package com.bigdata.modules.telemetry.v1.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.telemetry.shared.LogCircuitBreaker;
import com.bigdata.modules.telemetry.catalog.ServiceComponentCatalog;
import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.Requirement;
import com.bigdata.modules.telemetry.model.RequirementKind;
import com.bigdata.modules.telemetry.v1.catalog.TelemetryCatalogRegistry;
import com.bigdata.modules.telemetry.v1.dto.LogQueryV1Request;
import com.bigdata.modules.telemetry.v1.model.BackendWarning;
import com.bigdata.modules.telemetry.v1.model.ComponentCatalogEntry;
import com.bigdata.modules.telemetry.v1.model.LogDataType;
import com.bigdata.modules.telemetry.v1.model.LogFilter;
import com.bigdata.modules.telemetry.v1.model.LogStreamDefinition;
import com.bigdata.modules.telemetry.v1.model.Target;
import com.bigdata.modules.telemetry.v1.routing.BackendCandidate;
import com.bigdata.modules.telemetry.v1.routing.BackendRouter;
import com.bigdata.modules.telemetry.v1.routing.BackendSelection;
import com.bigdata.modules.telemetry.v1.routing.BackendSkip;
import com.bigdata.modules.telemetry.v1.routing.RoutingProperties;
import com.bigdata.modules.telemetry.v1.spi.BackendCapability;
import com.bigdata.modules.telemetry.v1.spi.BackendException;
import com.bigdata.modules.telemetry.v1.spi.LogBackend;
import com.bigdata.modules.telemetry.v1.spi.LogQueryResult;
import com.bigdata.modules.telemetry.v1.spi.LogQuerySpec;
import com.bigdata.modules.telemetry.v1.spi.TelemetryBackendRegistry;
import com.bigdata.modules.telemetry.v1.vo.TelemetryQueryV1Response;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 日志查询编排：按 dataType 分流服务日志（log.service）与任务日志（log.task）。
 *
 * <p>两类日志的后端集合严格隔离，禁止互相回退；slowquery 只在 log.service 下声明。
 * 声明未实现或分类不支持的组合返回明确的 code + requirements，不返回空数组。</p>
 */
@Service
public class LogTelemetryQueryService {

    private static final String CIRCUIT_PREFIX = "telemetry:v1:log:";
    private static final Set<String> STREAMS = Set.of("stdout", "stderr", "slowquery");
    private static final int DEFAULT_LIMIT = 500;
    private static final long DEFAULT_MAX_BYTES = 262144L;

    private final TelemetryCatalogRegistry catalogRegistry;
    private final BackendRouter router;
    private final TelemetryBackendRegistry backendRegistry;
    private final LogCircuitBreaker circuitBreaker;

    public LogTelemetryQueryService(TelemetryCatalogRegistry catalogRegistry,
                                    BackendRouter router,
                                    TelemetryBackendRegistry backendRegistry,
                                    LogCircuitBreaker circuitBreaker) {
        this.catalogRegistry = catalogRegistry;
        this.router = router;
        this.backendRegistry = backendRegistry;
        this.circuitBreaker = circuitBreaker;
    }

    public TelemetryQueryV1Response query(LogQueryV1Request request) {
        long start = System.currentTimeMillis();
        String mode = request.getMode() == null || request.getMode().isBlank()
                ? "data" : request.getMode().trim().toLowerCase();
        if ("catalog".equals(mode)) {
            return catalog(request, start);
        }
        if (!"data".equals(mode)) {
            throw new BusinessException(400, "mode 必须是 data / catalog");
        }
        Target target = requireTarget(request.getTarget());
        ComponentCatalogEntry entry = requireEntry(target);
        String stream = request.getStream();
        if (stream == null || stream.isBlank()) {
            throw new BusinessException(400, "缺少必填参数: stream");
        }
        if (!STREAMS.contains(stream)) {
            throw new BusinessException(400, "未知日志流: " + stream + "（stdout / stderr / slowquery）");
        }
        LogDataType dataType = requireDataType(request.getDataType());
        if (!dataType.supportsStream(stream)) {
            return unsupported(target, dataType.wire() + " 不支持 stream=" + stream
                            + "；log.task 只支持 stdout / stderr，slowquery 只在 log.service 下",
                    CapabilityCode.STREAM_NOT_SUPPORTED, List.of(), start);
        }
        LogStreamDefinition definition = entry.logStreams().stream()
                .filter(item -> item.dataType() == dataType && item.stream().equals(stream))
                .findFirst()
                .orElse(null);
        if (definition == null) {
            LogDataType other = entry.logStreams().stream()
                    .filter(item -> item.stream().equals(stream))
                    .map(LogStreamDefinition::dataType)
                    .findFirst()
                    .orElse(null);
            if (other != null) {
                return unsupported(target, "stream=" + stream + " 只在 " + other.wire()
                                + " 下声明，不能用 " + dataType.wire() + " 查询",
                        CapabilityCode.STREAM_NOT_SUPPORTED, List.of(), start);
            }
            return notImplemented(target, List.of(new Requirement(RequirementKind.CONFIG,
                    "telemetry.backends.local-disk[].sources",
                    "该组件尚未声明 " + dataType.wire() + " 的 " + stream + " 日志流")), start);
        }
        requireRefs(definition.requiredRefs(), request.getRef());
        requireAnyRefs(definition.requiredAnyRefs(), request.getRef(), dataType);
        LogQuerySpec spec = buildSpec(target, dataType, stream, request);
        BackendSelection selection = router.selectLog(target, definition);
        return execute(target, definition, spec, selection,
                Boolean.TRUE.equals(request.getIncludeBackendCandidates()), start);
    }

    // ------------------------------------------------------------------ 编排

    private TelemetryQueryV1Response execute(Target target, LogStreamDefinition definition,
                                             LogQuerySpec spec, BackendSelection selection,
                                             boolean includeCandidates, long start) {
        String stream = definition.stream();
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
            LogBackend backend = backendRegistry.log(candidate.backendId());
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
                LogQueryResult result = backend.fetch(spec);
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
                return ok(target, definition, selection, candidate, result, fallback,
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
                    return failed(target, stream, selection, skipped, attempts, e.getCode(),
                            e.getMessage(), requirements, e.getRetryAfterMs(), start);
                }
            }
        }

        if (attempts.isEmpty()) {
            if (!skipped.isEmpty() && skipped.stream()
                    .allMatch(skip -> skip.code() == CapabilityCode.DATA_TYPE_NOT_IMPLEMENTED)) {
                List<Requirement> declared = new ArrayList<>(definition.requirements());
                if (declared.isEmpty()) {
                    for (BackendSkip skip : skipped) {
                        declared.add(new Requirement(RequirementKind.CONFIG,
                                "telemetry.backends." + skip.backendType() + "[]", skip.reason()));
                    }
                }
                return notImplemented(target, declared, start);
            }
            CapabilityCode code = skipped.isEmpty()
                    ? CapabilityCode.BACKEND_UNAVAILABLE : skipped.get(0).code();
            List<Requirement> merged = new ArrayList<>(definition.requirements());
            merged.addAll(requirements);
            return unavailable(target, stream, selection, skipped, attempts, code,
                    skipped.isEmpty() ? "没有可用的日志后端" : skipped.get(0).reason(),
                    merged, start);
        }
        assert lastFailure != null;
        return failed(target, stream, selection, skipped, attempts, lastFailure.getCode(),
                lastFailure.getMessage(), requirements, lastFailure.getRetryAfterMs(), start);
    }

    // ------------------------------------------------------------------ 响应构造

    private TelemetryQueryV1Response ok(Target target, LogStreamDefinition definition,
                                        BackendSelection selection,
                                        BackendCandidate candidate, LogQueryResult result,
                                        boolean fallback, List<BackendSkip> skipped,
                                        List<TelemetryQueryV1Response.Attempt> attempts,
                                        List<BackendWarning> warnings, boolean includeCandidates,
                                        long start) {
        String stream = definition.stream();
        String resultState = result.isEmpty() ? "empty" : (result.truncated() ? "partial" : "data");
        TelemetryQueryV1Response.LogData data = new TelemetryQueryV1Response.LogData(
                definition.dataType().wire(), stream, resultState,
                result.entries(), result.entries().size());
        TelemetryQueryV1Response.ScanView scan = result.scan() == null ? null
                : new TelemetryQueryV1Response.ScanView(result.scan().files(),
                result.scan().bytes(), result.scan().lines(), result.scan().matched());
        TelemetryQueryV1Response.BackendView backend = new TelemetryQueryV1Response.BackendView(
                new TelemetryQueryV1Response.Selected(candidate.backendId(),
                        candidate.backendType().wire(), "data", fallback),
                selection.preferred(), fallback, skipped, attempts,
                includeCandidates ? selection.candidates() : List.of());
        TelemetryQueryV1Response.MetaView meta = new TelemetryQueryV1Response.MetaView(
                System.currentTimeMillis() - start, result.truncated(), fallback, warnings,
                null, Instant.now().toString(), result.nextCursor(), result.truncated(), scan);
        return new TelemetryQueryV1Response("ok", false, target, "data", backend, data, null, meta);
    }

    private TelemetryQueryV1Response catalog(LogQueryV1Request request, long start) {
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

    private TelemetryQueryV1Response notImplemented(Target target, List<Requirement> requirements,
                                                    long start) {
        return new TelemetryQueryV1Response("not_implemented", false, target, "data",
                emptyBackend(), null,
                new TelemetryQueryV1Response.ErrorView(CapabilityCode.DATA_TYPE_NOT_IMPLEMENTED,
                        "该日志流已声明但尚未实现；不会返回空数组", requirements),
                new TelemetryQueryV1Response.MetaView(System.currentTimeMillis() - start,
                        false, false, List.of(), null, Instant.now().toString(), null, false, null));
    }

    /** 分类 × stream 不支持的组合：显式返回 code，不静默返回空。 */
    private TelemetryQueryV1Response unsupported(Target target, String reason, CapabilityCode code,
                                                 List<Requirement> requirements, long start) {
        return new TelemetryQueryV1Response("unavailable", false, target, "data",
                emptyBackend(), null,
                new TelemetryQueryV1Response.ErrorView(code, reason, requirements),
                new TelemetryQueryV1Response.MetaView(System.currentTimeMillis() - start,
                        false, false, List.of(), null, Instant.now().toString(), null, false, null));
    }

    private TelemetryQueryV1Response unavailable(Target target, String stream,
                                                 BackendSelection selection, List<BackendSkip> skipped,
                                                 List<TelemetryQueryV1Response.Attempt> attempts,
                                                 CapabilityCode code, String reason,
                                                 List<Requirement> requirements, long start) {
        TelemetryQueryV1Response.BackendView backend = new TelemetryQueryV1Response.BackendView(
                null, selection.preferred(), false, skipped, attempts, List.of());
        return new TelemetryQueryV1Response("unavailable", code.retryable(), target, "data",
                backend, null,
                new TelemetryQueryV1Response.ErrorView(code, reason, requirements),
                new TelemetryQueryV1Response.MetaView(System.currentTimeMillis() - start,
                        false, false, List.of(), null, Instant.now().toString(), null, false, null));
    }

    private TelemetryQueryV1Response failed(Target target, String stream, BackendSelection selection,
                                            List<BackendSkip> skipped,
                                            List<TelemetryQueryV1Response.Attempt> attempts,
                                            CapabilityCode code, String reason,
                                            List<Requirement> requirements, Long retryAfterMs,
                                            long start) {
        TelemetryQueryV1Response.BackendView backend = new TelemetryQueryV1Response.BackendView(
                null, selection.preferred(), false, skipped, attempts, List.of());
        return new TelemetryQueryV1Response("failed", code.retryable(), target, "data",
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

    private Target requireTarget(Target target) {
        if (target == null) {
            throw new BusinessException(400, "缺少必填参数: target");
        }
        require(target.serviceType(), "target.serviceType");
        require(target.componentId(), "target.componentId");
        require(target.hostId(), "target.hostId");
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

    private LogDataType requireDataType(String value) {
        if (isBlank(value)) {
            throw new BusinessException(400, "缺少必填参数: dataType（log.service / log.task）");
        }
        return LogDataType.find(value).orElseThrow(() -> new BusinessException(400,
                "未知 dataType: " + value + "（log.service / log.task）"));
    }

    /** 多选一引用：log.task 要求 ref.taskId / ref.jobName 至少一个，两者都给时以 taskId 为准。 */
    private void requireAnyRefs(List<String> anyRefs, Map<String, String> refs, LogDataType dataType) {
        if (anyRefs == null || anyRefs.isEmpty()) {
            return;
        }
        boolean present = anyRefs.stream().anyMatch(key -> refs != null && !isBlank(refs.get(key)));
        if (present) {
            return;
        }
        String names = anyRefs.stream().map(key -> "ref." + key).collect(Collectors.joining(" / "));
        String hint = dataType == LogDataType.TASK ? "；两者都给时以 taskId 为准" : "";
        throw new BusinessException(400, "缺少必填引用: " + names + "（至少提供一个" + hint + "）");
    }

    private LogQuerySpec buildSpec(Target target, LogDataType dataType, String stream,
                                   LogQueryV1Request request) {
        LogFilter.MatchMode matchMode = LogFilter.MatchMode.ALL;
        if (request.getMatchMode() != null && !request.getMatchMode().isBlank()) {
            matchMode = "any".equalsIgnoreCase(request.getMatchMode())
                    ? LogFilter.MatchMode.ANY : LogFilter.MatchMode.ALL;
        }
        LogFilter filter = new LogFilter(request.getKeywords(), request.getExcludeKeywords(),
                matchMode, Boolean.TRUE.equals(request.getRegex()),
                Boolean.TRUE.equals(request.getCaseSensitive()));
        int limit = request.getLimit() == null ? DEFAULT_LIMIT : request.getLimit();
        long maxBytes = request.getMaxBytes() == null ? DEFAULT_MAX_BYTES : request.getMaxBytes();
        Duration timeout = request.getTimeoutMs() == null ? null
                : Duration.ofMillis(Math.max(request.getTimeoutMs(), 1));
        return new LogQuerySpec(target, dataType, stream, request.getTimeRange(), filter,
                request.getCursor(), limit, maxBytes, request.getRef(), timeout);
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
