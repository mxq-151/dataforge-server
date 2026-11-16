package com.bigdata.modules.telemetry.service.metricdata;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.telemetry.util.CircuitBreaker;
import com.bigdata.modules.telemetry.service.catalog.ServiceIdentityCatalog;
import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.Requirement;
import com.bigdata.modules.telemetry.common.model.RequirementKind;
import com.bigdata.modules.telemetry.service.catalog.TelemetryCatalogRegistry;
import com.bigdata.modules.telemetry.service.metricdata.model.DiscoveredMetric;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDiscoveryResult;
import com.bigdata.modules.telemetry.service.metricdata.discovery.MetricDiscoveryService;
import com.bigdata.modules.telemetry.dto.MetricQueryRequest;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataCatalogEntry;
import com.bigdata.modules.telemetry.service.metadata.registry.MetadataCatalogRegistry;
import com.bigdata.modules.telemetry.config.TelemetryMetadataProperties;
import com.bigdata.modules.telemetry.common.model.BackendWarning;
import com.bigdata.modules.telemetry.vo.CatalogStatus;
import com.bigdata.modules.telemetry.vo.ComponentCatalogEntry;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDefinition;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDiscoveryMode;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricPattern;
import com.bigdata.modules.telemetry.common.model.QueryMode;
import com.bigdata.modules.telemetry.common.model.Sampling;
import com.bigdata.modules.telemetry.common.model.Target;
import com.bigdata.modules.telemetry.service.routing.model.BackendCandidate;
import com.bigdata.modules.telemetry.service.routing.BackendRouter;
import com.bigdata.modules.telemetry.service.routing.model.BackendSelection;
import com.bigdata.modules.telemetry.service.routing.model.BackendSkip;
import com.bigdata.modules.telemetry.config.RoutingProperties;
import com.bigdata.modules.telemetry.common.model.BackendCapability;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.bigdata.modules.telemetry.service.metricdata.spi.MetricBackend;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricQueryResult;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricQuerySpec;
import com.bigdata.modules.telemetry.config.TelemetryBackendRegistry;
import com.bigdata.modules.telemetry.util.DurationParser;
import com.bigdata.modules.telemetry.vo.TelemetryQueryResponse;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.bigdata.modules.telemetry.vo.MetricData;
import com.bigdata.modules.telemetry.vo.SeriesView;
import com.bigdata.modules.telemetry.vo.MetricMeta;
import com.bigdata.modules.telemetry.vo.SampleView;
import com.bigdata.modules.telemetry.vo.CatalogData;
import com.bigdata.modules.telemetry.vo.MetricCatalogView;
import com.bigdata.modules.telemetry.util.Strings;

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
    private final MetadataCatalogRegistry metadataCatalogRegistry;
    private final BackendRouter router;
    private final TelemetryBackendRegistry backendRegistry;
    private final CircuitBreaker circuitBreaker;
    private final MetricDiscoveryService discoveryService;
    private final TelemetryMetadataProperties metadataProperties;

    public MetricTelemetryQueryService(TelemetryCatalogRegistry catalogRegistry,
                                       MetadataCatalogRegistry metadataCatalogRegistry,
                                       BackendRouter router,
                                       TelemetryBackendRegistry backendRegistry,
                                       CircuitBreaker circuitBreaker,
                                       MetricDiscoveryService discoveryService,
                                       TelemetryMetadataProperties metadataProperties) {
        this.catalogRegistry = catalogRegistry;
        this.metadataCatalogRegistry = metadataCatalogRegistry;
        this.router = router;
        this.backendRegistry = backendRegistry;
        this.circuitBreaker = circuitBreaker;
        this.discoveryService = discoveryService;
        this.metadataProperties = metadataProperties;
    }

    public TelemetryQueryResponse query(MetricQueryRequest request) {
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
        if (Strings.isBlank(request.getMetric())) {
            throw new BusinessException(400, "缺少必填参数: metric");
        }
        String requestedMetric = request.getMetric();
        MetricDefinition definition = entry.metrics().stream()
                .filter(metric -> metric.name().equals(requestedMetric))
                .findFirst()
                .orElse(null);
        if (definition != null) {
            // 已知语义需要 ref 时先报参数错误，避免发现校验把「缺 ref」误报成「未暴露」。
            requireRefs(definition.requiredRefs(), request.getRef());
        }
        MetricDiscoveryResult discovery;
        if (definition == null) {
            if (entry.metricCatalogStatus() == CatalogStatus.DECLARED_NOT_IMPLEMENTED) {
                return notImplemented(target, queryMode, entry.metricRequirements(), start);
            }
            discovery = discover(target, request);
            PatternMatch match = matchByPattern(entry, requestedMetric);
            if (match.ambiguous()) {
                return failed(target, queryMode, emptySelection(), List.of(), List.of(),
                        CapabilityCode.METRIC_PATTERN_AMBIGUOUS,
                        "指标名 " + requestedMetric + " 命中多个语义模式，拒绝猜测: "
                                + match.candidates().stream().map(MetricDefinition::name).toList(),
                        List.of(), null, start);
            }
            if (match.definition() != null) {
                definition = match.definition();
            } else if (!discovery.supported()) {
                return unavailable(target, queryMode, emptySelection(), List.of(), List.of(),
                        CapabilityCode.METRIC_DISCOVERY_UNSUPPORTED,
                        discovery.reason() == null ? "该引擎不支持指标名发现" : discovery.reason(),
                        discovery.requirements(), start);
            } else if (!containsMetric(discovery, requestedMetric)) {
                return unavailable(target, queryMode, emptySelection(), List.of(), List.of(),
                        CapabilityCode.METRIC_NOT_EXPOSED,
                        discovery.manual()
                                ? "手工名字表中没有该指标: " + requestedMetric
                                : "实例未暴露该指标: " + requestedMetric,
                        discovery.requirements(), start);
            } else {
                definition = unknownDefinition(requestedMetric, entry,
                        discoveredMetric(discovery, requestedMetric));
                if (definition == null) {
                    return unavailable(target, queryMode, emptySelection(), List.of(), List.of(),
                            CapabilityCode.METRIC_TYPE_UNKNOWN,
                            "指标类型未知，且目录没有可路由的后端: " + requestedMetric,
                            List.of(), start);
                }
            }
        } else {
            discovery = discover(target, request);
            if (definition.discovery() == MetricDiscoveryMode.DISCOVERED
                    && discovery.authoritative() && !matchesDefinition(definition, discovery)) {
                return unavailable(target, queryMode, emptySelection(), List.of(), List.of(),
                        CapabilityCode.METRIC_NOT_EXPOSED,
                        "实例未暴露该指标: " + definition.name(),
                        discovery.requirements(), start);
            }
        }
        requireRefs(definition.requiredRefs(), request.getRef());
        validateRate(request, definition, queryMode);
        MetricQuerySpec spec = buildSpec(target, definition, queryMode, request);
        BackendSelection selection = router.selectMetric(target, definition, queryMode);
        return execute(target, queryMode, spec, selection,
                Boolean.TRUE.equals(request.getIncludeBackendCandidates()), start);
    }

    // ------------------------------------------------------------------ 发现与语义匹配

    private MetricDiscoveryResult discover(Target target, MetricQueryRequest request) {
        int limit = Math.max(metadataProperties.getQuery().getMaxMetrics(), 1);
        Duration timeout = Duration.ofMillis(
                Math.max(metadataProperties.getQuery().getTimeoutMs(), 1));
        return discoveryService.discover(target, request.getRef(), timeout, limit);
    }

    private PatternMatch matchByPattern(ComponentCatalogEntry entry, String metricName) {
        List<MetricDefinition> candidates = entry.metrics().stream()
                .filter(metric -> metric.patternMatches(metricName))
                .toList();
        if (candidates.isEmpty()) {
            return new PatternMatch(null, false, List.of());
        }
        int best = candidates.stream()
                .mapToInt(metric -> metric.pattern().specificity())
                .max().orElse(0);
        List<MetricDefinition> bestCandidates = candidates.stream()
                .filter(metric -> metric.pattern().specificity() == best)
                .toList();
        if (bestCandidates.size() > 1) {
            return new PatternMatch(null, true, bestCandidates);
        }
        MetricDefinition matched = bestCandidates.get(0);
        return new PatternMatch(new MetricDefinition(matched.name(), matched.type(), matched.unit(),
                matched.scope(), matched.labels(), matched.requiredRefs(),
                matched.backendPreference(), matched.supportedBackends(), metricName,
                matched.endpoint(), matched.verified(), matched.note(), matched.pattern(),
                matched.discovery()), false, List.of());
    }

    private boolean containsMetric(MetricDiscoveryResult discovery, String metricName) {
        return discovery.metrics().stream().anyMatch(metric -> metric.name().equals(metricName));
    }

    private boolean matchesDefinition(MetricDefinition definition, MetricDiscoveryResult discovery) {
        if (definition.pattern() == null) {
            return containsMetric(discovery, definition.name());
        }
        return discovery.metrics().stream()
                .anyMatch(metric -> definition.patternMatches(metric.name()));
    }

    /**
     * 发现到了名字但没有任何语义模式命中：类型/单位直接采用发现来源（如 VM /api/v1/metadata
     * 或 Prometheus 文本 # TYPE）。类型仍未知时允许查询，但 rate=true 会被拒绝。
     */
    private MetricDefinition unknownDefinition(String metricName, ComponentCatalogEntry entry,
                                               DiscoveredMetric discovered) {
        Map<String, com.bigdata.modules.telemetry.common.model.BackendBinding> bindings =
                new java.util.LinkedHashMap<>();
        for (MetricDefinition metric : entry.metrics()) {
            for (com.bigdata.modules.telemetry.common.model.BackendBinding binding : metric.supportedBackends()) {
                bindings.putIfAbsent(binding.backendId(), binding);
            }
        }
        if (bindings.isEmpty()) {
            for (com.bigdata.modules.telemetry.service.metricdata.spi.MetricBackend backend
                    : backendRegistry.metricBackends()) {
                bindings.putIfAbsent(backend.backendId(),
                        new com.bigdata.modules.telemetry.common.model.BackendBinding(
                                backend.backendId(), backend.backendType(),
                                com.bigdata.modules.telemetry.common.model.BackendStatus.IMPLEMENTED,
                                100 + bindings.size(),
                                Set.of(QueryMode.RANGE, QueryMode.INSTANT),
                                Set.of(Sampling.STEP), false, null,
                                "运行时发现指标，按后端能力动态路由"));
            }
        }
        if (bindings.isEmpty()) {
            return null;
        }
        String type = discovered == null ? null : discovered.type();
        String unit = discovered == null ? null : discovered.unit();
        return new MetricDefinition(metricName, type, unit, "unknown", List.of(), List.of(),
                Map.of(), new ArrayList<>(bindings.values()), metricName, null, null,
                type == null
                        ? "发现结果未命中语义模式，类型未知；禁止 rate=true"
                        : "类型来自运行时元数据（" + (discovered == null ? "unknown" : discovered.source())
                        + "）",
                MetricPattern.exact(metricName), MetricDiscoveryMode.DISCOVERED);
    }

    private DiscoveredMetric discoveredMetric(MetricDiscoveryResult discovery, String metricName) {
        return discovery.metrics().stream()
                .filter(metric -> metric.name().equals(metricName))
                .findFirst().orElse(null);
    }

    private BackendSelection emptySelection() {
        return new BackendSelection(List.of(), List.of(), null);
    }

    private record PatternMatch(MetricDefinition definition, boolean ambiguous,
                                List<MetricDefinition> candidates) {
    }

    // ------------------------------------------------------------------ 编排

    private TelemetryQueryResponse execute(Target target, QueryMode mode, MetricQuerySpec spec,
                                             BackendSelection selection, boolean includeCandidates,
                                             long start) {
        List<BackendSkip> skipped = new ArrayList<>(selection.skipped());
        List<TelemetryQueryResponse.Attempt> attempts = new ArrayList<>();
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
                attempts.add(new TelemetryQueryResponse.Attempt(candidate.backendId(),
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
                attempts.add(new TelemetryQueryResponse.Attempt(candidate.backendId(),
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

    private TelemetryQueryResponse ok(Target target, QueryMode mode, MetricQuerySpec spec,
                                        BackendSelection selection, BackendCandidate candidate,
                                        MetricQueryResult result, boolean fallback,
                                        List<BackendSkip> skipped,
                                        List<TelemetryQueryResponse.Attempt> attempts,
                                        List<BackendWarning> warnings, boolean includeCandidates,
                                        long start) {
        List<SeriesView> series = new ArrayList<>();
        for (MetricQueryResult.Series item : result.series()) {
            List<SampleView> samples = new ArrayList<>();
            for (MetricQueryResult.Sample sample : item.samples()) {
                samples.add(new SampleView(sample.timestamp(), sample.value()));
            }
            series.add(new SeriesView(
                    new MetricMeta(spec.definition().name(),
                            spec.definition().type(), spec.definition().unit()),
                    item.labels(), samples, samples.size()));
        }
        String resultState = result.isEmpty() ? "empty" : (result.truncated() ? "partial" : "data");
        MetricData data = new MetricData(
                mode.wire(), resultState, series, series.size());
        TelemetryQueryResponse.BackendView backend = new TelemetryQueryResponse.BackendView(
                new TelemetryQueryResponse.Selected(candidate.backendId(),
                        candidate.backendType().wire(), mode.wire(), fallback),
                selection.preferred(), fallback, skipped, attempts,
                includeCandidates ? selection.candidates() : List.of());
        TelemetryQueryResponse.MetaView meta = new TelemetryQueryResponse.MetaView(
                System.currentTimeMillis() - start, result.truncated(), fallback, warnings,
                null, Instant.now().toString(), null, result.truncated(), null);
        return new TelemetryQueryResponse("ok", false, target, mode.wire(), backend, data, null, meta);
    }

    private TelemetryQueryResponse catalog(MetricQueryRequest request, long start) {
        Target filter = request.getTarget();
        List<ComponentCatalogEntry> components = catalogRegistry.catalog().entries().stream()
                .filter(entry -> filter == null || Strings.isBlank(filter.serviceType())
                        || entry.serviceType().equals(filter.serviceType()))
                .filter(entry -> filter == null || Strings.isBlank(filter.componentId())
                        || entry.componentId().equals(filter.componentId()))
                .toList();
        List<MetricCatalogView> views = new ArrayList<>();
        List<BackendWarning> warnings = new ArrayList<>();
        Set<String> warningKeys = new HashSet<>();
        int limit = Math.max(metadataProperties.getQuery().getMaxMetrics(), 1);
        Duration timeout = Duration.ofMillis(Math.max(metadataProperties.getQuery().getTimeoutMs(), 1));
        for (ComponentCatalogEntry entry : components) {
            Target target = new Target(entry.serviceType(), entry.componentId(), Target.CLUSTER_HOST);
            MetricDiscoveryResult discovery = discoveryService.discover(target, Map.of(), timeout, limit);
            for (BackendWarning warning : discovery.warnings()) {
                if (warningKeys.add(warning.code() + "|" + warning.message())) {
                    warnings.add(warning);
                }
            }
            if (entry.metrics().isEmpty()) {
                if (discovery.supported()) {
                    for (DiscoveredMetric discovered : discovery.metrics()) {
                        boolean typeKnown = discovered.type() != null && !discovered.type().isBlank();
                        views.add(new MetricCatalogView(
                                entry.serviceType(), entry.componentId(), discovered.name(),
                                discovered.type(), discovered.unit(),
                                "counter".equalsIgnoreCase(discovered.type()),
                                null, "DISCOVERED", true, typeKnown, discovered.source(),
                                discovery.manual(),
                                typeKnown ? "类型来自运行时元数据"
                                        : "发现结果未给出类型；rate=true 将被拒绝"));
                    }
                }
                continue;
            }
            for (MetricDefinition metric : entry.metrics()) {
                views.add(catalogView(entry, metric, discovery));
            }
            if (discovery.authoritative()) {
                for (DiscoveredMetric discovered : discovery.metrics()) {
                    boolean matched = entry.metrics().stream()
                            .anyMatch(metric -> metric.patternMatches(discovered.name()));
                    if (!matched) {
                        boolean typeKnown = discovered.type() != null && !discovered.type().isBlank();
                        views.add(new MetricCatalogView(
                                entry.serviceType(), entry.componentId(), discovered.name(),
                                discovered.type(), discovered.unit(),
                                "counter".equalsIgnoreCase(discovered.type()),
                                null, "DISCOVERED", true, typeKnown, discovered.source(), false,
                                typeKnown ? "类型来自运行时元数据"
                                        : "发现结果未命中语义模式，类型未知；rate=true 将被拒绝"));
                    }
                }
            }
        }
        CatalogData data = new CatalogData(
                catalogRegistry.catalog().version(), components, metadataOf(filter), views, warnings);
        return new TelemetryQueryResponse("ok", false, filter, "catalog",
                emptyBackend(), data, null,
                new TelemetryQueryResponse.MetaView(System.currentTimeMillis() - start,
                        false, false, warnings, null, Instant.now().toString(),
                        null, false, null));
    }

    private MetricCatalogView catalogView(
            ComponentCatalogEntry entry, MetricDefinition metric, MetricDiscoveryResult discovery) {
        boolean typeKnown = metric.type() != null && !metric.type().isBlank();
        String pattern = metric.pattern() == null ? null : metric.pattern().wire();
        if (!discovery.supported()) {
            boolean manual = metric.discovery() == MetricDiscoveryMode.MANUAL;
            return new MetricCatalogView(entry.serviceType(),
                    entry.componentId(), metric.name(), metric.type(), metric.unit(),
                    metric.rateApplicable(), pattern, metric.discovery().name(),
                    manual, typeKnown, manual ? "manual" : null, manual,
                    manual ? "该引擎无发现能力，名字表为手工维护" : metric.note());
        }
        if (metric.discovery() == MetricDiscoveryMode.MANUAL) {
            return new MetricCatalogView(entry.serviceType(),
                    entry.componentId(), metric.name(), metric.type(), metric.unit(),
                    metric.rateApplicable(), pattern, metric.discovery().name(),
                    true, typeKnown, "manual", true,
                    "该引擎无发现能力，名字表为手工维护");
        }
        DiscoveredMetric matched = discovery.metrics().stream()
                .filter(item -> metric.patternMatches(item.name()))
                .findFirst().orElse(null);
        boolean available = matched != null;
        String note = available ? metric.note()
                : "实例未暴露该指标";
        return new MetricCatalogView(entry.serviceType(),
                entry.componentId(), metric.name(), metric.type(), metric.unit(),
                metric.rateApplicable(), pattern, metric.discovery().name(),
                available, typeKnown, matched == null ? null : matched.source(),
                discovery.manual(), note);
    }

    /** metadata 目录与指标目录共用同一套 target 过滤规则。 */
    private List<MetadataCatalogEntry> metadataOf(Target filter) {
        List<MetadataCatalogEntry> matched = new ArrayList<>();
        for (MetadataCatalogEntry entry : metadataCatalogRegistry.entries()) {
            if (filter != null && !Strings.isBlank(filter.serviceType())
                    && !entry.serviceType().equals(filter.serviceType())) {
                continue;
            }
            if (filter != null && !Strings.isBlank(filter.componentId())
                    && !entry.componentId().equals(filter.componentId())) {
                continue;
            }
            matched.add(entry);
        }
        return matched;
    }

    private TelemetryQueryResponse notImplemented(Target target, QueryMode mode,
                                                    List<Requirement> requirements, long start) {
        return new TelemetryQueryResponse("not_implemented", false, target, mode.wire(),
                emptyBackend(), null,
                new TelemetryQueryResponse.ErrorView(CapabilityCode.DATA_TYPE_NOT_IMPLEMENTED,
                        "该数据类型已声明但尚未实现；不会返回空数组或 0 值", requirements),
                new TelemetryQueryResponse.MetaView(System.currentTimeMillis() - start,
                        false, false, List.of(), null, Instant.now().toString(), null, false, null));
    }

    private TelemetryQueryResponse unavailable(Target target, QueryMode mode,
                                                 BackendSelection selection, List<BackendSkip> skipped,
                                                 List<TelemetryQueryResponse.Attempt> attempts,
                                                 CapabilityCode code, String reason,
                                                 List<Requirement> requirements, long start) {
        TelemetryQueryResponse.BackendView backend = new TelemetryQueryResponse.BackendView(
                null, selection.preferred(), false, skipped, attempts, List.of());
        return new TelemetryQueryResponse("unavailable", code.retryable(), target, mode.wire(),
                backend, null,
                new TelemetryQueryResponse.ErrorView(code, reason, requirements),
                new TelemetryQueryResponse.MetaView(System.currentTimeMillis() - start,
                        false, false, List.of(), null, Instant.now().toString(), null, false, null));
    }

    private TelemetryQueryResponse failed(Target target, QueryMode mode, BackendSelection selection,
                                            List<BackendSkip> skipped,
                                            List<TelemetryQueryResponse.Attempt> attempts,
                                            CapabilityCode code, String reason,
                                            List<Requirement> requirements, Long retryAfterMs,
                                            long start) {
        TelemetryQueryResponse.BackendView backend = new TelemetryQueryResponse.BackendView(
                null, selection.preferred(), false, skipped, attempts, List.of());
        return new TelemetryQueryResponse("failed", code.retryable(), target, mode.wire(),
                backend, null,
                new TelemetryQueryResponse.ErrorView(code, reason, requirements),
                new TelemetryQueryResponse.MetaView(System.currentTimeMillis() - start,
                        false, false, List.of(), retryAfterMs, Instant.now().toString(),
                        null, false, null));
    }

    private TelemetryQueryResponse.BackendView emptyBackend() {
        return new TelemetryQueryResponse.BackendView(null, null, false,
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
        if (!ServiceIdentityCatalog.components().containsKey(target.serviceType())) {
            throw new BusinessException(400, "未知服务类型: " + target.serviceType());
        }
        if (!ServiceIdentityCatalog.supports(target.serviceType(), target.componentId())) {
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
            if (refs == null || Strings.isBlank(refs.get(key))) {
                throw new BusinessException(400, "缺少必填引用: ref." + key);
            }
        }
    }

    private MetricQuerySpec buildSpec(Target target, MetricDefinition definition, QueryMode mode,
                                      MetricQueryRequest request) {
        Instant time = null;
        Instant start = null;
        Instant end = null;
        Duration step = null;
        try {
            if (mode == QueryMode.INSTANT) {
                time = Strings.isBlank(request.getTime()) ? Instant.now() : Instant.parse(request.getTime());
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
    private void validateRate(MetricQueryRequest request, MetricDefinition definition, QueryMode mode) {
        if (!Boolean.TRUE.equals(request.getRate())) {
            return;
        }
        if (mode != QueryMode.RANGE) {
            throw new BusinessException(400, "rate 只允许用于 range 查询，当前 mode=" + mode.wire());
        }
        if (definition.type() == null || definition.type().isBlank()) {
            throw new BusinessException(400,
                    "指标类型未知，禁止 rate=true: " + definition.name()
                            + "（发现结果未命中任何语义模式）");
        }
        if (!"counter".equalsIgnoreCase(definition.type())) {
            throw new BusinessException(400,
                    "rate 只允许用于 counter 指标，当前类型=" + definition.type());
        }
    }

    private void require(String value, String field) {
        if (Strings.isBlank(value)) {
            throw new BusinessException(400, "缺少必填参数: " + field);
        }
    }

}
