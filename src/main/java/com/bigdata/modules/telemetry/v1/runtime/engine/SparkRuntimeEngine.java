package com.bigdata.modules.telemetry.v1.runtime.engine;

import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.v1.backend.engine.EngineRestClient;
import com.bigdata.modules.telemetry.v1.catalog.TelemetryCatalogRegistry;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties.SparkRestConfig;
import com.bigdata.modules.telemetry.v1.model.BackendType;
import com.bigdata.modules.telemetry.v1.model.MetricDefinition;
import com.bigdata.modules.telemetry.v1.runtime.config.RuntimeV1Properties;
import com.bigdata.modules.telemetry.v1.runtime.model.ConfigData;
import com.bigdata.modules.telemetry.v1.runtime.model.DiscoveryData;
import com.bigdata.modules.telemetry.v1.runtime.model.RuntimeComponent;
import com.bigdata.modules.telemetry.v1.runtime.model.RuntimeMetric;
import com.bigdata.modules.telemetry.v1.runtime.model.SnapshotData;
import com.bigdata.modules.telemetry.v1.runtime.spi.RuntimeEngine;
import com.bigdata.modules.telemetry.v1.runtime.spi.RuntimeQuerySpec;
import com.bigdata.modules.telemetry.v1.runtime.support.MetricValueNormalizer;
import com.bigdata.modules.telemetry.v1.runtime.support.RuntimeFamilies;
import com.bigdata.modules.telemetry.v1.spi.BackendException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spark History Server / live UI REST 的运行时引擎。
 *
 * <p>history 以 {@code /allexecutors} 为主，live 以 {@code /executors} 为主，另一个端点作为回退。
 * Spark REST 不提供 checkpoint 与背压指标，二者只出现在 {@code unavailableFamilies} 与
 * checkpoint.reason 中，不返回 0。</p>
 */
public class SparkRuntimeEngine implements RuntimeEngine {

    private static final String EXECUTOR = "executor";
    private static final String STAGE = "stage";
    private static final String JOB = "job";

    private final String backendId;
    private final SparkRestConfig config;
    private final String configurationError;
    private final TelemetryCatalogRegistry catalogRegistry;
    private final RuntimeV1Properties properties;
    private final EngineRestClient client;

    public SparkRuntimeEngine(String backendId, SparkRestConfig config, String authorizationHeader,
                              String configurationError, ObjectMapper objectMapper,
                              TelemetryCatalogRegistry catalogRegistry,
                              RuntimeV1Properties properties) {
        this.backendId = backendId;
        this.config = config;
        this.configurationError = configurationError;
        this.catalogRegistry = catalogRegistry;
        this.properties = properties;
        this.client = new EngineRestClient(config.getBaseUrl(), authorizationHeader,
                Duration.ofMillis(Math.max(config.getLimits().getTimeoutMs(), 1000)),
                config.getLimits().getMaxResponseBytes(), objectMapper);
    }

    @Override
    public String backendId() {
        return backendId;
    }

    @Override
    public BackendType backendType() {
        return BackendType.SPARK_REST;
    }

    @Override
    public String serviceType() {
        return "spark";
    }

    @Override
    public String mode() {
        return "live".equalsIgnoreCase(config.getMode()) ? "live" : "history";
    }

    @Override
    public boolean configured() {
        return configurationError == null
                && config.getBaseUrl() != null && !config.getBaseUrl().isBlank();
    }

    @Override
    public String unconfiguredReason() {
        if (configurationError != null) {
            return configurationError;
        }
        return "未配置 telemetry.backends.spark-rest[].base-url";
    }

    // ------------------------------------------------------------------ discovery

    @Override
    public List<DiscoveryData.JobView> jobs(RuntimeQuerySpec spec) {
        requireConfigured();
        if ("history".equals(mode())) {
            JsonNode root = client.getJson("/api/v1/applications");
            List<DiscoveryData.JobView> result = new ArrayList<>();
            int limit = limit(spec);
            if (root.isArray()) {
                for (JsonNode app : root) {
                    if (result.size() >= limit) {
                        break;
                    }
                    result.add(toJob(app, null));
                }
            }
            return result;
        }
        String appId = requireJobId(spec);
        JsonNode app = client.getJson("/api/v1/applications/" + encode(appId));
        return List.of(toJob(app, appId));
    }

    private DiscoveryData.JobView toJob(JsonNode app, String fallbackId) {
        String jobId = text(app, "id", fallbackId == null ? "" : fallbackId);
        JsonNode attempts = app.path("attempts");
        JsonNode last = attempts.isArray() && !attempts.isEmpty()
                ? attempts.get(attempts.size() - 1) : null;
        boolean completed = last != null && last.path("completed").asBoolean(false);
        return new DiscoveryData.JobView(jobId, text(app, "name", ""),
                completed ? "FINISHED" : "RUNNING",
                last == null ? null : epoch(last, "startTime"),
                last == null ? null : epoch(last, "endTime"),
                last == null ? null : epoch(last, "duration"),
                null,
                "attempts=" + (attempts.isArray() ? attempts.size() : 0)
                        + "，mode=" + mode());
    }

    @Override
    public List<RuntimeComponent> components(RuntimeQuerySpec spec) {
        requireConfigured();
        String role = spec.componentRole() == null ? "" : spec.componentRole().trim().toLowerCase();
        if (STAGE.equals(role)) {
            return stages(spec);
        }
        return executors(spec);
    }

    // ------------------------------------------------------------------ snapshot

    @Override
    public SnapshotData snapshot(RuntimeQuerySpec spec) {
        requireConfigured();
        String appId = requireJobId(spec);
        long capturedAt = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();
        JsonNode app = client.getJson("/api/v1/applications/" + encode(appId));

        Map<String, String> unavailable = unavailableFamilies(spec.families());
        List<RuntimeMetric> jobMetrics = jobMetrics(spec, app, capturedAt, warnings);

        List<RuntimeComponent> components = new ArrayList<>();
        if (wantsExecutorMetrics(spec)) {
            components.addAll(executors(spec));
        }
        if (spec.families().contains(RuntimeFamilies.THROUGHPUT)
                || spec.families().contains(RuntimeFamilies.SHUFFLE)
                || spec.families().contains(RuntimeFamilies.FAILURES)) {
            components.addAll(stages(spec));
        }
        List<RuntimeComponent> filtered = filterComponents(components, spec.components(), warnings);

        SnapshotData.CheckpointView checkpoint = null;
        if (spec.families().contains(RuntimeFamilies.CHECKPOINT)) {
            checkpoint = new SnapshotData.CheckpointView(false,
                    RuntimeFamilies.unavailableFor(serviceType()).get(RuntimeFamilies.CHECKPOINT),
                    null, null, null, null, null, List.of());
        }

        Map<String, Object> jobConfig = new LinkedHashMap<>();
        if (spec.includeConfig()) {
            jobConfig.putAll(configSections(appId, app, warnings));
        }

        JsonNode last = lastAttempt(app);
        if (filtered.isEmpty() && jobMetrics.isEmpty()) {
            warnings.add("应用当前没有可用的 executor/stage 样本（可能刚启动或历史已被回收）");
        }

        return new SnapshotData(appId, text(app, "name", ""),
                last == null ? "UNKNOWN"
                        : (last.path("completed").asBoolean(false) ? "FINISHED" : "RUNNING"),
                last == null ? null : epoch(last, "startTime"),
                last == null ? null : epoch(last, "endTime"),
                last == null ? null : epoch(last, "duration"),
                capturedAt, filtered, jobMetrics, checkpoint, unavailable, jobConfig, warnings);
    }

    @Override
    public ConfigData config(RuntimeQuerySpec spec) {
        requireConfigured();
        String appId = requireJobId(spec);
        List<String> warnings = new ArrayList<>();
        JsonNode app = client.getJson("/api/v1/applications/" + encode(appId));
        Map<String, Object> sections = new LinkedHashMap<>(configSections(appId, app, warnings));
        boolean available = sections.containsKey("sparkProperties");
        String reason = available ? null
                : "未获取到 /applications/" + appId + "/environment（live UI 或该版本可能不提供）";
        List<RuntimeComponent> components = filterComponents(executors(spec.withoutConfig()),
                spec.components(), warnings);
        return new ConfigData(available, reason, appId, System.currentTimeMillis(), sections,
                components, warnings);
    }

    // ------------------------------------------------------------------ Executor

    private List<RuntimeComponent> executors(RuntimeQuerySpec spec) {
        String appId = requireJobId(spec);
        boolean history = "history".equals(mode());
        String primary = history ? "allexecutors" : "executors";
        String fallback = history ? "executors" : "allexecutors";
        String root = "/api/v1/applications/" + encode(appId) + "/";

        JsonNode node = optional(root + primary);
        if (node == null || (node.isArray() && node.isEmpty())) {
            JsonNode fallbackNode = optional(root + fallback);
            if (fallbackNode != null && fallbackNode.isArray() && !fallbackNode.isEmpty()) {
                node = fallbackNode;
            }
        }
        if (node == null || !node.isArray()) {
            throw new BackendException(CapabilityCode.BACKEND_UNAVAILABLE,
                    "Spark executor 端点不可用（live: /executors；history: /allexecutors）");
        }

        List<MetricDefinition> specs = catalogMetrics(EXECUTOR, spec.families());
        List<String> explicitNames = spec.metrics();
        List<RuntimeComponent> result = new ArrayList<>();
        int limit = limit(spec);
        for (JsonNode executor : node) {
            if (result.size() >= limit) {
                break;
            }
            String id = text(executor, "id", "");
            if (!spec.components().isEmpty() && !spec.components().contains(id)) {
                continue;
            }
            String removeReason = text(executor, "removeReason", null);
            RuntimeComponent component = new RuntimeComponent(id, EXECUTOR, id,
                    text(executor, "hostPort", null), executorStatus(executor),
                    null, null, executor.path("totalCores").asInt(0),
                    longOrNull(executor.path("maxMemory")),
                    longOrNull(executor.path("memoryUsed")),
                    epoch(executor, "addTime"), epoch(executor, "removeTime"),
                    removeReason, List.of());

            List<RuntimeMetric> metrics = new ArrayList<>();
            for (MetricDefinition definition : specs) {
                Double raw = numberOrNull(executor.get(definition.rawMetric()));
                metrics.add(toMetric(definition, raw,
                        "executor 未返回该字段（历史应用可能已回收）"));
            }
            for (String name : explicitNames) {
                Double raw = numberOrNull(executor.get(name));
                metrics.add(explicitMetric(name, raw));
            }
            result.add(component.withMetrics(metrics));
        }
        return result;
    }

    private String executorStatus(JsonNode executor) {
        if (executor.path("isActive").asBoolean(false)) {
            return "ACTIVE";
        }
        return executor.path("removeTime").asLong(0) > 0 ? "REMOVED" : "DEAD";
    }

    // ------------------------------------------------------------------ Stage

    private List<RuntimeComponent> stages(RuntimeQuerySpec spec) {
        String appId = requireJobId(spec);
        JsonNode root = optional("/api/v1/applications/" + encode(appId) + "/stages");
        if (root == null || !root.isArray()) {
            throw new BackendException(CapabilityCode.BACKEND_UNAVAILABLE,
                    "未获取到 /applications/" + appId + "/stages，stage 级指标不可用");
        }

        Map<Integer, JsonNode> latestByStage = new LinkedHashMap<>();
        for (JsonNode stage : root) {
            int stageId = stage.path("stageId").asInt(-1);
            if (stageId < 0) {
                continue;
            }
            JsonNode existing = latestByStage.get(stageId);
            if (existing == null
                    || stage.path("attemptId").asInt(0) >= existing.path("attemptId").asInt(0)) {
                latestByStage.put(stageId, stage);
            }
        }

        List<MetricDefinition> specs = catalogMetrics(STAGE, spec.families());
        List<JsonNode> ordered = new ArrayList<>(latestByStage.values());
        ordered.sort(Comparator.comparingInt(stage -> stage.path("stageId").asInt(0)));

        List<RuntimeComponent> result = new ArrayList<>();
        int limit = limit(spec);
        for (JsonNode stage : ordered) {
            if (result.size() >= limit) {
                break;
            }
            int stageId = stage.path("stageId").asInt(0);
            int attemptId = stage.path("attemptId").asInt(0);
            String id = "stage-" + stageId + "-" + attemptId;
            if (!spec.components().isEmpty() && !spec.components().contains(id)) {
                continue;
            }
            RuntimeComponent component = new RuntimeComponent(id, STAGE,
                    text(stage, "name", "stage-" + stageId), null,
                    text(stage, "status", ""), null, null, null, null, null,
                    epoch(stage, "submissionTime"), epoch(stage, "completionTime"),
                    text(stage, "failureReason", null), List.of());

            List<RuntimeMetric> metrics = new ArrayList<>();
            for (MetricDefinition definition : specs) {
                Double raw = numberOrNull(stage.get(definition.rawMetric()));
                if ("attemptId".equals(definition.rawMetric()) && raw != null) {
                    raw = raw + 1;
                }
                metrics.add(toMetric(definition, raw, "stage 未返回该字段"));
            }
            for (String name : spec.metrics()) {
                metrics.add(explicitMetric(name, numberOrNull(stage.get(name))));
            }
            result.add(component.withMetrics(metrics));
        }
        return result;
    }

    // ------------------------------------------------------------------ 作业级指标

    private List<RuntimeMetric> jobMetrics(RuntimeQuerySpec spec, JsonNode app, long capturedAt,
                                           List<String> warnings) {
        List<RuntimeMetric> result = new ArrayList<>();
        if (spec.families().contains(RuntimeFamilies.FAILURES)) {
            for (MetricDefinition definition : catalogMetrics("application", spec.families())) {
                Double raw = "attempts".equals(definition.rawMetric())
                        ? (double) (app.path("attempts").isArray()
                        ? app.path("attempts").size() : 0) : null;
                result.add(toMetric(definition, raw, "应用未返回该字段"));
            }
            List<MetricDefinition> jobSpecs = catalogMetrics(JOB, spec.families());
            if (!jobSpecs.isEmpty()) {
                JsonNode jobs = optional("/api/v1/applications/" + encode(spec.jobId()) + "/jobs");
                if (jobs == null || !jobs.isArray()) {
                    warnings.add("未获取到 /applications/" + spec.jobId()
                            + "/jobs，作业级失败指标不可用");
                    for (MetricDefinition definition : jobSpecs) {
                        result.add(RuntimeMetric.unavailable(definition.name(),
                                RuntimeFamilies.FAILURES, definition.rawMetric(), definition.unit(),
                                "jobs 端点不可用"));
                    }
                } else {
                    for (MetricDefinition definition : jobSpecs) {
                        double sum = 0;
                        for (JsonNode job : jobs) {
                            sum += job.path(definition.rawMetric()).asDouble(0);
                        }
                        result.add(toMetric(definition, sum, null));
                    }
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ 配置

    private Map<String, Object> configSections(String appId, JsonNode app, List<String> warnings) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, String> application = new LinkedHashMap<>();
        application.put("appId", appId);
        application.put("name", text(app, "name", ""));
        application.put("attempts", String.valueOf(app.path("attempts").size()));
        application.put("mode", mode());
        result.put("application", application);

        JsonNode environment = optional("/api/v1/applications/" + encode(appId) + "/environment");
        if (environment == null) {
            warnings.add("environment 端点不可用，作业配置不完整");
            return result;
        }
        result.put("sparkProperties", flatten(environment.path("sparkProperties")));
        result.put("hadoopProperties", flatten(environment.path("hadoopProperties")));
        result.put("systemProperties", flatten(environment.path("systemProperties")));
        Map<String, String> classpath = new LinkedHashMap<>();
        JsonNode entries = environment.path("classpathEntries");
        if (entries.isArray()) {
            int index = 0;
            for (JsonNode entry : entries) {
                classpath.put("classpath[" + index++ + "]", entry.isArray()
                        ? String.join(":", texts(entry)) : entry.asText(""));
            }
        }
        result.put("classpath", classpath);
        return result;
    }

    // ------------------------------------------------------------------ 指标解析

    private RuntimeMetric toMetric(MetricDefinition definition, Double rawValue, String missingReason) {
        String family = RuntimeFamilies.familyOf(definition.name());
        if (rawValue == null) {
            return RuntimeMetric.unavailable(definition.name(), family, definition.rawMetric(),
                    definition.unit(), missingReason);
        }
        double value = MetricValueNormalizer.normalize(definition.rawMetric(), definition.unit(),
                rawValue);
        return RuntimeMetric.available(definition.name(), family, definition.rawMetric(), value,
                definition.unit());
    }

    private RuntimeMetric explicitMetric(String name, Double value) {
        if (value == null) {
            return RuntimeMetric.unavailable(name, RuntimeFamilies.EXPLICIT, name,
                    FlinkRuntimeEngine.guessUnit(name), "组件未返回该字段");
        }
        return RuntimeMetric.available(name, RuntimeFamilies.EXPLICIT, name, value,
                FlinkRuntimeEngine.guessUnit(name));
    }

    private List<MetricDefinition> catalogMetrics(String scope, List<String> families) {
        List<MetricDefinition> result = new ArrayList<>();
        // Spark 不再向指标目录写手工语义；作业级快照字段只服务 /api/runtime/**，
        // 不进入 metrics/query，也不冒充运行时发现。
        List<MetricDefinition> source = new ArrayList<>(
                RuntimeFamilies.catalogMetrics(serviceType(), catalogRegistry.catalog()));
        if (source.isEmpty()) {
            source.addAll(SparkRuntimeMetricSpecs.DEFINITIONS);
        }
        for (MetricDefinition definition : source) {
            if (!scope.equals(definition.scope())) {
                continue;
            }
            if (!families.contains(RuntimeFamilies.familyOf(definition.name()))) {
                continue;
            }
            if (definition.rawMetric() == null || definition.rawMetric().isBlank()) {
                continue;
            }
            result.add(definition);
        }
        return result;
    }

    private Map<String, String> unavailableFamilies(List<String> families) {
        Map<String, String> all = RuntimeFamilies.unavailableFor(serviceType());
        Map<String, String> result = new LinkedHashMap<>();
        for (String family : families) {
            String reason = all.get(family);
            if (reason != null) {
                result.put(family, reason);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ 工具

    private void requireConfigured() {
        if (!configured()) {
            throw new BackendException(CapabilityCode.NOT_CONFIGURED, unconfiguredReason());
        }
    }

    private String requireJobId(RuntimeQuerySpec spec) {
        if (spec.jobId() == null || spec.jobId().isBlank()) {
            throw new BackendException(CapabilityCode.INVALID_ARGUMENT,
                    "缺少 jobId（Spark applicationId）");
        }
        return spec.jobId().trim();
    }

    private boolean wantsExecutorMetrics(RuntimeQuerySpec spec) {
        return spec.families().contains(RuntimeFamilies.JVM_GC)
                || spec.families().contains(RuntimeFamilies.THROUGHPUT)
                || spec.families().contains(RuntimeFamilies.FAILURES)
                || !spec.metrics().isEmpty();
    }

    private int limit(RuntimeQuerySpec spec) {
        int value = spec.limit() > 0 ? spec.limit() : properties.getMaxComponents();
        return Math.max(value, 1);
    }

    private List<RuntimeComponent> filterComponents(List<RuntimeComponent> components,
                                                    List<String> ids, List<String> warnings) {
        if (ids == null || ids.isEmpty()) {
            return components;
        }
        List<RuntimeComponent> result = new ArrayList<>();
        for (RuntimeComponent component : components) {
            if (ids.contains(component.id())) {
                result.add(component);
            }
        }
        if (result.size() < ids.size()) {
            List<String> missing = new ArrayList<>(ids);
            missing.removeAll(result.stream().map(RuntimeComponent::id).toList());
            warnings.add("以下组件未找到: " + String.join(",", missing));
        }
        return result;
    }

    private JsonNode lastAttempt(JsonNode app) {
        JsonNode attempts = app.path("attempts");
        return attempts.isArray() && !attempts.isEmpty()
                ? attempts.get(attempts.size() - 1) : null;
    }

    private JsonNode optional(String path) {
        try {
            return client.getJson(path);
        } catch (BackendException e) {
            if (e.getCode() == CapabilityCode.NOT_FOUND) {
                return null;
            }
            throw e;
        }
    }

    private static List<String> texts(JsonNode array) {
        List<String> result = new ArrayList<>();
        for (JsonNode item : array) {
            result.add(item.asText(""));
        }
        return result;
    }

    private static Map<String, String> flatten(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        if (node == null) {
            return result;
        }
        // Spark 的 /environment 可能是键值数组，也可能是对象，两种都支持。
        if (node.isArray()) {
            for (JsonNode entry : node) {
                if (!entry.isArray() || entry.size() < 2) {
                    continue;
                }
                String key = entry.get(0).asText("");
                if (key.isBlank()) {
                    continue;
                }
                JsonNode value = entry.get(1);
                result.put(key, value.isValueNode() ? value.asText() : value.toString());
            }
            return result;
        }
        if (!node.isObject()) {
            return result;
        }
        node.fields().forEachRemaining(field -> result.put(field.getKey(),
                field.getValue().isValueNode() ? field.getValue().asText()
                        : field.getValue().toString()));
        return result;
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null) {
            return fallback;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return fallback;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? fallback : text;
    }

    private static Long epoch(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.canConvertToLong()) {
            return null;
        }
        long result = value.asLong();
        return result > 0 ? result : null;
    }

    private static Long longOrNull(JsonNode value) {
        if (value == null || value.isNull() || !value.canConvertToLong()) {
            return null;
        }
        long result = value.asLong();
        return result >= 0 ? result : null;
    }

    private static Double numberOrNull(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        if (value.isBoolean()) {
            return value.asBoolean() ? 1D : 0D;
        }
        String text = value.asText("");
        if (text.isBlank() || "<unknown>".equalsIgnoreCase(text) || "NaN".equalsIgnoreCase(text)) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
