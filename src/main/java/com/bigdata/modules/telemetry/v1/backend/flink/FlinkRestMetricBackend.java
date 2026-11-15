package com.bigdata.modules.telemetry.v1.backend.flink;

import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.Requirement;
import com.bigdata.modules.telemetry.model.RequirementKind;
import com.bigdata.modules.telemetry.v1.backend.engine.EngineRestClient;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties.FlinkRestConfig;
import com.bigdata.modules.telemetry.v1.model.BackendType;
import com.bigdata.modules.telemetry.v1.model.BackendWarning;
import com.bigdata.modules.telemetry.v1.model.QueryMode;
import com.bigdata.modules.telemetry.v1.spi.BackendCapability;
import com.bigdata.modules.telemetry.v1.spi.BackendException;
import com.bigdata.modules.telemetry.v1.spi.ExceptionQueryResult;
import com.bigdata.modules.telemetry.v1.spi.ExceptionQuerySpec;
import com.bigdata.modules.telemetry.v1.spi.ExceptionSummaryBackend;
import com.bigdata.modules.telemetry.v1.spi.MetricBackend;
import com.bigdata.modules.telemetry.v1.spi.MetricQueryResult;
import com.bigdata.modules.telemetry.v1.spi.MetricQuerySpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flink REST 后端：checkpoint 历史、背压与异常摘要。
 *
 * <p>证据边界：
 * <ul>
 *   <li>checkpoint 是离散事件序列，range 查询直接返回事件时间点，不做等步长插值；</li>
 *   <li>背压端点只有当前快照，因此只支持 instant，range 明确返回 QUERY_MODE_UNSUPPORTED；</li>
 *   <li>异常摘要结构化为 {@code /jobs/:jobId/exceptions} 原始结果，属于 engineEvidence，
 *       不塞进 stdout/stderr/slowquery。</li>
 * </ul>
 */
public final class FlinkRestMetricBackend implements MetricBackend, ExceptionSummaryBackend {

    private static final Set<String> CHECKPOINT_METRICS = Set.of(
            "flink_checkpoint_duration_seconds",
            "flink_checkpoint_size_bytes",
            "flink_checkpoint_completed_total",
            "flink_checkpoint_failed_total");
    private static final Set<String> BACKPRESSURE_METRICS = Set.of(
            "flink_backpressure_busy_ratio",
            "flink_backpressure_backpressured_ratio");

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
        if (isBlank(config.getBaseUrl())) {
            return BackendCapability.unavailable(CapabilityCode.NOT_CONFIGURED,
                    "未配置 Flink REST 地址",
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "telemetry.backends.flink-rest[].base-url", "配置 JobManager REST 地址")));
        }
        String metric = spec.definition().name();
        if (spec.mode() == QueryMode.RANGE && !CHECKPOINT_METRICS.contains(metric)) {
            return BackendCapability.unavailable(CapabilityCode.QUERY_MODE_UNSUPPORTED,
                    "Flink REST 的 " + metric + " 只有当前快照，不支持 range", List.of());
        }
        if (isBlank(jobId(spec))) {
            return BackendCapability.unavailable(CapabilityCode.INVALID_ARGUMENT,
                    "缺少 ref.jobId", List.of());
        }
        if (BACKPRESSURE_METRICS.contains(metric) && isBlank(vertexId(spec))) {
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
        if (BACKPRESSURE_METRICS.contains(metric)) {
            return backpressure(spec, jobId, vertexId(spec), metric);
        }
        throw new BackendException(CapabilityCode.DATA_TYPE_NOT_IMPLEMENTED,
                "Flink REST 未实现指标: " + metric);
    }

    /** 异常摘要原始响应：结构化证据，供 engineEvidence 使用，不进日志接口。 */
    public JsonNode exceptionSummary(String jobId) {
        return client.getJson(exceptionsPath(jobId));
    }

    /** 作业拓扑快照（顶点、状态与时间戳）。 */
    public JsonNode topology(String jobId) {
        return client.getJson("/jobs/" + encode(jobId));
    }

    // ------------------------------------------------------------------ 异常摘要

    @Override
    public BackendCapability evaluate(ExceptionQuerySpec spec) {
        if (isBlank(config.getBaseUrl())) {
            return BackendCapability.unavailable(CapabilityCode.NOT_CONFIGURED,
                    "未配置 Flink REST 地址",
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "telemetry.backends.flink-rest[].base-url",
                            "配置 JobManager REST 地址")));
        }
        if (isBlank(spec.jobId())) {
            return BackendCapability.unavailable(CapabilityCode.INVALID_ARGUMENT,
                    "缺少 ref.jobId", List.of());
        }
        return BackendCapability.available();
    }

    /**
     * 只查询列表接口 {@code GET /jobs/{jobId}/exceptions}。
     *
     * <p>Flink 2.2 不存在 {@code /jobs/{jobId}/exceptions/{exception}} 子资源：实测任意路径参数
     * 都返回 404，源码里的 {@code JobExceptionsHandler} 也只有一个 {@code @Get}。因此这里不保留
     * 单条详情的调用路径，避免按旧版文档加回一个必然 404 的请求。</p>
     *
     * <p>服务端通过 {@code maxExceptions} 自己限制历史条数，并以 {@code exceptionHistory.truncated}
     * 告知是否还有更多；客户端还会对并发异常条数与单条 stacktrace 长度做上限保护，并显式标记。</p>
     */
    @Override
    public ExceptionQueryResult fetch(ExceptionQuerySpec spec) {
        int maxExceptions = clamp(spec.maxExceptions(), config.getMaxExceptions());
        int maxConcurrent = clamp(spec.maxConcurrentExceptions(), config.getMaxConcurrentExceptions());
        boolean includeStackTraces = spec.includeStackTraces() == null
                || spec.includeStackTraces();
        JsonNode root = client.getJson(exceptionsPath(spec.jobId())
                + "?maxExceptions=" + maxExceptions);
        return parseHistory(root, maxExceptions, maxConcurrent, includeStackTraces);
    }

    private ExceptionQueryResult parseHistory(JsonNode root, int maxExceptions, int maxConcurrent,
                                              boolean includeStackTraces) {
        JsonNode history = root.path("exceptionHistory");
        if (!history.isObject()) {
            throw new BackendException(CapabilityCode.PARSE_ERROR,
                    "Flink 异常响应缺少 exceptionHistory（不识别旧版扁平结构）");
        }
        boolean serverTruncated = history.path("truncated").asBoolean(false);
        List<BackendWarning> warnings = new ArrayList<>();
        JsonNode entries = history.path("entries");
        int available = entries.isArray() ? entries.size() : 0;
        List<ExceptionQueryResult.ExceptionRecord> records = new ArrayList<>();
        int concurrentTotal = 0;
        boolean concurrentTruncated = false;
        boolean stackTraceTruncated = false;
        for (JsonNode node : entries) {
            if (records.size() >= maxExceptions) {
                break;
            }
            int concurrentAvailable = node.path("concurrentExceptions").size();
            concurrentTotal += concurrentAvailable;
            if (concurrentAvailable > maxConcurrent) {
                concurrentTruncated = true;
            }
            ExceptionQueryResult.ExceptionRecord record =
                    parseEntry(node, serverTruncated, maxConcurrent, includeStackTraces);
            stackTraceTruncated = stackTraceTruncated || record.stackTraceTruncated()
                    || record.concurrentExceptions().stream()
                    .anyMatch(item -> item.stackTraceTruncated());
            records.add(record);
        }
        boolean limitTruncated = available > records.size() || concurrentTruncated
                || stackTraceTruncated;
        if (serverTruncated) {
            warnings.add(BackendWarning.of("EXCEPTION_HISTORY_TRUNCATED",
                    "Flink 服务端异常历史已截断（exceptionHistory.truncated=true），未返回全部历史"));
        }
        if (available > records.size()) {
            warnings.add(BackendWarning.of("RESULT_TRUNCATED",
                    "异常条数超过上限 " + maxExceptions + "，仅返回前 " + records.size() + " 条"));
        }
        if (concurrentTruncated) {
            warnings.add(BackendWarning.of("RESULT_TRUNCATED",
                    "并发异常条数超过上限 " + maxConcurrent + "，仅保留前 " + maxConcurrent + " 条"));
        }
        if (stackTraceTruncated) {
            warnings.add(BackendWarning.of("STACKTRACE_TRUNCATED",
                    "单条 stacktrace 超过 " + config.getMaxStackTraceChars()
                            + " 字符上限，已客户端截断"));
        }
        return new ExceptionQueryResult(records, concurrentTotal, serverTruncated,
                limitTruncated, maxExceptions, warnings);
    }

    private ExceptionQueryResult.ExceptionRecord parseEntry(JsonNode node, boolean serverTruncated,
                                                            int maxConcurrent,
                                                            boolean includeStackTraces) {
        String exceptionName = textOrNull(node, "exceptionName");
        Stack stack = stackTrace(node, includeStackTraces);
        List<ExceptionQueryResult.ConcurrentExceptionRecord> concurrent = new ArrayList<>();
        for (JsonNode child : node.path("concurrentExceptions")) {
            if (concurrent.size() >= maxConcurrent) {
                break;
            }
            concurrent.add(parseConcurrent(child, includeStackTraces));
        }
        return new ExceptionQueryResult.ExceptionRecord(exceptionName,
                rootCause(stack.value(), exceptionName), stack.value(),
                instant(node.path("timestamp").asLong(0L)),
                textOrNull(node, "taskName"), textOrNull(node, "taskManagerId"),
                textOrNull(node, "endpoint"), failureLabels(node),
                serverTruncated, stack.truncated(), stack.chars(), concurrent);
    }

    private ExceptionQueryResult.ConcurrentExceptionRecord parseConcurrent(
            JsonNode node, boolean includeStackTraces) {
        String exceptionName = textOrNull(node, "exceptionName");
        Stack stack = stackTrace(node, includeStackTraces);
        return new ExceptionQueryResult.ConcurrentExceptionRecord(exceptionName,
                rootCause(stack.value(), exceptionName), stack.value(),
                instant(node.path("timestamp").asLong(0L)),
                textOrNull(node, "taskName"), textOrNull(node, "taskManagerId"),
                textOrNull(node, "endpoint"), failureLabels(node),
                stack.truncated(), stack.chars());
    }

    private Stack stackTrace(JsonNode node, boolean include) {
        if (!include) {
            return new Stack(null, false, 0);
        }
        String value = textOrNull(node, "stacktrace");
        if (value == null) {
            return new Stack(null, false, 0);
        }
        int chars = value.length();
        int cap = Math.max(config.getMaxStackTraceChars(), 1);
        if (chars > cap) {
            return new Stack(value.substring(0, cap), true, chars);
        }
        return new Stack(value, false, chars);
    }

    /** 取最后一个 {@code Caused by:} 行作为根因；没有 cause 时回退异常类名。 */
    private static String rootCause(String stackTrace, String exceptionName) {
        if (stackTrace == null) {
            return exceptionName;
        }
        String root = null;
        for (String line : stackTrace.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Caused by:")) {
                String cause = trimmed.substring("Caused by:".length()).trim();
                if (!cause.isEmpty()) {
                    root = cause;
                }
            }
        }
        return root == null ? exceptionName : root;
    }

    private static Instant instant(long epochMillis) {
        return epochMillis <= 0 ? null : Instant.ofEpochMilli(epochMillis);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private static Map<String, String> failureLabels(JsonNode node) {
        Map<String, String> labels = new LinkedHashMap<>();
        JsonNode value = node.path("failureLabels");
        if (value.isObject()) {
            value.fields().forEachRemaining(entry ->
                    labels.put(entry.getKey(), entry.getValue().asText()));
        }
        return labels;
    }

    private static int clamp(Integer requested, int configured) {
        int cap = Math.max(configured, 1);
        return requested == null ? cap : Math.max(1, Math.min(requested, cap));
    }

    private static String exceptionsPath(String jobId) {
        return "/jobs/" + encode(jobId) + "/exceptions";
    }

    private record Stack(String value, boolean truncated, int chars) {
    }

    // ------------------------------------------------------------------ checkpoint

    private MetricQueryResult checkpoint(MetricQuerySpec spec, String jobId, String metric) {
        JsonNode root = client.getJson("/jobs/" + encode(jobId) + "/checkpoints");
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
            case "flink_checkpoint_completed_total" -> cumulative(completed);
            default -> cumulative(failed);
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

    // ------------------------------------------------------------------ 背压

    private MetricQueryResult backpressure(MetricQuerySpec spec, String jobId, String vertexId,
                                           String metric) {
        JsonNode root = client.getJson("/jobs/" + encode(jobId) + "/vertices/"
                + encode(vertexId) + "/backpressure");
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
        if (!isBlank(vertexId)) {
            return vertexId;
        }
        return spec.labelMatchers().get("vertex_id");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record Checkpoint(Instant timestamp, boolean completed, Long durationMs, Long sizeBytes) {
    }
}
