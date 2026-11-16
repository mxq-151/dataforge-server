package com.bigdata.modules.telemetry.service.logdata;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.telemetry.util.CircuitBreaker;
import com.bigdata.modules.telemetry.service.catalog.ServiceIdentityCatalog;
import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.Requirement;
import com.bigdata.modules.telemetry.common.model.RequirementKind;
import com.bigdata.modules.telemetry.service.catalog.TelemetryCatalogRegistry;
import com.bigdata.modules.telemetry.dto.LogQueryRequest;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataCatalogEntry;
import com.bigdata.modules.telemetry.service.metadata.registry.MetadataCatalogRegistry;
import com.bigdata.modules.telemetry.common.model.BackendWarning;
import com.bigdata.modules.telemetry.vo.ComponentCatalogEntry;
import com.bigdata.modules.telemetry.service.logdata.model.LogDataType;
import com.bigdata.modules.telemetry.service.logdata.model.LogFilter;
import com.bigdata.modules.telemetry.service.logdata.model.LogStreamDefinition;
import com.bigdata.modules.telemetry.common.model.Target;
import com.bigdata.modules.telemetry.service.routing.model.BackendCandidate;
import com.bigdata.modules.telemetry.service.routing.BackendRouter;
import com.bigdata.modules.telemetry.service.routing.model.BackendSelection;
import com.bigdata.modules.telemetry.service.routing.model.BackendSkip;
import com.bigdata.modules.telemetry.config.RoutingProperties;
import com.bigdata.modules.telemetry.common.model.BackendCapability;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.bigdata.modules.telemetry.service.logdata.spi.LogBackend;
import com.bigdata.modules.telemetry.service.logdata.model.LogQueryResult;
import com.bigdata.modules.telemetry.service.logdata.model.LogQuerySpec;
import com.bigdata.modules.telemetry.config.TelemetryBackendRegistry;
import com.bigdata.modules.telemetry.vo.TelemetryQueryResponse;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.bigdata.modules.telemetry.vo.CatalogData;
import com.bigdata.modules.telemetry.vo.LogData;
import com.bigdata.modules.telemetry.util.Strings;

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
    private final MetadataCatalogRegistry metadataCatalogRegistry;
    private final BackendRouter router;
    private final TelemetryBackendRegistry backendRegistry;
    private final CircuitBreaker circuitBreaker;

    public LogTelemetryQueryService(TelemetryCatalogRegistry catalogRegistry,
                                    MetadataCatalogRegistry metadataCatalogRegistry,
                                    BackendRouter router,
                                    TelemetryBackendRegistry backendRegistry,
                                    CircuitBreaker circuitBreaker) {
        this.catalogRegistry = catalogRegistry;
        this.metadataCatalogRegistry = metadataCatalogRegistry;
        this.router = router;
        this.backendRegistry = backendRegistry;
        this.circuitBreaker = circuitBreaker;
    }

    public TelemetryQueryResponse query(LogQueryRequest request) {
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

    private TelemetryQueryResponse execute(Target target, LogStreamDefinition definition,
                                             LogQuerySpec spec, BackendSelection selection,
                                             boolean includeCandidates, long start) {
        String stream = definition.stream();
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
                return ok(target, definition, selection, candidate, result, fallback,
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
                if (e.getCode() == CapabilityCode.NO_LOG_CONTENT
                        || e.getCode() == CapabilityCode.LOG_SOURCE_UNAVAILABLE) {
                    return unavailable(target, selection, skipped, attempts, e.getCode(),
                            e.getMessage(), requirements, start);
                }
                if (!e.availabilityFailure()) {
                    return failed(target, selection, skipped, attempts, e.getCode(),
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
            return unavailable(target, selection, skipped, attempts, code,
                    skipped.isEmpty() ? "没有可用的日志后端" : skipped.get(0).reason(),
                    merged, start);
        }
        assert lastFailure != null;
        return failed(target, selection, skipped, attempts, lastFailure.getCode(),
                lastFailure.getMessage(), requirements, lastFailure.getRetryAfterMs(), start);
    }

    // ------------------------------------------------------------------ 响应构造

    private TelemetryQueryResponse ok(Target target, LogStreamDefinition definition,
                                        BackendSelection selection,
                                        BackendCandidate candidate, LogQueryResult result,
                                        boolean fallback, List<BackendSkip> skipped,
                                        List<TelemetryQueryResponse.Attempt> attempts,
                                        List<BackendWarning> warnings, boolean includeCandidates,
                                        long start) {
        String stream = definition.stream();
        String resultState = result.isEmpty() ? "empty" : (result.truncated() ? "partial" : "data");
        LogData data = new LogData(
                definition.dataType().wire(), stream, resultState,
                result.entries(), result.entries().size());
        TelemetryQueryResponse.ScanView scan = result.scan() == null ? null
                : new TelemetryQueryResponse.ScanView(result.scan().files(),
                result.scan().bytes(), result.scan().lines(), result.scan().matched());
        TelemetryQueryResponse.BackendView backend = new TelemetryQueryResponse.BackendView(
                new TelemetryQueryResponse.Selected(candidate.backendId(),
                        candidate.backendType().wire(), "data", fallback),
                selection.preferred(), fallback, skipped, attempts,
                includeCandidates ? selection.candidates() : List.of());
        TelemetryQueryResponse.MetaView meta = new TelemetryQueryResponse.MetaView(
                System.currentTimeMillis() - start, result.truncated(), fallback, warnings,
                null, Instant.now().toString(), result.nextCursor(), result.truncated(), scan);
        return new TelemetryQueryResponse("ok", false, target, "data", backend, data, null, meta);
    }

    private TelemetryQueryResponse catalog(LogQueryRequest request, long start) {
        Target filter = request.getTarget();
        List<ComponentCatalogEntry> components = catalogRegistry.catalog().entries().stream()
                .filter(entry -> filter == null || Strings.isBlank(filter.serviceType())
                        || entry.serviceType().equals(filter.serviceType()))
                .filter(entry -> filter == null || Strings.isBlank(filter.componentId())
                        || entry.componentId().equals(filter.componentId()))
                .toList();
        CatalogData data = new CatalogData(
                catalogRegistry.catalog().version(), components, metadataOf(filter));

        return new TelemetryQueryResponse("ok", false, filter, "catalog",
                emptyBackend(), data, null,
                new TelemetryQueryResponse.MetaView(System.currentTimeMillis() - start,
                        false, false, List.of(), null, Instant.now().toString(),
                        null, false, null));
    }

    /** metadata 目录与日志目录共用同一套 target 过滤规则。 */
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

    private TelemetryQueryResponse notImplemented(Target target, List<Requirement> requirements,
                                                    long start) {
        return new TelemetryQueryResponse("not_implemented", false, target, "data",
                emptyBackend(), null,
                new TelemetryQueryResponse.ErrorView(CapabilityCode.DATA_TYPE_NOT_IMPLEMENTED,
                        "该日志流已声明但尚未实现；不会返回空数组", requirements),
                new TelemetryQueryResponse.MetaView(System.currentTimeMillis() - start,
                        false, false, List.of(), null, Instant.now().toString(), null, false, null));
    }

    /** 分类 × stream 不支持的组合：显式返回 code，不静默返回空。 */
    private TelemetryQueryResponse unsupported(Target target, String reason, CapabilityCode code,
                                                 List<Requirement> requirements, long start) {
        return new TelemetryQueryResponse("unavailable", false, target, "data",
                emptyBackend(), null,
                new TelemetryQueryResponse.ErrorView(code, reason, requirements),
                new TelemetryQueryResponse.MetaView(System.currentTimeMillis() - start,
                        false, false, List.of(), null, Instant.now().toString(), null, false, null));
    }

    private TelemetryQueryResponse unavailable(Target target,
                                                 BackendSelection selection, List<BackendSkip> skipped,
                                                 List<TelemetryQueryResponse.Attempt> attempts,
                                                 CapabilityCode code, String reason,
                                                 List<Requirement> requirements, long start) {
        TelemetryQueryResponse.BackendView backend = new TelemetryQueryResponse.BackendView(
                null, selection.preferred(), false, skipped, attempts, List.of());
        return new TelemetryQueryResponse("unavailable", code.retryable(), target, "data",
                backend, null,
                new TelemetryQueryResponse.ErrorView(code, reason, requirements),
                new TelemetryQueryResponse.MetaView(System.currentTimeMillis() - start,
                        false, false, List.of(), null, Instant.now().toString(), null, false, null));
    }

    private TelemetryQueryResponse failed(Target target, BackendSelection selection,
                                            List<BackendSkip> skipped,
                                            List<TelemetryQueryResponse.Attempt> attempts,
                                            CapabilityCode code, String reason,
                                            List<Requirement> requirements, Long retryAfterMs,
                                            long start) {
        TelemetryQueryResponse.BackendView backend = new TelemetryQueryResponse.BackendView(
                null, selection.preferred(), false, skipped, attempts, List.of());
        return new TelemetryQueryResponse("failed", code.retryable(), target, "data",
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

    private Target requireTarget(Target target) {
        if (target == null) {
            throw new BusinessException(400, "缺少必填参数: target");
        }
        require(target.serviceType(), "target.serviceType");
        require(target.componentId(), "target.componentId");
        require(target.hostId(), "target.hostId");
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

    private LogDataType requireDataType(String value) {
        if (Strings.isBlank(value)) {
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
        boolean present = anyRefs.stream().anyMatch(key -> refs != null && !Strings.isBlank(refs.get(key)));
        if (present) {
            return;
        }
        String names = anyRefs.stream().map(key -> "ref." + key).collect(Collectors.joining(" / "));
        String hint = dataType == LogDataType.TASK ? "；两者都给时以 taskId 为准" : "";
        throw new BusinessException(400, "缺少必填引用: " + names + "（至少提供一个" + hint + "）");
    }

    private LogQuerySpec buildSpec(Target target, LogDataType dataType, String stream,
                                   LogQueryRequest request) {
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
        if (Strings.isBlank(value)) {
            throw new BusinessException(400, "缺少必填参数: " + field);
        }
    }

}
