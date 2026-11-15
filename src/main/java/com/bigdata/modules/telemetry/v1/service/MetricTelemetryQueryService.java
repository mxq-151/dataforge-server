package com.bigdata.modules.telemetry.v1.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.telemetry.shared.LogCircuitBreaker;
import com.bigdata.modules.telemetry.catalog.ServiceComponentCatalog;
import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.Requirement;
import com.bigdata.modules.telemetry.model.RequirementKind;
import com.bigdata.modules.telemetry.v1.catalog.TelemetryCatalogRegistry;
import com.bigdata.modules.telemetry.v1.dto.MetricQueryV1Request;
import com.bigdata.modules.telemetry.v1.model.BackendWarning;
import com.bigdata.modules.telemetry.v1.model.CatalogStatus;
import com.bigdata.modules.telemetry.v1.model.ComponentCatalogEntry;
import com.bigdata.modules.telemetry.v1.model.MetricDefinition;
import com.bigdata.modules.telemetry.v1.model.QueryMode;
import com.bigdata.modules.telemetry.v1.model.Target;
import com.bigdata.modules.telemetry.v1.routing.BackendCandidate;
import com.bigdata.modules.telemetry.v1.routing.BackendRouter;
import com.bigdata.modules.telemetry.v1.routing.BackendSelection;
import com.bigdata.modules.telemetry.v1.routing.BackendSkip;
import com.bigdata.modules.telemetry.v1.routing.RoutingProperties;
import com.bigdata.modules.telemetry.v1.spi.BackendCapability;
import com.bigdata.modules.telemetry.v1.spi.BackendException;
import com.bigdata.modules.telemetry.v1.spi.MetricBackend;
import com.bigdata.modules.telemetry.v1.spi.MetricQueryResult;
import com.bigdata.modules.telemetry.v1.spi.MetricQuerySpec;
import com.bigdata.modules.telemetry.v1.spi.TelemetryBackendRegistry;
import com.bigdata.modules.telemetry.v1.support.DurationParser;
import com.bigdata.modules.telemetry.v1.vo.TelemetryQueryV1Response;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 状态数据查询编排：目录 → 路由 → 能力判定 → 取数 → 回退判定。
 *
 * <p>回退只发生在：已发起调用、故障属于可用性类、备选与首选在配置中显式声明为同一等价组。
 * 空结果、部分结果、权限、参数与映射错误都不回退。</p>
 */
@Service
public class MetricTelemetryQueryService {

    private static final String CIRCUIT_PREFIX = "telemetry:v1:";

    private final TelemetryCatalogRegistry catalogRegistry;
    private final BackendRouter router;
    private final TelemetryBackendRegistry backendRegistry;
    private final LogCircuitBreaker circuitBreaker;

    public MetricTelemetryQueryService(TelemetryCatalogRegistry catalogRegistry,
                                       BackendRouter router,
                                       TelemetryBackendRegistry backendRegistry,
                                       LogCircuitBreaker circuitBreaker) {
        this.catalogRegistry = catalogRegistry;
        this.router = router;
        this.backendRegistry = backendRegistry;
        this.circuitBreaker = circuitBreaker;
    }

    public TelemetryQueryV1Response query(MetricQueryV1Request request) {
        long start = System.currentTimeMillis();
        String mode = request.getMode() == null ? "" : request.getMode().trim().toLowerCase();
        if ("catalog".equals(mode)) {
            return catalog(request, start);
        }
        QueryMode queryMode;
        try {
            queryMode = QueryMode.fromWire(mode);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "mode 必须是 range / instant / catalog");
        }
        Target target = requireTarget(request.getTarget(), true);
        ComponentCatalogEntry entry = requireEntry(target);
        if (isBlank(request.getMetric())) {
            throw new BusinessException(400, "缺少必填参数: metric");
        }
        MetricDefinition definition = entry.metrics().stream()
                .filter(metric -> metric.name().equals(request.getMetric()))
                .findFirst()
                .orElse(null);
        if (definition == null) {
            if (entry.metricCatalogStatus() == CatalogStatus.DECLARED_NOT_IMPLEMENTED) {
                return notImplemented(target, queryMode, entry.metricRequirements(), start);
            }
            throw new BusinessException(400, "未知指标: " + request.getMetric());
        }
        requireRefs(definition.requiredRefs(), request.getRef());
        validateRate(request, definition, queryMode);
        MetricQuerySpec spec = buildSpec(target, definition, queryMode, request);
        BackendSelection selection = router.selectMetric(target, definition, queryMode);
        return execute(target, queryMode, spec, selection,
                Boolean.TRUE.equals(request.getIncludeBackendCandidates()), start);
    }

    // ------------------------------------------------------------------ 编排

    private TelemetryQueryV1Response execute(Target target, QueryMode mode, MetricQuerySpec spec,
                                             BackendSelection selection, boolean includeCandidates,
                                             long start) {
        List<BackendSkip> skipped = new ArrayList<>(selection.skipped());
        List<TelemetryQueryV1Response.Attempt> attempts = new ArrayList<>();
        List<Requirement> requirements = new ArrayList<>();
        List<BackendWarning> warnings = new ArrayList<>();
        BackendCandidate firstAttempted = null;
        BackendException lastFailure = null;
        RoutingProperties.Circuit circuit = router.circuit();

        for (BackendCandidate candidate : selection.candidates()) {
            if (firstAttempted != null
                    && !router.equivalentBackends(firstAttempted, candidate, target.serviceType())) {
                skipped.add(new BackendSkip(candidate.backendId(), candidate.backendType().wire(),
                        CapabilityCode.NOT_EQUIVALENT,
                        "未声明与 " + firstAttempted.backendId() + " 同属一个等价组"));
                continue;
            }
            MetricBackend backend = backendRegistry.metric(candidate.backendId());
            if (backend == null) {
                skipped.add(new BackendSkip(candidate.backendId(), candidate.backendType().wire(),
                        CapabilityCode.NOT_CONFIGURED, "后端实例未注册"));
                continue;
            }
            BackendCapability capability = backend.evaluate(spec);
            if (!capability.isAvailable()) {
                skipped.add(new BackendSkip(candidate.backendId(), candidate.backendType().wire(),
                        capability.code(), capability.reason()));
                requirements.addAll(capability.requirements());
                continue;
            }
            String circuitKey = CIRCUIT_PREFIX + candidate.backendId();
            if (circuitBreaker.isOpen(circuitKey)) {
                skipped.add(new BackendSkip(candidate.backendId(), candidate.backendType().wire(),
                        CapabilityCode.BACKEND_UNAVAILABLE,
                        "熔断打开，剩余 " + circuitBreaker.retryAfterMs(circuitKey) + "ms"));
                continue;
            }

            long attemptStart = System.currentTimeMillis();
            try {
                MetricQueryResult result = backend.fetch(spec);
                circuitBreaker.recordSuccess(circuitKey);
                attempts.add(new TelemetryQueryV1Response.Attempt(candidate.backendId(),
                        candidate.backendType().wire(), CapabilityCode.OK,
                        System.currentTimeMillis() - attemptStart, false));
                boolean fallback = firstAttempted != null
                        || (selection.preferred() != null
                        && !selection.preferred().equals(candidate.backendId()));
                if (fallback) {
                    warnings.add(BackendWarning.of("PRIMARY_BACKEND_UNAVAILABLE",
                            "首选后端未产出数据，实际使用 " + candidate.backendId()));
                }
                warnings.addAll(result.warnings());
                return ok(target, mode, spec, selection, candidate, result, fallback,
                        skipped, attempts, warnings, includeCandidates, start);
            } catch (BackendException e) {
                circuitBreaker.recordFailure(circuitKey,
                        circuit.getFailureThreshold(), circuit.getOpenMs());
                attempts.add(new TelemetryQueryV1Response.Attempt(candidate.backendId(),
                        candidate.backendType().wire(), e.getCode(),
                        System.currentTimeMillis() - attemptStart, e.retryable()));
                requirements.addAll(e.getRequirements());
                if (firstAttempted == null) {
                    firstAttempted = candidate;
                }
                lastFailure = e;
                if (!e.availabilityFailure()) {
                    return failed(target, mode, selection, skipped, attempts,
                            e.getCode(), e.getMessage(), requirements, e.getRetryAfterMs(), start);
                }
            }
        }

        if (attempts.isEmpty()) {
            if (!skipped.isEmpty() && skipped.stream()
                    .allMatch(skip -> skip.code() == CapabilityCode.DATA_TYPE_NOT_IMPLEMENTED)) {
                List<Requirement> declared = skipped.stream()
                        .map(skip -> new Requirement(RequirementKind.CONFIG,
                                "telemetry.backends." + skip.backendType() + "[]",
                                skip.reason()))
                        .toList();
                return notImplemented(target, mode, declared, start);
            }
            CapabilityCode code = skipped.isEmpty()
                    ? CapabilityCode.BACKEND_UNAVAILABLE : skipped.get(0).code();
            return unavailable(target, mode, selection, skipped, attempts, code,
                    skipped.isEmpty() ? "没有可用的状态数据后端" : skipped.get(0).reason(),
                    requirements, start);
        }
        assert lastFailure != null;
        return failed(target, mode, selection, skipped, attempts, lastFailure.getCode(),
                lastFailure.getMessage(), requirements, lastFailure.getRetryAfterMs(), start);
    }

    // ------------------------------------------------------------------ 响应构造

    private TelemetryQueryV1Response ok(Target target, QueryMode mode, MetricQuerySpec spec,
                                        BackendSelection selection, BackendCandidate candidate,
                                        MetricQueryResult result, boolean fallback,
                                        List<BackendSkip> skipped,
                                        List<TelemetryQueryV1Response.Attempt> attempts,
                                        List<BackendWarning> warnings, boolean includeCandidates,
                                        long start) {
        List<TelemetryQueryV1Response.SeriesView> series = new ArrayList<>();
        for (MetricQueryResult.Series item : result.series()) {
            List<TelemetryQueryV1Response.SampleView> samples = new ArrayList<>();
            for (MetricQueryResult.Sample sample : item.samples()) {
                samples.add(new TelemetryQueryV1Response.SampleView(sample.timestamp(), sample.value()));
            }
            series.add(new TelemetryQueryV1Response.SeriesView(
                    new TelemetryQueryV1Response.MetricMeta(spec.definition().name(),
                            spec.definition().type(), spec.definition().unit()),
                    item.labels(), samples, samples.size()));
        }
        String resultState = result.isEmpty() ? "empty" : (result.truncated() ? "partial" : "data");
        TelemetryQueryV1Response.MetricData data = new TelemetryQueryV1Response.MetricData(
                mode.wire(), resultState, series, series.size());
        TelemetryQueryV1Response.BackendView backend = new TelemetryQueryV1Response.BackendView(
                new TelemetryQueryV1Response.Selected(candidate.backendId(),
                        candidate.backendType().wire(), mode.wire(), fallback),
                selection.preferred(), fallback, skipped, attempts,
                includeCandidates ? selection.candidates() : List.of());
        TelemetryQueryV1Response.MetaView meta = new TelemetryQueryV1Response.MetaView(
                System.currentTimeMillis() - start, result.truncated(), fallback, warnings,
                null, Instant.now().toString(), null, result.truncated(), null);
        return new TelemetryQueryV1Response("ok", false, target, mode.wire(), backend, data, null, meta);
    }

    private TelemetryQueryV1Response catalog(MetricQueryV1Request request, long start) {
        Target filter = request.getTarget();
        List<ComponentCatalogEntry> components = catalogRegistry.catalog().entries().stream()
                .filter(entry -> filter == null || isBlank(filter.serviceType())
                        || entry.serviceType().equals(filter.serviceType()))
                .filter(entry -> filter == null || isBlank(filter.componentId())
                        || entry.componentId().equals(filter.componentId()))
                .toList();
        TelemetryQueryV1Response.CatalogData data = new TelemetryQueryV1Response.CatalogData(
                catalogRegistry.catalog().version(), components);
        return new TelemetryQueryV1Response("ok", false, filter, "catalog",
                emptyBackend(), data, null,
                new TelemetryQueryV1Response.MetaView(System.currentTimeMillis() - start,
                        false, false, List.of(), null, Instant.now().toString(),
                        null, false, null));
    }

    private TelemetryQueryV1Response notImplemented(Target target, QueryMode mode,
                                                    List<Requirement> requirements, long start) {
        return new TelemetryQueryV1Response("not_implemented", false, target, mode.wire(),
                emptyBackend(), null,
                new TelemetryQueryV1Response.ErrorView(CapabilityCode.DATA_TYPE_NOT_IMPLEMENTED,
                        "该数据类型已声明但尚未实现；不会返回空数组或 0 值", requirements),
                new TelemetryQueryV1Response.MetaView(System.currentTimeMillis() - start,
                        false, false, List.of(), null, Instant.now().toString(), null, false, null));
    }

    private TelemetryQueryV1Response unavailable(Target target, QueryMode mode,
                                                 BackendSelection selection, List<BackendSkip> skipped,
                                                 List<TelemetryQueryV1Response.Attempt> attempts,
                                                 CapabilityCode code, String reason,
                                                 List<Requirement> requirements, long start) {
        TelemetryQueryV1Response.BackendView backend = new TelemetryQueryV1Response.BackendView(
                null, selection.preferred(), false, skipped, attempts, List.of());
        return new TelemetryQueryV1Response("unavailable", code.retryable(), target, mode.wire(),
                backend, null,
                new TelemetryQueryV1Response.ErrorView(code, reason, requirements),
                new TelemetryQueryV1Response.MetaView(System.currentTimeMillis() - start,
                        false, false, List.of(), null, Instant.now().toString(), null, false, null));
    }

    private TelemetryQueryV1Response failed(Target target, QueryMode mode, BackendSelection selection,
                                            List<BackendSkip> skipped,
                                            List<TelemetryQueryV1Response.Attempt> attempts,
                                            CapabilityCode code, String reason,
                                            List<Requirement> requirements, Long retryAfterMs,
                                            long start) {
        TelemetryQueryV1Response.BackendView backend = new TelemetryQueryV1Response.BackendView(
                null, selection.preferred(), false, skipped, attempts, List.of());
        return new TelemetryQueryV1Response("failed", code.retryable(), target, mode.wire(),
                backend, null,
                new TelemetryQueryV1Response.ErrorView(code, reason, requirements),
                new TelemetryQueryV1Response.MetaView(System.currentTimeMillis() - start,
                        false, false, List.of(), retryAfterMs, Instant.now().toString(),
                        null, false, null));
    }

    private TelemetryQueryV1Response.BackendView emptyBackend() {
        return new TelemetryQueryV1Response.BackendView(null, null, false,
                List.of(), List.of(), List.of());
    }

    // ------------------------------------------------------------------ 校验

    private Target requireTarget(Target target, boolean requireHost) {
        if (target == null) {
            throw new BusinessException(400, "缺少必填参数: target");
        }
        require(target.serviceType(), "target.serviceType");
        require(target.componentId(), "target.componentId");
        if (requireHost) {
            require(target.hostId(), "target.hostId");
        }
        if (!ServiceComponentCatalog.components().containsKey(target.serviceType())) {
            throw new BusinessException(400, "未知服务类型: " + target.serviceType());
        }
        if (!ServiceComponentCatalog.supports(target.serviceType(), target.componentId())) {
            throw new BusinessException(400, "组件不属于该服务: " + target.componentId());
        }
        return target;
    }

    private ComponentCatalogEntry requireEntry(Target target) {
        return catalogRegistry.catalog().entry(target.serviceType(), target.componentId())
                .orElseThrow(() -> new BusinessException(400,
                        "该服务组件尚未纳入目录: " + target.componentId()));
    }

    private void requireRefs(List<String> requiredRefs, Map<String, String> refs) {
        if (requiredRefs == null || requiredRefs.isEmpty()) {
            return;
        }
        for (String key : requiredRefs) {
            if (refs == null || isBlank(refs.get(key))) {
                throw new BusinessException(400, "缺少必填引用: ref." + key);
            }
        }
    }

    private MetricQuerySpec buildSpec(Target target, MetricDefinition definition, QueryMode mode,
                                      MetricQueryV1Request request) {
        Instant time = null;
        Instant start = null;
        Instant end = null;
        Duration step = null;
        try {
            if (mode == QueryMode.INSTANT) {
                time = isBlank(request.getTime()) ? Instant.now() : Instant.parse(request.getTime());
            } else {
                if (request.getTimeRange() == null
                        || request.getTimeRange().start() == null
                        || request.getTimeRange().end() == null) {
                    throw new IllegalArgumentException("range 查询必须提供 timeRange.start / timeRange.end");
                }
                start = request.getTimeRange().start();
                end = request.getTimeRange().end();
                step = DurationParser.parse(request.getStep());
            }
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new BusinessException(400, e.getMessage());
        }
        Duration timeout = request.getTimeoutMs() == null ? null
                : Duration.ofMillis(Math.max(request.getTimeoutMs(), 1));
        return new MetricQuerySpec(target, definition, request.getLabelMatchers(), mode, time,
                start, end, step, request.getRef(), timeout,
                Boolean.TRUE.equals(request.getRate()));
    }

    /** rate 只允许 counter + range；不满足直接参数错误，不静默忽略。 */
    private void validateRate(MetricQueryV1Request request, MetricDefinition definition, QueryMode mode) {
        if (!Boolean.TRUE.equals(request.getRate())) {
            return;
        }
        if (mode != QueryMode.RANGE) {
            throw new BusinessException(400, "rate 只允许用于 range 查询，当前 mode=" + mode.wire());
        }
        if (!"counter".equalsIgnoreCase(definition.type())) {
            throw new BusinessException(400,
                    "rate 只允许用于 counter 指标，当前类型=" + definition.type());
        }
    }

    private void require(String value, String field) {
        if (isBlank(value)) {
            throw new BusinessException(400, "缺少必填参数: " + field);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
