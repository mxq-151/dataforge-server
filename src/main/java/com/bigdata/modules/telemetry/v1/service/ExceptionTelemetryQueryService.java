package com.bigdata.modules.telemetry.v1.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.telemetry.catalog.ServiceComponentCatalog;
import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.Requirement;
import com.bigdata.modules.telemetry.model.RequirementKind;
import com.bigdata.modules.telemetry.shared.LogCircuitBreaker;
import com.bigdata.modules.telemetry.v1.catalog.TelemetryCatalogRegistry;
import com.bigdata.modules.telemetry.v1.dto.ExceptionQueryV1Request;
import com.bigdata.modules.telemetry.v1.model.BackendWarning;
import com.bigdata.modules.telemetry.v1.model.ComponentCatalogEntry;
import com.bigdata.modules.telemetry.v1.model.ComponentCatalogEntry.EngineEvidenceDefinition;
import com.bigdata.modules.telemetry.v1.model.Target;
import com.bigdata.modules.telemetry.v1.routing.BackendCandidate;
import com.bigdata.modules.telemetry.v1.routing.BackendRouter;
import com.bigdata.modules.telemetry.v1.routing.BackendSelection;
import com.bigdata.modules.telemetry.v1.routing.BackendSkip;
import com.bigdata.modules.telemetry.v1.routing.RoutingProperties;
import com.bigdata.modules.telemetry.v1.spi.BackendCapability;
import com.bigdata.modules.telemetry.v1.spi.BackendException;
import com.bigdata.modules.telemetry.v1.spi.ExceptionQueryResult;
import com.bigdata.modules.telemetry.v1.spi.ExceptionQuerySpec;
import com.bigdata.modules.telemetry.v1.spi.ExceptionSummaryBackend;
import com.bigdata.modules.telemetry.v1.spi.MetricBackend;
import com.bigdata.modules.telemetry.v1.spi.TelemetryBackendRegistry;
import com.bigdata.modules.telemetry.v1.vo.TelemetryQueryV1Response;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 引擎结构化异常摘要查询编排（当前只有 Flink）。
 *
 * <p>异常不进入日志接口：结构化字段（taskName / exceptionName / rootCause / timestamp /
 * 并发异常 / 截断标记）必须保留。成功但没有异常返回 {@code ok + resultState=empty}，
 * 与 404、上游失败、配置缺失严格区分；上限触达返回 {@code truncated=true} +
 * {@code RESULT_TRUNCATED}。</p>
 *
 * <p>Flink 2.2 只有 {@code GET /jobs/{jobId}/exceptions} 列表接口，没有单条异常子资源，
 * 因此这里不提供按子资源取详情的调用路径。</p>
 */
@Service
public class ExceptionTelemetryQueryService {

    private static final String EVIDENCE = "exception_summary";
    private static final String CIRCUIT_PREFIX = "telemetry:v1:exception:";

    private final TelemetryCatalogRegistry catalogRegistry;
    private final BackendRouter router;
    private final TelemetryBackendRegistry backendRegistry;
    private final LogCircuitBreaker circuitBreaker;

    public ExceptionTelemetryQueryService(TelemetryCatalogRegistry catalogRegistry,
                                          BackendRouter router,
                                          TelemetryBackendRegistry backendRegistry,
                                          LogCircuitBreaker circuitBreaker) {
        this.catalogRegistry = catalogRegistry;
        this.router = router;
        this.backendRegistry = backendRegistry;
        this.circuitBreaker = circuitBreaker;
    }

    public TelemetryQueryV1Response query(ExceptionQueryV1Request request) {
        long start = System.currentTimeMillis();
        Target target = requireTarget(request.getTarget());
        ComponentCatalogEntry entry = requireEntry(target);
        EngineEvidenceDefinition evidence = entry.engineEvidence().stream()
                .filter(item -> EVIDENCE.equals(item.name()))
                .findFirst()
                .orElse(null);
        if (evidence == null) {
            return notImplemented(target, List.of(new Requirement(RequirementKind.CONFIG,
                    "catalog.engineEvidence[" + EVIDENCE + "]",
                    "该组件未声明 exception_summary；Flink 异常摘要在 flink.jobmanager / "
                            + "flink.taskmanager 下声明")), start);
        }
        Map<String, String> refs = request.getRef() == null ? Map.of() : request.getRef();
        String jobId = refs.get("jobId");
        if (isBlank(jobId)) {
            throw new BusinessException(400, "缺少必填引用: ref.jobId");
        }
        Duration timeout = request.getTimeoutMs() == null ? null
                : Duration.ofMillis(Math.max(request.getTimeoutMs(), 1));
        ExceptionQuerySpec spec = new ExceptionQuerySpec(target, jobId,
                request.getMaxExceptions(), request.getMaxConcurrentExceptions(),
                request.getIncludeStackTraces(), timeout);
        BackendSelection selection = router.selectEvidence(target, EVIDENCE,
                evidence.supportedBackends());
        return execute(target, spec, selection,
                Boolean.TRUE.equals(request.getIncludeBackendCandidates()), start);
    }

    // ------------------------------------------------------------------ 编排

    private TelemetryQueryV1Response execute(Target target, ExceptionQuerySpec spec,
                                             BackendSelection selection,
                                             boolean includeCandidates, long start) {
        List<BackendSkip> skipped = new ArrayList<>(selection.skipped());
        List<TelemetryQueryV1Response.Attempt> attempts = new ArrayList<>();
        List<Requirement> requirements = new ArrayList<>();
        BackendException lastFailure = null;
        RoutingProperties.Circuit circuit = router.circuit();

        for (BackendCandidate candidate : selection.candidates()) {
            MetricBackend backend = backendRegistry.metric(candidate.backendId());
            if (backend == null) {
                skipped.add(new BackendSkip(candidate.backendId(), candidate.backendType().wire(),
                        CapabilityCode.NOT_CONFIGURED, "后端实例未注册"));
                continue;
            }
            if (!(backend instanceof ExceptionSummaryBackend summary)) {
                skipped.add(new BackendSkip(candidate.backendId(), candidate.backendType().wire(),
                        CapabilityCode.DATA_TYPE_NOT_IMPLEMENTED, "后端不提供结构化异常摘要"));
                continue;
            }
            BackendCapability capability = summary.evaluate(spec);
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
                ExceptionQueryResult result = summary.fetch(spec);
                circuitBreaker.recordSuccess(circuitKey);
                attempts.add(new TelemetryQueryV1Response.Attempt(candidate.backendId(),
                        candidate.backendType().wire(), CapabilityCode.OK,
                        System.currentTimeMillis() - attemptStart, false));
                return ok(target, spec, selection, candidate, result, skipped, attempts,
                        includeCandidates, start);
            } catch (BackendException e) {
                circuitBreaker.recordFailure(circuitKey,
                        circuit.getFailureThreshold(), circuit.getOpenMs());
                attempts.add(new TelemetryQueryV1Response.Attempt(candidate.backendId(),
                        candidate.backendType().wire(), e.getCode(),
                        System.currentTimeMillis() - attemptStart, e.retryable()));
                requirements.addAll(e.getRequirements());
                lastFailure = e;
                if (!e.availabilityFailure()) {
                    return failed(target, selection, skipped, attempts, e.getCode(),
                            e.getMessage(), requirements, e.getRetryAfterMs(), start);
                }
            }
        }

        if (attempts.isEmpty()) {
            boolean onlyUnimplemented = !skipped.isEmpty()
                    && skipped.stream().allMatch(skip ->
                    skip.code() == CapabilityCode.DATA_TYPE_NOT_IMPLEMENTED);
            if (onlyUnimplemented) {
                return notImplemented(target, requirements, start);
            }
            CapabilityCode code = skipped.isEmpty()
                    ? CapabilityCode.BACKEND_UNAVAILABLE : skipped.get(0).code();
            requirements.add(defaultRequirement());
            return unavailable(target, selection, skipped, attempts, code,
                    skipped.isEmpty() ? "没有可用的异常摘要后端" : skipped.get(0).reason(),
                    requirements, start);
        }
        assert lastFailure != null;
        requirements.add(defaultRequirement());
        return unavailable(target, selection, skipped, attempts, lastFailure.getCode(),
                lastFailure.getMessage(), requirements, start);
    }

    // ------------------------------------------------------------------ 响应构造

    private TelemetryQueryV1Response ok(Target target, ExceptionQuerySpec spec,
                                        BackendSelection selection, BackendCandidate candidate,
                                        ExceptionQueryResult result, List<BackendSkip> skipped,
                                        List<TelemetryQueryV1Response.Attempt> attempts,
                                        boolean includeCandidates, long start) {
        List<BackendWarning> warnings = new ArrayList<>(result.warnings());
        if (result.isEmpty()) {
            warnings.add(BackendWarning.of("NO_EXCEPTION_HISTORY",
                    "查询成功：作业 " + spec.jobId() + " 在服务端异常历史中没有记录"
                            + "（可能从未失败，或历史已被服务端清理）"));
        }
        String resultState = result.isEmpty() ? "empty"
                : (result.truncated() ? "partial" : "data");
        CapabilityCode truncatedCode = result.truncated()
                ? CapabilityCode.RESULT_TRUNCATED : null;
        List<TelemetryQueryV1Response.ExceptionEntryView> entries = new ArrayList<>();
        for (ExceptionQueryResult.ExceptionRecord record : result.exceptions()) {
            entries.add(entry(record));
        }
        TelemetryQueryV1Response.ExceptionData data = new TelemetryQueryV1Response.ExceptionData(
                spec.jobId(), resultState, result.isEmpty(), entries, entries.size(),
                result.concurrentExceptionCount(), result.truncated(), truncatedCode,
                result.serverTruncated(), result.requestedMaxExceptions(), warnings);
        TelemetryQueryV1Response.BackendView backend = new TelemetryQueryV1Response.BackendView(
                new TelemetryQueryV1Response.Selected(candidate.backendId(),
                        candidate.backendType().wire(), "data", false),
                selection.preferred(), false, skipped, attempts,
                includeCandidates ? selection.candidates() : List.of());
        TelemetryQueryV1Response.MetaView meta = new TelemetryQueryV1Response.MetaView(
                System.currentTimeMillis() - start, result.truncated(), false, warnings,
                null, Instant.now().toString(), null, result.truncated(), null);
        return new TelemetryQueryV1Response("ok", false, target, "data", backend, data, null, meta);
    }

    private static TelemetryQueryV1Response.ExceptionEntryView entry(
            ExceptionQueryResult.ExceptionRecord record) {
        List<TelemetryQueryV1Response.ConcurrentExceptionView> concurrent = new ArrayList<>();
        for (ExceptionQueryResult.ConcurrentExceptionRecord item : record.concurrentExceptions()) {
            concurrent.add(new TelemetryQueryV1Response.ConcurrentExceptionView(
                    item.exceptionName(), item.rootCause(), item.taskName(), item.timestamp(),
                    item.taskManagerId(), item.endpoint(), item.stackTrace(),
                    item.stackTraceTruncated(), item.stackTraceChars()));
        }
        return new TelemetryQueryV1Response.ExceptionEntryView(record.exceptionName(),
                record.rootCause(), record.taskName(), record.timestamp(),
                record.serverTruncated(), record.failureLabels(), record.taskManagerId(),
                record.endpoint(), record.stackTrace(), record.stackTraceTruncated(),
                record.stackTraceChars(), concurrent);
    }

    private TelemetryQueryV1Response notImplemented(Target target, List<Requirement> requirements,
                                                    long start) {
        return new TelemetryQueryV1Response("not_implemented", false, target, "data",
                emptyBackend(), null,
                new TelemetryQueryV1Response.ErrorView(CapabilityCode.DATA_TYPE_NOT_IMPLEMENTED,
                        "该组件的异常摘要已声明但尚未实现；不会返回空数组", requirements),
                new TelemetryQueryV1Response.MetaView(System.currentTimeMillis() - start,
                        false, false, List.of(), null, Instant.now().toString(),
                        null, false, null));
    }

    private TelemetryQueryV1Response unavailable(Target target, BackendSelection selection,
                                                 List<BackendSkip> skipped,
                                                 List<TelemetryQueryV1Response.Attempt> attempts,
                                                 CapabilityCode code, String reason,
                                                 List<Requirement> requirements, long start) {
        TelemetryQueryV1Response.BackendView backend = new TelemetryQueryV1Response.BackendView(
                null, selection.preferred(), false, skipped, attempts, List.of());
        return new TelemetryQueryV1Response("unavailable", code.retryable(), target, "data",
                backend, null,
                new TelemetryQueryV1Response.ErrorView(code, reason, requirements),
                new TelemetryQueryV1Response.MetaView(System.currentTimeMillis() - start,
                        false, false, List.of(), null, Instant.now().toString(),
                        null, false, null));
    }

    private TelemetryQueryV1Response failed(Target target, BackendSelection selection,
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

    private static Requirement defaultRequirement() {
        return new Requirement(RequirementKind.CONFIG, "telemetry.backends.flink-rest[]",
                "配置 Flink JobManager REST 地址后可按 ref.jobId 查询异常摘要");
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

    private static void require(String value, String field) {
        if (isBlank(value)) {
            throw new BusinessException(400, "缺少必填参数: " + field);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
