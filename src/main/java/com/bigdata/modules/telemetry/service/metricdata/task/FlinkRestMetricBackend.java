package com.bigdata.modules.telemetry.service.metricdata.task;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.Requirement;
import com.bigdata.modules.telemetry.common.model.RequirementKind;
import com.bigdata.modules.telemetry.common.http.EngineRestClient;
import com.bigdata.modules.telemetry.common.model.BackendType;
import com.bigdata.modules.telemetry.common.model.QueryMode;
import com.bigdata.modules.telemetry.common.model.BackendCapability;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.bigdata.modules.telemetry.service.metricdata.spi.MetricBackend;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricQueryResult;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricQuerySpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DoubleNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.bigdata.modules.telemetry.config.properties.FlinkRestConfig;
import com.bigdata.modules.telemetry.util.Urls;
import com.bigdata.modules.telemetry.util.Strings;

/**
 * Flink REST 指标后端：checkpoint 历史、背压与作业/TM/vertex 快照。
 *
 * <p>证据边界：
 * <ul>
 *   <li>checkpoint 是离散事件序列，range 查询直接返回事件时间点，不做等步长插值；</li>
 *   <li>背压端点只有当前快照，因此只支持 instant，range 明确返回 QUERY_MODE_UNSUPPORTED；</li>
 * </ul>
 */
public final class FlinkRestMetricBackend implements MetricBackend {

    private static final Set<String> CHECKPOINT_METRICS = Set.of(
            "flink_checkpoint_duration_seconds",
            "flink_checkpoint_size_bytes",
            "flink_checkpoint_completed_total",
            "flink_checkpoint_failed_total");
    private static final Set<String> BACKPRESSURE_METRICS = Set.of(
            "flink_backpressure_busy_ratio",
            "flink_backpressure_backpressured_ratio");
    private static final Set<String> CHECKPOINT_VIEW_METRICS = Set.of(
            "flink_checkpoint_in_progress",
            "flink_checkpoint_restored_total",
            "flink_checkpoint_latest_info",
            "flink_checkpoint_latest_failed");
    private static final Set<String> JOB_METRICS = Set.of(
            "flink_tasks_failed",
            "flink_tasks_total");
    private static final Set<String> TASKMANAGER_METRICS = Set.of(
            "flink_taskmanager_info",
            "flink_taskmanager_slots_total",
            "flink_taskmanager_slots_free",
            "flink_taskmanager_cpu_cores",
            "flink_taskmanager_memory_bytes",
            "flink_taskmanager_managed_memory_bytes",
            "flink_taskmanager_physical_memory_bytes",
            "flink_taskmanager_last_heartbeat_seconds");
    private static final Set<String> VERTEX_INVENTORY_METRICS = Set.of(
            "flink_vertex_info",
            "flink_vertex_parallelism",
            "flink_vertex_tasks_failed");
    private static final long HEARTBEAT_STALE_MS = 30_000L;

    private final String backendId;
    private final FlinkRestConfig config;
    private final ObjectMapper objectMapper;
    private final EngineRestClient client;

    public FlinkRestMetricBackend(String backendId, FlinkRestConfig config,
                                  String authorizationHeader, ObjectMapper objectMapper) {
        this.backendId = backendId;
        this.config = config;
        this.objectMapper = objectMapper;
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
    public BackendCapability evaluate(MetricQuerySpec spec) {
        if (Strings.isBlank(config.getBaseUrl())) {
            return BackendCapability.unavailable(CapabilityCode.NOT_CONFIGURED,
                    "未配置 Flink REST 地址",
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "telemetry.backends.flink-rest[].base-url", "配置 JobManager REST 地址")));
        }
        String metric = spec.definition().name();
        if (!supported(metric)) {
            return BackendCapability.unavailable(CapabilityCode.DATA_TYPE_NOT_IMPLEMENTED,
                    "Flink REST 未实现指标: " + metric, List.of());
        }
        if (spec.mode() == QueryMode.RANGE && !CHECKPOINT_METRICS.contains(metric)) {
            return BackendCapability.unavailable(CapabilityCode.QUERY_MODE_UNSUPPORTED,
                    "Flink REST 的 " + metric + " 只有当前快照，不支持 range", List.of());
        }
        if (!TASKMANAGER_METRICS.contains(metric) && Strings.isBlank(jobId(spec))) {
            return BackendCapability.unavailable(CapabilityCode.INVALID_ARGUMENT,
                    "缺少 ref.jobId", List.of());
        }
        if (BACKPRESSURE_METRICS.contains(metric) && Strings.isBlank(vertexId(spec))) {
            return BackendCapability.unavailable(CapabilityCode.INVALID_ARGUMENT,
                    "背压指标需要 ref.vertexId 或 labelMatchers.vertex_id", List.of());
        }
        return BackendCapability.available();
    }

    @Override
    public MetricQueryResult fetch(MetricQuerySpec spec) {
        String jobId = jobId(spec);
        String metric = spec.definition().name();
        if (CHECKPOINT_METRICS.contains(metric)) {
            return checkpoint(spec, jobId, metric);
        }
        if (CHECKPOINT_VIEW_METRICS.contains(metric)) {
            return checkpointView(jobId, metric);
        }
        if (BACKPRESSURE_METRICS.contains(metric)) {
            return backpressure(jobId, vertexId(spec), metric);
        }
        if (JOB_METRICS.contains(metric)) {
            return jobDetail(jobId, metric);
        }
        if (TASKMANAGER_METRICS.contains(metric)) {
            return taskManagers(metric);
        }
        if (VERTEX_INVENTORY_METRICS.contains(metric)) {
            return vertices(jobId, metric);
        }
        throw new BackendException(CapabilityCode.DATA_TYPE_NOT_IMPLEMENTED,
                "Flink REST 未实现指标: " + metric);
    }

    private static boolean supported(String metric) {
        return CHECKPOINT_METRICS.contains(metric) || CHECKPOINT_VIEW_METRICS.contains(metric)
                || BACKPRESSURE_METRICS.contains(metric) || JOB_METRICS.contains(metric)
                || TASKMANAGER_METRICS.contains(metric) || VERTEX_INVENTORY_METRICS.contains(metric);
    }

    // ------------------------------------------------------------------ checkpoint

    private MetricQueryResult checkpoint(MetricQuerySpec spec, String jobId, String metric) {
        JsonNode root = client.getJson("/jobs/" + Urls.encode(jobId) + "/checkpoints");
        List<Checkpoint> completed = new ArrayList<>();
        List<Checkpoint> failed = new ArrayList<>();
        JsonNode history = root.path("history");
        if (history.isArray()) {
            for (JsonNode node : history) {
                Checkpoint checkpoint = parseCheckpoint(node, null);
                if (checkpoint == null) {
                    continue;
                }
                if (checkpoint.completed()) {
                    completed.add(checkpoint);
                } else {
                    failed.add(checkpoint);
                }
            }
        }
        if (completed.isEmpty() && failed.isEmpty() && root.path("latest").path("completed").isObject()) {
            Checkpoint latest = parseCheckpoint(root.path("latest").path("completed"), true);
            if (latest != null) {
                completed.add(latest);
            }
        }
        completed.sort(Comparator.comparing(Checkpoint::timestamp));
        failed.sort(Comparator.comparing(Checkpoint::timestamp));

        List<MetricQueryResult.Sample> samples = switch (metric) {
            case "flink_checkpoint_duration_seconds" -> samples(completed, true);
            case "flink_checkpoint_size_bytes" -> samples(completed, false);
            case "flink_checkpoint_completed_total" -> rate(spec, cumulative(completed));
            default -> rate(spec, cumulative(failed));
        };
        samples = window(spec, samples);
        Map<String, String> labels = Map.of("job_id", jobId);
        List<MetricQueryResult.Series> series = samples.isEmpty() ? List.of()
                : List.of(new MetricQueryResult.Series(labels, samples));
        return new MetricQueryResult(series, false, List.of());
    }

    private Checkpoint parseCheckpoint(JsonNode node, Boolean completedOverride) {
        long timestamp = node.path("trigger_timestamp").asLong(0L);
        if (timestamp <= 0) {
            timestamp = node.path("latest_ack_timestamp").asLong(0L);
        }
        if (timestamp <= 0) {
            return null;
        }
        boolean completed = completedOverride != null
                ? completedOverride
                : "COMPLETED".equalsIgnoreCase(node.path("status").asText());
        Long duration = node.hasNonNull("duration") ? node.get("duration").asLong() : null;
        Long size = node.hasNonNull("size") ? node.get("size").asLong() : null;
        return new Checkpoint(Instant.ofEpochMilli(timestamp), completed, duration, size);
    }

    private List<MetricQueryResult.Sample> samples(List<Checkpoint> checkpoints, boolean duration) {
        List<MetricQueryResult.Sample> samples = new ArrayList<>();
        for (Checkpoint checkpoint : checkpoints) {
            Long value = duration ? checkpoint.durationMs() : checkpoint.sizeBytes();
            if (value == null) {
                continue;
            }
            double normalized = duration ? value / 1000.0 : value.doubleValue();
            samples.add(new MetricQueryResult.Sample(checkpoint.timestamp(), normalized));
        }
        return samples;
    }

    private List<MetricQueryResult.Sample> cumulative(List<Checkpoint> checkpoints) {
        List<MetricQueryResult.Sample> samples = new ArrayList<>();
        long count = 0;
        for (Checkpoint checkpoint : checkpoints) {
            count++;
            samples.add(new MetricQueryResult.Sample(checkpoint.timestamp(), count));
        }
        return samples;
    }

    /** rate 只对累计事件序列生效；样本不足两个时返回空，不补 0。 */
    private List<MetricQueryResult.Sample> rate(MetricQuerySpec spec,
                                                List<MetricQueryResult.Sample> samples) {
        if (!spec.rate()) {
            return samples;
        }
        List<MetricQueryResult.Sample> result = new ArrayList<>();
        for (int i = 1; i < samples.size(); i++) {
            MetricQueryResult.Sample previous = samples.get(i - 1);
            MetricQueryResult.Sample current = samples.get(i);
            double seconds = Duration.between(previous.timestamp(), current.timestamp())
                    .toMillis() / 1000.0;
            if (seconds <= 0) {
                continue;
            }
            result.add(new MetricQueryResult.Sample(current.timestamp(),
                    (current.value() - previous.value()) / seconds));
        }
        return result;
    }

    private List<MetricQueryResult.Sample> window(MetricQuerySpec spec,
                                                  List<MetricQueryResult.Sample> samples) {
        if (spec.mode() == QueryMode.INSTANT) {
            return samples.isEmpty() ? List.of() : List.of(samples.get(samples.size() - 1));
        }
        if (spec.timeRange() == null) {
            return samples;
        }
        return samples.stream()
                .filter(sample -> spec.timeRange().contains(sample.timestamp()))
                .toList();
    }

    // ------------------------------------------------------------------ checkpoint 状态

    private MetricQueryResult checkpointView(String jobId, String metric) {
        JsonNode root = client.getJson("/jobs/" + Urls.encode(jobId) + "/checkpoints");
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("job_id", jobId);
        Instant timestamp = Instant.now();
        if ("flink_checkpoint_in_progress".equals(metric)) {
            JsonNode value = root.path("counts").get("in_progress");
            return single(labels, timestamp, value);
        }
        if ("flink_checkpoint_restored_total".equals(metric)) {
            JsonNode value = root.path("counts").get("restored");
            return single(labels, timestamp, value);
        }
        if ("flink_checkpoint_latest_failed".equals(metric)) {
            JsonNode failed = root.path("latest").path("failed");
            if (!failed.isObject()) {
                return new MetricQueryResult(List.of(), false, List.of());
            }
            labels.put("checkpoint_id", failed.path("id").asText(""));
            labels.put("reason", Strings.truncate(textOrNull(failed, "failure_message",
                    textOrNull(failed, "failureMessage", null)), 500));
            return single(labels, timestamp, 1);
        }
        LatestCheckpoint latest = latestCheckpoint(root.path("latest"));
        if (latest == null) {
            return new MetricQueryResult(List.of(), false, List.of());
        }
        String status = textOrNull(latest.node(), "status", latest.kind().toUpperCase());
        labels.put("checkpoint_id", latest.node().path("id").asText(""));
        labels.put("status", status.toUpperCase());
        return single(labels, timestamp, 1);
    }

    /** 从 latest.{completed,failed,restored,savepoint} 中按触发时间挑最近一次。 */
    private static LatestCheckpoint latestCheckpoint(JsonNode latest) {
        LatestCheckpoint chosen = null;
        long chosenTime = -1;
        for (String key : List.of("completed", "failed", "restored", "savepoint")) {
            JsonNode item = latest.path(key);
            if (!item.isObject()) {
                continue;
            }
            long time = item.path("trigger_timestamp").asLong(0L);
            if (time <= 0) {
                time = item.path("latest_ack_timestamp").asLong(0L);
            }
            if (time > chosenTime) {
                chosen = new LatestCheckpoint(key, item);
                chosenTime = time;
            }
        }
        return chosen;
    }

    private record LatestCheckpoint(String kind, JsonNode node) {
    }

    private MetricQueryResult single(Map<String, String> labels, Instant timestamp, JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull() || !value.isNumber()) {
            return new MetricQueryResult(List.of(), false, List.of());
        }
        return single(labels, timestamp, value.asDouble());
    }

    private MetricQueryResult single(Map<String, String> labels, Instant timestamp, double value) {
        return new MetricQueryResult(List.of(new MetricQueryResult.Series(Map.copyOf(labels),
                List.of(new MetricQueryResult.Sample(timestamp, value)))), false, List.of());
    }

    // ------------------------------------------------------------------ job / TM / vertex

    private MetricQueryResult jobDetail(String jobId, String metric) {
        JsonNode job = client.getJson("/jobs/" + Urls.encode(jobId));
        // Flink 2.2 的 /jobs/{id} 没有顶层 tasks 对象，只有 status-counts 的各状态任务数
        JsonNode counts = job.path("status-counts");
        Map<String, String> labels = Map.of("job_id", jobId);
        if ("flink_tasks_failed".equals(metric)) {
            return single(labels, Instant.now(), counts.get("FAILED"));
        }
        long total = 0L;
        for (JsonNode count : counts) {
            if (count.isNumber()) {
                total += count.asLong();
            }
        }
        return single(labels, Instant.now(), DoubleNode.valueOf(total));
    }

    private MetricQueryResult taskManagers(String metric) {
        JsonNode root = client.getJson("/taskmanagers");
        List<MetricQueryResult.Series> series = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (JsonNode tm : root.path("taskmanagers")) {
            String id = tm.path("id").asText("");
            if (id.isBlank()) {
                continue;
            }
            Map<String, String> labels = new LinkedHashMap<>();
            labels.put("taskmanager_id", id);
            JsonNode value;
            switch (metric) {
                case "flink_taskmanager_info" -> {
                    labels.put("host", tmHost(tm));
                    labels.put("status", taskManagerStatus(tm, now));
                    value = DoubleNode.valueOf(1);
                }
                case "flink_taskmanager_slots_total" -> value = tm.get("slotsNumber");
                case "flink_taskmanager_slots_free" -> value = tm.get("freeSlots");
                case "flink_taskmanager_cpu_cores" ->
                        value = tm.path("totalResource").get("cpuCores");
                case "flink_taskmanager_memory_bytes" -> {
                    value = tm.path("totalResource").get("memory");
                    if (value == null || !value.isNumber()
                            || value.asDouble() <= 0) {
                        value = tm.path("hardware").get("physicalMemory");
                    }
                }
                case "flink_taskmanager_managed_memory_bytes" ->
                        value = tm.path("totalResource").get("managedMemory");
                case "flink_taskmanager_physical_memory_bytes" ->
                        value = tm.path("hardware").get("physicalMemory");
                default -> {
                    long heartbeat = tm.path("timeSinceLastHeartbeat").asLong(0L);
                    value = heartbeat <= 0 ? null : DoubleNode.valueOf(
                            Math.max(now - heartbeat, 0L) / 1000.0);
                }
            }
            MetricQueryResult result = single(labels, Instant.ofEpochMilli(now), value);
            if (!result.series().isEmpty()) {
                series.addAll(result.series());
                guardSeries(series.size());
            }
        }
        return new MetricQueryResult(series, false, List.of());
    }

    private MetricQueryResult vertices(String jobId, String metric) {
        JsonNode job = client.getJson("/jobs/" + Urls.encode(jobId));
        List<MetricQueryResult.Series> series = new ArrayList<>();
        Instant timestamp = Instant.now();
        for (JsonNode vertex : job.path("vertices")) {
            String vertexId = vertex.path("id").asText("");
            if (vertexId.isBlank()) {
                continue;
            }
            Map<String, String> labels = new LinkedHashMap<>();
            labels.put("job_id", jobId);
            labels.put("vertex_id", vertexId);
            JsonNode value;
            switch (metric) {
                case "flink_vertex_info" -> {
                    labels.put("vertex_name", vertex.path("name").asText(""));
                    labels.put("status", vertex.path("status").asText(""));
                    value = DoubleNode.valueOf(1);
                }
                case "flink_vertex_parallelism" -> value = vertex.get("parallelism");
                default -> value = vertex.path("tasks").get("FAILED");
            }
            MetricQueryResult result = single(labels, timestamp, value);
            if (!result.series().isEmpty()) {
                series.addAll(result.series());
                guardSeries(series.size());
            }
        }
        return new MetricQueryResult(series, false, List.of());
    }

    private void guardSeries(int size) {
        if (size > config.getLimits().getMaxSeries()) {
            throw new BackendException(CapabilityCode.QUERY_TOO_EXPENSIVE,
                    "序列数超过上限 " + config.getLimits().getMaxSeries());
        }
    }

    private static String tmHost(JsonNode tm) {
        String path = tm.path("path").asText("");
        int at = path.indexOf('@');
        if (at < 0) {
            return "";
        }
        String host = path.substring(at + 1);
        int slash = host.indexOf('/');
        if (slash > 0) {
            host = host.substring(0, slash);
        }
        int colon = host.lastIndexOf(':');
        return colon > 0 ? host.substring(0, colon) : host;
    }

    private static String taskManagerStatus(JsonNode tm, long now) {
        long heartbeat = tm.path("timeSinceLastHeartbeat").asLong(0L);
        return heartbeat > 0 && now - heartbeat <= HEARTBEAT_STALE_MS ? "ACTIVE" : "STALE";
    }

    private static String textOrNull(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        String text = value.asText();
        return text.isBlank() ? fallback : text;
    }

    // ------------------------------------------------------------------ 背压

    private MetricQueryResult backpressure(String jobId, String vertexId, String metric) {
        JsonNode root = client.getJson("/jobs/" + Urls.encode(jobId) + "/vertices/"
                + Urls.encode(vertexId) + "/backpressure");
        long timestamp = root.path("end-timestamp").asLong(System.currentTimeMillis());
        String field = metric.contains("busy") ? "busyRatio" : "ratio";
        List<MetricQueryResult.Series> series = new ArrayList<>();
        for (JsonNode subtask : root.path("subtasks")) {
            if (!subtask.hasNonNull(field)) {
                continue;
            }
            Map<String, String> labels = new LinkedHashMap<>();
            labels.put("job_id", jobId);
            labels.put("vertex_id", vertexId);
            labels.put("subtask", subtask.path("subtask").asText("0"));
            series.add(new MetricQueryResult.Series(labels,
                    List.of(new MetricQueryResult.Sample(Instant.ofEpochMilli(timestamp),
                            subtask.path(field).asDouble()))));
            if (series.size() > config.getLimits().getMaxSeries()) {
                throw new BackendException(CapabilityCode.QUERY_TOO_EXPENSIVE,
                        "背压序列数超过上限 " + config.getLimits().getMaxSeries());
            }
        }
        return new MetricQueryResult(series, false, List.of());
    }

    // ------------------------------------------------------------------ 辅助

    private static String jobId(MetricQuerySpec spec) {
        return spec.refs().get("jobId");
    }

    private static String vertexId(MetricQuerySpec spec) {
        String vertexId = spec.refs().get("vertexId");
        if (!Strings.isBlank(vertexId)) {
            return vertexId;
        }
        return spec.labelMatchers().get("vertex_id");
    }


    private record Checkpoint(Instant timestamp, boolean completed, Long durationMs, Long sizeBytes) {
    }
}
