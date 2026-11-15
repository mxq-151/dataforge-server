package com.bigdata.modules.telemetry.v1.runtime.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.telemetry.catalog.ServiceComponentCatalog;
import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.Requirement;
import com.bigdata.modules.telemetry.model.RequirementKind;
import com.bigdata.modules.telemetry.v1.catalog.TelemetryCatalogRegistry;
import com.bigdata.modules.telemetry.v1.model.BackendWarning;
import com.bigdata.modules.telemetry.v1.model.Target;
import com.bigdata.modules.telemetry.v1.runtime.config.RuntimeV1Properties;
import com.bigdata.modules.telemetry.v1.runtime.dto.CompareV1Request;
import com.bigdata.modules.telemetry.v1.runtime.dto.ConfigV1Request;
import com.bigdata.modules.telemetry.v1.runtime.dto.DiscoveryQueryV1Request;
import com.bigdata.modules.telemetry.v1.runtime.dto.SnapshotV1Request;
import com.bigdata.modules.telemetry.v1.runtime.engine.RuntimeEngineRegistry;
import com.bigdata.modules.telemetry.v1.runtime.model.CompareData;
import com.bigdata.modules.telemetry.v1.runtime.model.ConfigData;
import com.bigdata.modules.telemetry.v1.runtime.model.DiscoveryData;
import com.bigdata.modules.telemetry.v1.runtime.model.RuntimeComponent;
import com.bigdata.modules.telemetry.v1.runtime.model.RuntimeMetric;
import com.bigdata.modules.telemetry.v1.runtime.model.SnapshotData;
import com.bigdata.modules.telemetry.v1.runtime.spi.RuntimeEngine;
import com.bigdata.modules.telemetry.v1.runtime.spi.RuntimeQuerySpec;
import com.bigdata.modules.telemetry.v1.runtime.support.RuntimeFamilies;
import com.bigdata.modules.telemetry.v1.support.SensitiveDataMasker;
import com.bigdata.modules.telemetry.v1.runtime.support.SnapshotHistory;
import com.bigdata.modules.telemetry.v1.spi.BackendException;
import com.bigdata.modules.telemetry.v1.vo.TelemetryQueryV1Response;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 运行时端点编排：target 校验 → 引擎选择 → 取数 → 四态响应。
 *
 * <p>鉴权只复用 v1 已有的 target 维度校验（{@link ServiceComponentCatalog}），不引入日志模块的
 * 访问控制模型；配置参数一律在返回前脱敏。</p>
 */
@Service
public class RuntimeTelemetryQueryService {

    private static final Set<String> SCOPES = Set.of("sources", "jobs", "components");
    public static final String REASON_SAMPLE_INSUFFICIENT = "SAMPLE_INSUFFICIENT";
    public static final String REASON_JOB_MISMATCH = "JOB_MISMATCH";
    public static final String REASON_TIME_WINDOW_EXCEEDED = "TIME_WINDOW_EXCEEDED";

    private final RuntimeEngineRegistry engineRegistry;
    private final TelemetryCatalogRegistry catalogRegistry;
    private final RuntimeV1Properties properties;
    private final SnapshotHistory history;
    private final SensitiveDataMasker masker;

    public RuntimeTelemetryQueryService(RuntimeEngineRegistry engineRegistry,
                                        TelemetryCatalogRegistry catalogRegistry,
                                        RuntimeV1Properties properties,
                                        SnapshotHistory history,
                                        SensitiveDataMasker masker) {
        this.engineRegistry = engineRegistry;
        this.catalogRegistry = catalogRegistry;
        this.properties = properties;
        this.history = history;
        this.masker = masker;
    }

    // ------------------------------------------------------------------ discovery

    public TelemetryQueryV1Response discovery(DiscoveryQueryV1Request request) {
        long start = System.currentTimeMillis();
        String scope = request.getScope() == null ? "" : request.getScope().trim().toLowerCase(Locale.ROOT);
        if (!SCOPES.contains(scope)) {
            throw new BusinessException(400, "scope 必须是 sources / jobs / components");
        }
        Target target = requireTarget(request.getTarget());
        List<RuntimeEngine> candidates = engineRegistry.enginesFor(target.serviceType());

        if ("sources".equals(scope)) {
            List<String> warnings = new ArrayList<>();
            if (candidates.isEmpty()) {
                warnings.add("未配置 " + target.serviceType() + " 运行时引擎（配置键 "
                        + configKey(target.serviceType()) + "）");
            }
            List<DiscoveryData.SourceView> sources = candidates.stream().map(this::sourceView).toList();
            String mode = normalizeMode(request.getSourceMode(),
                    candidates.isEmpty() ? null : candidates.get(0));
            DiscoveryData data = new DiscoveryData("sources", System.currentTimeMillis(), sources,
                    List.of(), List.of(), warnings);
            return ok(target, mode, candidates.isEmpty() ? null : candidates.get(0), data,
                    warnings, start);
        }

        Resolved resolved = resolve(target, request.getSourceMode());
        if (resolved.error() != null) {
            return resolved.error();
        }
        RuntimeEngine engine = resolved.engine();
        String mode = normalizeMode(request.getSourceMode(), engine);
        RuntimeQuerySpec spec = spec(target, mode, request.getJobId(), List.of(), List.of(),
                request.getComponents(), request.getComponentRole(), false, request.getLimit());
        try {
            List<DiscoveryData.JobView> jobs = "jobs".equals(scope)
                    ? engine.jobs(spec) : List.of();
            List<RuntimeComponent> components = "components".equals(scope)
                    ? engine.components(spec) : List.of();
            List<String> warnings = new ArrayList<>();
            DiscoveryData data = new DiscoveryData(scope, System.currentTimeMillis(), List.of(),
                    jobs, components, warnings);
            return ok(target, mode, engine, data, warnings, start);
        } catch (BackendException e) {
            return error(target, mode, engine, e, start);
        }
    }

    // ------------------------------------------------------------------ snapshot

    public TelemetryQueryV1Response snapshot(SnapshotV1Request request) {
        long start = System.currentTimeMillis();
        Target target = requireTarget(request.getTarget());
        String jobId = requireJobId(request.getJobId());
        Resolved resolved = resolve(target, request.getSourceMode());
        if (resolved.error() != null) {
            return resolved.error();
        }
        RuntimeEngine engine = resolved.engine();
        String mode = normalizeMode(request.getSourceMode(), engine);
        List<String> families = resolveFamilies(request.getFamilies(), target.serviceType());
        RuntimeQuerySpec spec = spec(target, mode, jobId, families, request.getMetrics(),
                request.getComponents(), null,
                Boolean.TRUE.equals(request.getIncludeConfig()), null);
        try {
            SnapshotData snapshot = engine.snapshot(spec);
            SnapshotData masked = new SnapshotData(snapshot.jobId(), snapshot.jobName(),
                    snapshot.state(), snapshot.startTime(), snapshot.endTime(),
                    snapshot.durationMs(), snapshot.capturedAt(), snapshot.components(),
                    snapshot.jobMetrics(), snapshot.checkpoint(), snapshot.unavailableFamilies(),
                    masker.maskSections(snapshot.jobConfig()), snapshot.warnings());
            history.record(historyKey(target, jobId), toEntry(masked));
            return ok(target, mode, engine, masked, masked.warnings(), start);
        } catch (BackendException e) {
            return error(target, mode, engine, e, start);
        }
    }

    // ------------------------------------------------------------------ config

    public TelemetryQueryV1Response config(ConfigV1Request request) {
        long start = System.currentTimeMillis();
        Target target = requireTarget(request.getTarget());
        String jobId = requireJobId(request.getJobId());
        Resolved resolved = resolve(target, request.getSourceMode());
        if (resolved.error() != null) {
            return resolved.error();
        }
        RuntimeEngine engine = resolved.engine();
        String mode = normalizeMode(request.getSourceMode(), engine);
        RuntimeQuerySpec spec = spec(target, mode, jobId, List.of(), List.of(),
                request.getComponents(), null, false, null);
        try {
            ConfigData data = engine.config(spec);
            ConfigData masked = new ConfigData(data.available(), data.reason(), data.jobId(),
                    data.capturedAt(), masker.maskSections(data.sections()), data.components(),
                    data.warnings());
            return ok(target, mode, engine, masked, masked.warnings(), start);
        } catch (BackendException e) {
            return error(target, mode, engine, e, start);
        }
    }

    // ------------------------------------------------------------------ compare

    public TelemetryQueryV1Response compare(CompareV1Request request) {
        long start = System.currentTimeMillis();
        Target target = requireTarget(request.getTarget());
        String jobId = requireJobId(request.getJobId());
        Resolved resolved = resolve(target, request.getSourceMode());
        if (resolved.error() != null) {
            return resolved.error();
        }
        RuntimeEngine engine = resolved.engine();
        String mode = normalizeMode(request.getSourceMode(), engine);
        long windowSeconds = request.getWindowSeconds() == null
                ? Math.max(properties.getDefaultWindowSeconds(), 1)
                : Math.max(request.getWindowSeconds(), 1);
        List<String> families = resolveFamilies(List.of(), target.serviceType());
        RuntimeQuerySpec spec = spec(target, mode, jobId, families, List.of(), List.of(),
                null, false, null);
        try {
            SnapshotData current = engine.snapshot(spec);
            String key = historyKey(target, jobId);
            Optional<SnapshotHistory.Entry> latest = history.latest(key);
            String currentPrefix = targetPrefix(target);
            List<String> warnings = new ArrayList<>();
            CompareData data;
            if (latest.isEmpty()) {
                boolean otherJob = history.entriesWithPrefix(currentPrefix).stream()
                        .anyMatch(entry -> !entry.jobId().equals(jobId));
                data = new CompareData(false,
                        otherJob ? REASON_JOB_MISMATCH : REASON_SAMPLE_INSUFFICIENT,
                        windowSeconds, null, current.capturedAt(), List.of(), warnings);
            } else {
                SnapshotHistory.Entry previous = latest.get();
                if (!previous.jobId().equals(jobId)) {
                    data = new CompareData(false, REASON_JOB_MISMATCH, windowSeconds,
                            previous.capturedAt(), current.capturedAt(), List.of(), warnings);
                } else if (current.capturedAt() - previous.capturedAt() > windowSeconds * 1000L) {
                    data = new CompareData(false, REASON_TIME_WINDOW_EXCEEDED, windowSeconds,
                            previous.capturedAt(), current.capturedAt(), List.of(), warnings);
                } else {
                    data = compareData(previous, current, windowSeconds, warnings);
                }
            }
            history.record(key, toEntry(current));
            return ok(target, mode, engine, data, warnings, start);
        } catch (BackendException e) {
            return error(target, mode, engine, e, start);
        }
    }

    private CompareData compareData(SnapshotHistory.Entry previous, SnapshotData current,
                                    long windowSeconds, List<String> warnings) {
        Map<String, SnapshotHistory.Point> now = points(current);
        List<CompareData.Row> rows = new ArrayList<>();
        double seconds = Math.max((current.capturedAt() - previous.capturedAt()) / 1000.0, 0.001);
        int missing = 0;
        for (Map.Entry<String, SnapshotHistory.Point> entry : previous.points().entrySet()) {
            SnapshotHistory.Point before = entry.getValue();
            SnapshotHistory.Point after = now.get(entry.getKey());
            if (after == null) {
                missing++;
                continue;
            }
            double delta = after.value() - before.value();
            rows.add(new CompareData.Row(after.component(), after.componentType(), after.family(),
                    after.name(), after.unit(), before.value(), after.value(), delta,
                    delta / seconds));
        }
        for (String key : now.keySet()) {
            if (!previous.points().containsKey(key)) {
                missing++;
            }
        }
        rows.sort((left, right) -> {
            int byComponent = left.component().compareTo(right.component());
            return byComponent != 0 ? byComponent : left.name().compareTo(right.name());
        });
        if (missing > 0) {
            warnings.add("有 " + missing + " 条指标只出现在一侧快照，未参与对比");
        }
        return new CompareData(true, null, windowSeconds, previous.capturedAt(),
                current.capturedAt(), rows, warnings);
    }

    private Map<String, SnapshotHistory.Point> points(SnapshotData snapshot) {
        Map<String, SnapshotHistory.Point> points = new LinkedHashMap<>();
        for (RuntimeMetric metric : snapshot.jobMetrics()) {
            if (metric.hasValue()) {
                points.put(pointKey("job", metric.family(), metric.name()),
                        new SnapshotHistory.Point("job", "job", metric.family(), metric.name(),
                                metric.unit(), metric.value()));
            }
        }
        for (RuntimeComponent component : snapshot.components()) {
            for (RuntimeMetric metric : component.metrics()) {
                if (metric.hasValue()) {
                    points.put(pointKey(component.id(), metric.family(), metric.name()),
                            new SnapshotHistory.Point(component.id(), component.type(),
                                    metric.family(), metric.name(), metric.unit(), metric.value()));
                }
            }
        }
        return points;
    }

    private SnapshotHistory.Entry toEntry(SnapshotData snapshot) {
        return new SnapshotHistory.Entry(snapshot.jobId(), snapshot.capturedAt(),
                points(snapshot));
    }

    // ------------------------------------------------------------------ 引擎与校验

    private record Resolved(RuntimeEngine engine, TelemetryQueryV1Response error) {
    }

    private Resolved resolve(Target target, String requestedMode) {
        Optional<RuntimeEngine> found = engineRegistry.resolve(target.serviceType(),
                requestedMode == null ? null : requestedMode.trim());
        if (found.isEmpty()) {
            return new Resolved(null, unavailable(target, normalizeMode(requestedMode, null),
                    CapabilityCode.NOT_CONFIGURED,
                    "未配置 " + target.serviceType() + " 运行时引擎（配置键 "
                            + configKey(target.serviceType()) + "）",
                    List.of(new Requirement(RequirementKind.CONFIG,
                            configKey(target.serviceType()), "配置引擎 REST 地址"))));
        }
        RuntimeEngine engine = found.get();
        if (!engine.configured()) {
            return new Resolved(null, unavailable(target, normalizeMode(requestedMode, engine),
                    CapabilityCode.NOT_CONFIGURED, engine.unconfiguredReason(),
                    List.of(new Requirement(RequirementKind.CONFIG,
                            configKey(target.serviceType()), "配置引擎 REST 地址与认证"))));
        }
        return new Resolved(engine, null);
    }

    private DiscoveryData.SourceView sourceView(RuntimeEngine engine) {
        return new DiscoveryData.SourceView(engine.backendId(), engine.backendType().wire(),
                engine.serviceType(), engine.mode(), engine.configured(),
                RuntimeFamilies.supportedFor(engine.serviceType(), catalogRegistry.catalog()),
                RuntimeFamilies.unavailableFor(engine.serviceType()));
    }

    private List<String> resolveFamilies(List<String> requested, String serviceType) {
        if (requested == null || requested.isEmpty()) {
            return RuntimeFamilies.defaultsFor(serviceType, catalogRegistry.catalog());
        }
        Set<String> families = new LinkedHashSet<>();
        for (String value : requested) {
            String family = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (!RuntimeFamilies.isKnown(family)) {
                throw new BusinessException(400, "未知指标族: " + value
                        + "（可选 checkpoint / throughput / jvm_gc / backpressure / shuffle / failures）");
            }
            families.add(family);
        }
        return new ArrayList<>(families);
    }

    private RuntimeQuerySpec spec(Target target, String mode, String jobId, List<String> families,
                                  List<String> metrics, List<String> components, String role,
                                  boolean includeConfig, Integer limit) {
        int effectiveLimit = limit == null || limit <= 0
                ? Math.max(properties.getMaxComponents(), 1) : limit;
        return new RuntimeQuerySpec(target, mode, jobId, families, metrics, components, role,
                includeConfig, effectiveLimit, properties.getCheckpointHistory());
    }

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

    private String requireJobId(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            throw new BusinessException(400, "缺少必填参数: jobId");
        }
        return jobId.trim();
    }

    private void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(400, "缺少必填参数: " + field);
        }
    }

    private String normalizeMode(String requested, RuntimeEngine engine) {
        if (requested == null || requested.isBlank()) {
            return engine == null ? "live" : engine.mode();
        }
        String mode = requested.trim().toLowerCase(Locale.ROOT);
        if (!"live".equals(mode) && !"history".equals(mode)) {
            throw new BusinessException(400, "sourceMode 必须是 live / history");
        }
        return mode;
    }

    private String configKey(String serviceType) {
        return "spark".equals(serviceType)
                ? "telemetry.backends.spark-rest[]" : "telemetry.backends.flink-rest[]";
    }

    // ------------------------------------------------------------------ 响应

    private TelemetryQueryV1Response ok(Target target, String mode, RuntimeEngine engine, Object data,
                                        List<String> warnings, long start) {
        long elapsed = System.currentTimeMillis() - start;
        TelemetryQueryV1Response.BackendView backend = new TelemetryQueryV1Response.BackendView(
                engine == null ? null
                        : new TelemetryQueryV1Response.Selected(engine.backendId(),
                        engine.backendType().wire(), mode, false),
                engine == null ? null : engine.backendId(), false, List.of(),
                engine == null ? List.of()
                        : List.of(new TelemetryQueryV1Response.Attempt(engine.backendId(),
                        engine.backendType().wire(), CapabilityCode.OK, elapsed, false)),
                List.of());
        return new TelemetryQueryV1Response("ok", false, target, mode, backend, data, null,
                meta(elapsed, warnings, null));
    }

    private TelemetryQueryV1Response unavailable(Target target, String mode, CapabilityCode code,
                                                 String reason, List<Requirement> requirements) {
        return new TelemetryQueryV1Response("unavailable", code.retryable(), target, mode,
                new TelemetryQueryV1Response.BackendView(null, null, false, List.of(), List.of(),
                        List.of()), null,
                new TelemetryQueryV1Response.ErrorView(code, reason, requirements),
                meta(0, List.of(), null));
    }

    private TelemetryQueryV1Response error(Target target, String mode, RuntimeEngine engine,
                                           BackendException e, long start) {
        long elapsed = System.currentTimeMillis() - start;
        TelemetryQueryV1Response.BackendView backend = new TelemetryQueryV1Response.BackendView(
                null, engine.backendId(), false, List.of(),
                List.of(new TelemetryQueryV1Response.Attempt(engine.backendId(),
                        engine.backendType().wire(), e.getCode(), elapsed, e.retryable())),
                List.of());
        return new TelemetryQueryV1Response(
                e.availabilityFailure() ? "unavailable" : "failed", e.retryable(), target, mode,
                backend, null,
                new TelemetryQueryV1Response.ErrorView(e.getCode(), e.getMessage(),
                        e.getRequirements()),
                meta(elapsed, List.of(), e.getRetryAfterMs()));
    }

    private TelemetryQueryV1Response.MetaView meta(long elapsed, List<String> warnings,
                                                   Long retryAfterMs) {
        List<BackendWarning> converted = new ArrayList<>();
        for (String warning : warnings) {
            converted.add(BackendWarning.of("RUNTIME_WARNING", warning));
        }
        return new TelemetryQueryV1Response.MetaView(elapsed, false, false, converted,
                retryAfterMs, Instant.now().toString(), null, false, null);
    }

    // ------------------------------------------------------------------ compare key

    private String targetPrefix(Target target) {
        return target.serviceType() + "|" + target.componentId() + "|" + target.hostId() + "|";
    }

    private String historyKey(Target target, String jobId) {
        return targetPrefix(target) + jobId;
    }

    private static String pointKey(String component, String family, String name) {
        return component + "|" + family + "|" + name;
    }
}
