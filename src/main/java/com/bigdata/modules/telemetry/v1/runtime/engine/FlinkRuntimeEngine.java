package com.bigdata.modules.telemetry.v1.runtime.engine;

import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.v1.backend.engine.EngineRestClient;
import com.bigdata.modules.telemetry.v1.catalog.TelemetryCatalogRegistry;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties.FlinkRestConfig;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Flink JobManager REST 的运行时引擎（live）。
 *
 * <p>端点：{@code /jobs/overview}、{@code /jobs/{id}}、{@code /jobs/{id}/checkpoints}、
 * {@code /jobs/{id}/config}、{@code /jobmanager/config}、{@code /taskmanagers}、
 * {@code /taskmanagers/{id}/metrics}、{@code /jobs/{id}/metrics}、
 * {@code /jobs/{id}/vertices/{vid}/subtasks/metrics}。</p>
 *
 * <p>取不到的指标一律 UNAVAILABLE + reason；{@code metrics} 端点里缺席的指标名不会报错，
 * 因此不能把它当成 0。</p>
 */
public class FlinkRuntimeEngine implements RuntimeEngine {

    private static final long HEARTBEAT_STALE_MS = 30_000L;
    private static final String TM = "taskmanager";
    private static final String VERTEX = "vertex";
    private static final String JOB = "job";

    private final String backendId;
    private final FlinkRestConfig config;
    private final String configurationError;
    private final TelemetryCatalogRegistry catalogRegistry;
    private final RuntimeV1Properties properties;
    private final EngineRestClient client;

    public FlinkRuntimeEngine(String backendId, FlinkRestConfig config, String authorizationHeader,
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
        return BackendType.FLINK_REST;
    }

    @Override
    public String serviceType() {
        return "flink";
    }

    @Override
    public String mode() {
        return "live";
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
        return "未配置 telemetry.backends.flink-rest[].base-url";
    }

    // ------------------------------------------------------------------ discovery

    @Override
    public List<DiscoveryData.JobView> jobs(RuntimeQuerySpec spec) {
        requireConfigured();
        JsonNode root = client.getJson("/jobs/overview");
        List<DiscoveryData.JobView> result = new ArrayList<>();
        int limit = limit(spec);
        for (JsonNode job : array(root, "jobs")) {
            if (result.size() >= limit) {
                break;
            }
            int failed = job.path("tasks").path("failed").asInt(0);
            int total = job.path("tasks").path("total").asInt(0);
            result.add(new DiscoveryData.JobView(
                    text(job, "jid", text(job, "id", "")),
                    text(job, "name", ""),
                    text(job, "state", ""),
                    epoch(job, "start-time"),
                    epoch(job, "end-time"),
                    epoch(job, "duration"),
                    null,
                    "tasks " + (total - failed) + "/" + total + " 正常"));
        }
        return result;
    }

    @Override
    public List<RuntimeComponent> components(RuntimeQuerySpec spec) {
        requireConfigured();
        String role = role(spec.componentRole());
        if (VERTEX.equals(role)) {
            return vertices(spec);
        }
        return taskManagers(spec);
    }

    // ------------------------------------------------------------------ snapshot

    @Override
    public SnapshotData snapshot(RuntimeQuerySpec spec) {
        requireConfigured();
        String jobId = requireJobId(spec);
        long capturedAt = System.currentTimeMillis();
        List<String> families = spec.families();
        List<String> warnings = new ArrayList<>();

        JsonNode job = client.getJson("/jobs/" + encode(jobId));

        Map<String, String> unavailable = unavailableFamilies(families);
        List<RuntimeMetric> jobMetrics = jobMetrics(spec, job);

        List<RuntimeComponent> components = new ArrayList<>();
        if (families.contains(RuntimeFamilies.JVM_GC)
                || hasExplicitStatusMetric(spec.metrics())) {
            components.addAll(taskManagers(spec));
        }
        if (families.contains(RuntimeFamilies.THROUGHPUT)
                || families.contains(RuntimeFamilies.BACKPRESSURE)
                || families.contains(RuntimeFamilies.SHUFFLE)
                || !explicit(spec.metrics(), false).isEmpty()) {
            components.addAll(vertices(spec));
        }
        List<RuntimeComponent> filtered = filterComponents(components, spec.components(), warnings);

        SnapshotData.CheckpointView checkpoint = null;
        if (families.contains(RuntimeFamilies.CHECKPOINT)
                || families.contains(RuntimeFamilies.FAILURES)) {
            checkpoint = checkpoint(spec, jobId);
        }

        Map<String, Object> jobConfig = new LinkedHashMap<>();
        if (spec.includeConfig()) {
            JsonNode jobConfigNode = optional("/jobs/" + encode(jobId) + "/config");
            jobConfig.put("jobId", jobId);
            jobConfig.put("name", text(job, "name", ""));
            jobConfig.put("executionConfig", jobConfigNode == null ? Map.of()
                    : flatten(jobConfigNode.path("execution-config")));
            JsonNode jmConfig = optional("/jobmanager/config");
            if (jmConfig != null && jmConfig.isArray()) {
                Map<String, String> values = new LinkedHashMap<>();
                for (JsonNode item : jmConfig) {
                    values.put(text(item, "key", ""), text(item, "value", ""));
                }
                jobConfig.put("jobmanager", values);
            }
        }

        String state = text(job, "state", "");
        if (isTerminal(state) && jobMetrics.isEmpty() && filtered.isEmpty()) {
            warnings.add("作业状态为 " + state
                    + "，JobManager 已不提供运行时指标；需要历史数据请接入 Flink History Server");
        }

        return new SnapshotData(jobId, text(job, "name", ""), state,
                epoch(job, "start-time"), epoch(job, "end-time"), epoch(job, "duration"),
                capturedAt, filtered, jobMetrics, checkpoint, unavailable, jobConfig, warnings);
    }

    @Override
    public ConfigData config(RuntimeQuerySpec spec) {
        requireConfigured();
        String jobId = requireJobId(spec);
        List<String> warnings = new ArrayList<>();
        Map<String, Object> sections = new LinkedHashMap<>();
        boolean available = false;
        String reason = null;

        JsonNode jobConfig = optional("/jobs/" + encode(jobId) + "/config");
        if (jobConfig == null) {
            reason = "未获取到 /jobs/" + jobId + "/config（作业可能已归档或该版本不提供）";
        } else {
            available = true;
            sections.put("job", flatten(jobConfig.path("execution-config")));
        }

        JsonNode jmConfig = optional("/jobmanager/config");
        if (jmConfig == null) {
            warnings.add("未获取到 /jobmanager/config（集群配置不可用）");
        } else if (jmConfig.isArray()) {
            Map<String, String> values = new LinkedHashMap<>();
            for (JsonNode item : jmConfig) {
                values.put(text(item, "key", ""), text(item, "value", ""));
            }
            sections.put("jobmanager", values);
        }

        List<RuntimeComponent> components = filterComponents(taskManagers(spec.withoutConfig()),
                spec.components(), warnings);
        warnings.add("Flink REST 不暴露单个 TaskManager 的 JVM 启动参数；"
                + "TM 级资源以 totalResource/hardware 为准，JVM 参数需从部署配置获取");

        return new ConfigData(available, reason, jobId, System.currentTimeMillis(), sections,
                components, warnings);
    }

    // ------------------------------------------------------------------ 作业级指标

    private List<RuntimeMetric> jobMetrics(RuntimeQuerySpec spec, JsonNode job) {
        List<MetricDefinition> specs = new ArrayList<>();
        for (MetricDefinition definition : catalogMetrics(JOB, spec.families())) {
            if (definition.rawMetric() != null && definition.rawMetric().contains("/")) {
                continue;
            }
            if ("tasks.failed".equals(definition.rawMetric())) {
                continue;
            }
            specs.add(definition);
        }
        Set<String> names = new LinkedHashSet<>();
        for (MetricDefinition definition : specs) {
            names.add(definition.rawMetric());
        }
        List<String> explicitJobMetrics = explicit(spec.metrics(), false);
        names.addAll(explicitJobMetrics);

        Map<String, List<Double>> values = names.isEmpty() ? Map.of()
                : fetchMetrics("/jobs/" + encode(spec.jobId()) + "/metrics?get="
                + encode(String.join(",", names)));

        List<RuntimeMetric> result = new ArrayList<>();
        for (MetricDefinition definition : specs) {
            Double value = aggregate(values, definition.rawMetric(), false);
            result.add(toMetric(definition, value, jobMissingReason(definition)));
        }
        if (spec.families().contains(RuntimeFamilies.FAILURES)) {
            result.add(RuntimeMetric.available("flink_tasks_failed", RuntimeFamilies.FAILURES,
                    "tasks.failed", job.path("tasks").path("failed").asInt(0), "count"));
        }
        for (String name : explicitJobMetrics) {
            Double value = aggregate(values, name, false);
            result.add(explicitMetric(name, value, "作业未返回该指标（可能名称不存在或已结束）"));
        }
        return result;
    }

    // ------------------------------------------------------------------ TaskManager

    private List<RuntimeComponent> taskManagers(RuntimeQuerySpec spec) {
        JsonNode root = client.getJson("/taskmanagers");
        List<RuntimeComponent> result = new ArrayList<>();
        List<MetricDefinition> specs = catalogMetrics("component", spec.families());
        List<String> explicitNames = explicit(spec.metrics(), true);
        Set<String> names = new LinkedHashSet<>();
        for (MetricDefinition definition : specs) {
            names.add(definition.rawMetric());
        }
        names.addAll(explicitNames);

        int limit = limit(spec);
        long now = System.currentTimeMillis();
        for (JsonNode tm : array(root, "taskmanagers")) {
            if (result.size() >= limit) {
                break;
            }
            RuntimeComponent component = mapTaskManager(tm, now);
            List<RuntimeMetric> metrics = new ArrayList<>();
            if (!names.isEmpty()) {
                Map<String, List<Double>> values = fetchMetrics("/taskmanagers/"
                        + encode(component.id()) + "/metrics?get="
                        + encode(String.join(",", names)));
                for (MetricDefinition definition : specs) {
                    Double value = aggregate(values, definition.rawMetric(), true);
                    metrics.add(toMetric(definition, value,
                            "TM 未返回该指标（版本可能不支持通配符或指标未注册）"));
                }
                for (String name : explicitNames) {
                    Double value = aggregate(values, name, true);
                    metrics.add(explicitMetric(name, value, "TM 未返回该指标"));
                }
            }
            result.add(component.withMetrics(metrics));
        }
        return result;
    }

    private RuntimeComponent mapTaskManager(JsonNode tm, long now) {
        String id = text(tm, "id", "");
        long heartbeat = tm.path("timeSinceLastHeartbeat").asLong(0L);
        // Flink 返回的是最后一次心跳的毫秒时间戳，不是「距今毫秒数」，必须做差。
        boolean active = heartbeat > 0 && now - heartbeat <= HEARTBEAT_STALE_MS;
        JsonNode total = tm.path("totalResource");
        JsonNode hardware = tm.path("hardware");
        long memory = Math.round(number(total, "memory", 0));
        if (memory <= 0) {
            memory = Math.round(number(hardware, "physicalMemory", 0));
        }
        long managed = Math.round(number(total, "managedMemory", 0));
        String note = active ? null : "超过 " + (HEARTBEAT_STALE_MS / 1000) + "s 未收到心跳";
        return new RuntimeComponent(id, TM, id, tmHost(tm),
                active ? "ACTIVE" : "STALE",
                tm.path("slotsNumber").asInt(0), tm.path("freeSlots").asInt(0),
                (int) Math.round(number(total, "cpuCores", 0)),
                memory > 0 ? memory : null, managed > 0 ? managed : null,
                null, null, note, List.of());
    }

    private String tmHost(JsonNode tm) {
        String path = text(tm, "path", "");
        int at = path.indexOf('@');
        if (at < 0) {
            return null;
        }
        String host = path.substring(at + 1);
        int slash = host.indexOf('/');
        if (slash > 0) {
            host = host.substring(0, slash);
        }
        int colon = host.lastIndexOf(':');
        return colon > 0 ? host.substring(0, colon) : host;
    }

    // ------------------------------------------------------------------ Vertex

    private List<RuntimeComponent> vertices(RuntimeQuerySpec spec) {
        String jobId = requireJobId(spec);
        JsonNode job = client.getJson("/jobs/" + encode(jobId));
        List<String> families = new ArrayList<>();
        for (String family : List.of(RuntimeFamilies.THROUGHPUT, RuntimeFamilies.BACKPRESSURE,
                RuntimeFamilies.SHUFFLE)) {
            if (spec.families().contains(family)) {
                families.add(family);
            }
        }
        List<MetricDefinition> specs = new ArrayList<>();
        for (String family : families) {
            specs.addAll(catalogMetrics(VERTEX, List.of(family)));
        }
        List<String> explicitNames = explicit(spec.metrics(), false);
        Set<String> names = new LinkedHashSet<>();
        for (MetricDefinition definition : specs) {
            names.add(definition.rawMetric());
        }
        names.addAll(explicitNames);
        if (names.isEmpty()) {
            return List.of();
        }

        List<RuntimeComponent> result = new ArrayList<>();
        int limit = Math.min(limit(spec), Math.max(properties.getMaxVertices(), 1));
        for (JsonNode vertex : array(job, "vertices")) {
            if (result.size() >= limit) {
                break;
            }
            String vertexId = text(vertex, "id", "");
            String name = text(vertex, "name", vertexId);
            Map<String, List<Double>> values = fetchMetrics("/jobs/" + encode(jobId)
                    + "/vertices/" + encode(vertexId) + "/subtasks/metrics?get="
                    + encode(String.join(",", names)));
            List<RuntimeMetric> metrics = new ArrayList<>();
            for (MetricDefinition definition : specs) {
                boolean ratio = RuntimeFamilies.BACKPRESSURE.equals(
                        RuntimeFamilies.familyOf(definition.name()));
                Double value = aggregate(values, definition.rawMetric(), ratio);
                metrics.add(toMetric(definition, value, "该 vertex 未返回此指标（作业可能已结束）"));
            }
            for (String metricName : explicitNames) {
                Double value = aggregate(values, metricName, false);
                metrics.add(explicitMetric(metricName, value, "该 vertex 未返回此指标"));
            }
            result.add(new RuntimeComponent(vertexId, VERTEX, name, null,
                    text(vertex, "status", ""), null, null, null, null, null,
                    null, null, null, metrics));
        }
        return result;
    }

    // ------------------------------------------------------------------ Checkpoint

    private SnapshotData.CheckpointView checkpoint(RuntimeQuerySpec spec, String jobId) {
        JsonNode root = optional("/jobs/" + encode(jobId) + "/checkpoints");
        if (root == null) {
            return new SnapshotData.CheckpointView(false,
                    "当前 Flink 版本未提供 /jobs/" + jobId + "/checkpoints 或作业已归档",
                    null, null, null, null, null, List.of());
        }
        JsonNode counts = root.path("counts");
        JsonNode latestFailed = root.path("latest").path("failed");
        List<SnapshotData.CheckpointView.Entry> history = new ArrayList<>();
        for (JsonNode item : array(root, "history")) {
            history.add(checkpointEntry(item));
        }
        if (history.isEmpty()) {
            for (String key : new String[]{"completed", "failed", "restored", "savepoint"}) {
                JsonNode item = root.path("latest").path(key);
                if (item.isObject()) {
                    history.add(checkpointEntry(item));
                }
            }
        }
        history.sort(Comparator.comparing(entry -> entry.id() == null ? -1L : entry.id(),
                Comparator.reverseOrder()));
        int limit = Math.max(spec.checkpointHistory(), 1);
        if (history.size() > limit) {
            history = new ArrayList<>(history.subList(0, limit));
        }
        return new SnapshotData.CheckpointView(true, null,
                counts.path("completed").asInt(0), counts.path("failed").asInt(0),
                counts.path("in_progress").asInt(0), counts.path("restored").asInt(0),
                latestFailed.isObject()
                        ? text(latestFailed, "failure_message",
                        text(latestFailed, "failureMessage", null)) : null,
                history);
    }

    private SnapshotData.CheckpointView.Entry checkpointEntry(JsonNode item) {
        long size = item.path("state_size").asLong(0);
        return new SnapshotData.CheckpointView.Entry(
                item.path("id").isNumber() ? item.path("id").asLong() : null,
                text(item, "status", ""),
                epoch(item, "trigger_timestamp"),
                epoch(item, "latest_ack_timestamp"),
                epoch(item, "end_to_end_duration"),
                size > 0 ? size : null,
                text(item, "failure_message", text(item, "failureMessage", null)));
    }

    // ------------------------------------------------------------------ 指标解析

    private Map<String, List<Double>> fetchMetrics(String path) {
        return parseMetricValues(optional(path));
    }

    static Map<String, List<Double>> parseMetricValues(JsonNode node) {
        Map<String, List<Double>> result = new LinkedHashMap<>();
        if (node == null) {
            return result;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String id = normalizeId(item.path("id").asText(""));
                JsonNode value = item.get("value");
                if (value != null) {
                    addValue(result, id, numberOrNull(value));
                    continue;
                }
                // vertex 的 subtasks/metrics 返回 min/max/avg/sum；累计量取 sum，比率取 max。
                addValue(result, id + "#sum", numberOrNull(item.get("sum")));
                addValue(result, id + "#max", numberOrNull(item.get("max")));
            }
        } else if (node.isObject()) {
            node.fields().forEachRemaining(field -> {
                JsonNode value = field.getValue();
                if (value.isObject()) {
                    value.fields().forEachRemaining(inner -> addValue(result,
                            normalizeId(inner.getKey()), numberOrNull(inner.getValue())));
                } else {
                    addValue(result, normalizeId(field.getKey()), numberOrNull(value));
                }
            });
        }
        return result;
    }

    private static String normalizeId(String id) {
        int dot = id.indexOf('.');
        if (dot > 0 && id.substring(0, dot).chars().allMatch(Character::isDigit)) {
            return id.substring(dot + 1);
        }
        return id;
    }

    private static void addValue(Map<String, List<Double>> values, String key, Double value) {
        if (key == null || key.isBlank() || value == null) {
            return;
        }
        values.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }

    private static Double aggregate(Map<String, List<Double>> values, String raw, boolean max) {
        String suffix = max ? "#max" : "#sum";
        List<Double> matched = new ArrayList<>();
        for (Map.Entry<String, List<Double>> entry : values.entrySet()) {
            String key = entry.getKey();
            String base = key;
            if (key.endsWith("#sum") || key.endsWith("#max")) {
                if (!key.endsWith(suffix)) {
                    continue;
                }
                base = key.substring(0, key.length() - 4);
            }
            if (matches(raw, base)) {
                matched.addAll(entry.getValue());
            }
        }
        if (matched.isEmpty()) {
            return null;
        }
        return max
                ? matched.stream().mapToDouble(Double::doubleValue).max().orElse(0)
                : matched.stream().mapToDouble(Double::doubleValue).sum();
    }

    static boolean matches(String pattern, String value) {
        if (pattern == null || value == null) {
            return false;
        }
        if (!pattern.contains("*")) {
            return pattern.equals(value);
        }
        String regex = java.util.Arrays.stream(pattern.split("\\*", -1))
                .map(Pattern::quote)
                .collect(Collectors.joining(".*"));
        return value.matches(regex);
    }

    private String jobMissingReason(MetricDefinition definition) {
        if (RuntimeFamilies.THROUGHPUT.equals(RuntimeFamilies.familyOf(definition.name()))) {
            return "Flink 2.x 作业级 metrics 不返回吞吐指标；请查看 vertex 级 flink_vertex_*";
        }
        return "作业未返回该指标（Flink 不返回缺失指标）";
    }

    private RuntimeMetric toMetric(MetricDefinition definition, Double rawValue, String missingReason) {
        String family = RuntimeFamilies.familyOf(definition.name());
        if (rawValue != null && rawValue < 0
                && ("lastCheckpointDuration".equals(definition.rawMetric())
                || "lastCheckpointSize".equals(definition.rawMetric()))) {
            return RuntimeMetric.unavailable(definition.name(), family, definition.rawMetric(),
                    definition.unit(), "Flink 返回 -1，表示尚未产生完成的 checkpoint");
        }
        if (rawValue == null) {
            return RuntimeMetric.unavailable(definition.name(), family, definition.rawMetric(),
                    definition.unit(), missingReason);
        }
        double value = MetricValueNormalizer.normalize(definition.rawMetric(), definition.unit(),
                rawValue);
        return RuntimeMetric.available(definition.name(), family, definition.rawMetric(), value,
                definition.unit());
    }

    private RuntimeMetric explicitMetric(String name, Double value, String reason) {
        if (value == null) {
            return RuntimeMetric.unavailable(name, RuntimeFamilies.EXPLICIT, name, guessUnit(name),
                    reason);
        }
        return RuntimeMetric.available(name, RuntimeFamilies.EXPLICIT, name, value, guessUnit(name));
    }

    static String guessUnit(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        if (lower.contains("persecond")) {
            return lower.contains("byte") ? "bytes_per_second" : "records_per_second";
        }
        if (lower.contains("byte") || lower.contains("size")) {
            return "bytes";
        }
        if (lower.contains("time") || lower.contains("duration")) {
            return "ms";
        }
        if (lower.contains("record")) {
            return "records";
        }
        return "count";
    }

    // ------------------------------------------------------------------ 目录

    private List<MetricDefinition> catalogMetrics(String scope, List<String> families) {
        List<MetricDefinition> result = new ArrayList<>();
        for (MetricDefinition definition : RuntimeFamilies.catalogMetrics(serviceType(),
                catalogRegistry.catalog())) {
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
                    "缺少 jobId（Flink 作业 id）");
        }
        return spec.jobId().trim();
    }

    private int limit(RuntimeQuerySpec spec) {
        int value = spec.limit() > 0 ? spec.limit() : properties.getMaxComponents();
        return Math.max(value, 1);
    }

    private String role(String role) {
        if (role == null || role.isBlank()) {
            return TM;
        }
        String value = role.trim().toLowerCase();
        if ("executor".equals(value)) {
            return TM;
        }
        return value;
    }

    private List<String> explicit(List<String> metrics, boolean taskManager) {
        List<String> result = new ArrayList<>();
        for (String metric : metrics) {
            if (metric == null || metric.isBlank()) {
                continue;
            }
            boolean status = metric.startsWith("Status.");
            if (taskManager == status) {
                result.add(metric);
            }
        }
        return result;
    }

    private boolean hasExplicitStatusMetric(List<String> metrics) {
        return !explicit(metrics, true).isEmpty();
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

    private Iterable<JsonNode> array(JsonNode node, String field) {
        if (node == null) {
            return List.of();
        }
        JsonNode array = node.path(field);
        return array.isArray() ? array : List.of();
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

    private static double number(JsonNode node, String field, double fallback) {
        if (node == null) {
            return fallback;
        }
        Double parsed = numberOrNull(node.get(field));
        return parsed == null ? fallback : parsed;
    }

    private static Double numberOrNull(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asDouble();
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

    private static Map<String, String> flatten(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return result;
        }
        node.fields().forEachRemaining(field -> {
            JsonNode value = field.getValue();
            result.put(field.getKey(), value.isValueNode() ? value.asText() : value.toString());
        });
        return result;
    }

    private static boolean isTerminal(String state) {
        return "FINISHED".equalsIgnoreCase(state) || "FAILED".equalsIgnoreCase(state)
                || "CANCELED".equalsIgnoreCase(state) || "CANCELLED".equalsIgnoreCase(state);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
