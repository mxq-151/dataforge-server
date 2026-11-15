package com.bigdata.modules.telemetry.v1.backend.spark;

import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.Requirement;
import com.bigdata.modules.telemetry.model.RequirementKind;
import com.bigdata.modules.telemetry.v1.backend.engine.EngineRestClient;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties.SparkRestConfig;
import com.bigdata.modules.telemetry.v1.model.BackendType;
import com.bigdata.modules.telemetry.v1.model.QueryMode;
import com.bigdata.modules.telemetry.v1.spi.BackendCapability;
import com.bigdata.modules.telemetry.v1.spi.BackendException;
import com.bigdata.modules.telemetry.v1.spi.MetricBackend;
import com.bigdata.modules.telemetry.v1.spi.MetricQueryResult;
import com.bigdata.modules.telemetry.v1.spi.MetricQuerySpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spark REST 后端：SHS（history）或 live UI 的完成态快照。
 *
 * <p>证据边界：
 * <ul>
 *   <li>stage / executor 指标只有当前快照，只支持 instant；range 明确返回 QUERY_MODE_UNSUPPORTED；</li>
 *   <li>SHS 的 {@code /logs} 是事件日志 zip，不是文本日志，因此本后端不提供 stdout/stderr，
 *       日志接口也不会把 {@code spark-rest} 当成日志后端；</li>
 *   <li>拓扑（jobs → stageIds）通过 {@link #topology(String)} 返回结构化快照。</li>
 * </ul>
 */
public final class SparkRestMetricBackend implements MetricBackend {

    private static final Map<String, FieldSpec> STAGE_METRICS = Map.of(
            "spark_stage_input_records_total", new FieldSpec("inputRecords", 1.0),
            "spark_stage_output_records_total", new FieldSpec("outputRecords", 1.0),
            "spark_stage_shuffle_read_bytes_total", new FieldSpec("shuffleReadBytes", 1.0),
            "spark_stage_shuffle_write_bytes_total", new FieldSpec("shuffleWriteBytes", 1.0),
            "spark_stage_tasks_failed_total", new FieldSpec("numFailedTasks", 1.0));

    private static final Map<String, FieldSpec> EXECUTOR_METRICS = Map.of(
            "spark_executor_memory_used_bytes", new FieldSpec("memoryUsed", 1.0),
            "spark_executor_memory_max_bytes", new FieldSpec("maxMemory", 1.0),
            "spark_executor_gc_seconds_total", new FieldSpec("totalGCTime", 1000.0),
            "spark_executor_run_seconds_total", new FieldSpec("totalDuration", 1000.0),
            "spark_executor_cores", new FieldSpec("totalCores", 1.0),
            "spark_executor_active_tasks", new FieldSpec("activeTasks", 1.0),
            "spark_executor_input_bytes_total", new FieldSpec("totalInputBytes", 1.0),
            "spark_executor_tasks_failed_total", new FieldSpec("failedTasks", 1.0));

    private final String backendId;
    private final SparkRestConfig config;
    private final EngineRestClient client;

    public SparkRestMetricBackend(String backendId, SparkRestConfig config,
                                  String authorizationHeader, ObjectMapper objectMapper) {
        this.backendId = backendId;
        this.config = config;
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
    public BackendCapability evaluate(MetricQuerySpec spec) {
        if (isBlank(config.getBaseUrl())) {
            return BackendCapability.unavailable(CapabilityCode.NOT_CONFIGURED,
                    "未配置 Spark REST 地址",
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "telemetry.backends.spark-rest[].base-url",
                            "配置 SHS 或 live UI 地址")));
        }
        if (spec.mode() != QueryMode.INSTANT) {
            return BackendCapability.unavailable(CapabilityCode.QUERY_MODE_UNSUPPORTED,
                    "Spark SHS 是完成态快照，只支持 instant，不支持 range", List.of());
        }
        if (isBlank(applicationId(spec))) {
            return BackendCapability.unavailable(CapabilityCode.INVALID_ARGUMENT,
                    "缺少 ref.applicationId", List.of());
        }
        String metric = spec.definition().name();
        if (!STAGE_METRICS.containsKey(metric) && !EXECUTOR_METRICS.containsKey(metric)) {
            return BackendCapability.unavailable(CapabilityCode.DATA_TYPE_NOT_IMPLEMENTED,
                    "Spark REST 未实现指标: " + metric, List.of());
        }
        return BackendCapability.available();
    }

    @Override
    public MetricQueryResult fetch(MetricQuerySpec spec) {
        String applicationId = applicationId(spec);
        String metric = spec.definition().name();
        FieldSpec stageField = STAGE_METRICS.get(metric);
        if (stageField != null) {
            return stages(spec, applicationId, stageField);
        }
        FieldSpec executorField = EXECUTOR_METRICS.get(metric);
        if (executorField != null) {
            return executors(spec, applicationId, executorField);
        }
        throw new BackendException(CapabilityCode.DATA_TYPE_NOT_IMPLEMENTED,
                "Spark REST 未实现指标: " + metric);
    }

    /** 作业拓扑快照：jobs 列表 + stageIds，供诊断侧还原 DAG。 */
    public JsonNode topology(String applicationId) {
        return client.getJson("/api/v1/applications/" + encode(applicationId) + "/jobs");
    }

    // ------------------------------------------------------------------ stage / executor

    private MetricQueryResult stages(MetricQuerySpec spec, String applicationId, FieldSpec field) {
        JsonNode root = client.getJson("/api/v1/applications/" + encode(applicationId) + "/stages");
        if (!root.isArray()) {
            throw new BackendException(CapabilityCode.PARSE_ERROR, "SHS stages 响应不是数组");
        }
        String stageFilter = spec.labelMatchers().get("stage_id");
        String attemptFilter = spec.labelMatchers().get("attempt_id");
        List<MetricQueryResult.Series> series = new ArrayList<>();
        for (JsonNode stage : root) {
            if (stageFilter != null && !stageFilter.equals(stage.path("stageId").asText())) {
                continue;
            }
            if (attemptFilter != null && !attemptFilter.equals(stage.path("attemptId").asText())) {
                continue;
            }
            if (!stage.hasNonNull(field.field())) {
                continue;
            }
            Map<String, String> labels = new LinkedHashMap<>();
            labels.put("application_id", applicationId);
            labels.put("stage_id", stage.path("stageId").asText(""));
            if (stage.hasNonNull("attemptId")) {
                labels.put("attempt_id", stage.path("attemptId").asText());
            }
            Instant timestamp = firstInstant(stage, "completionTime", "submissionTime");
            series.add(new MetricQueryResult.Series(labels,
                    List.of(new MetricQueryResult.Sample(timestamp, value(stage, field)))));
            guardSeries(series.size());
        }
        return new MetricQueryResult(series, false, List.of());
    }

    private MetricQueryResult executors(MetricQuerySpec spec, String applicationId, FieldSpec field) {
        String path = "live".equalsIgnoreCase(config.getMode()) ? "/executors" : "/allexecutors";
        JsonNode root = client.getJson("/api/v1/applications/" + encode(applicationId) + path);
        if (!root.isArray()) {
            throw new BackendException(CapabilityCode.PARSE_ERROR, "SHS executors 响应不是数组");
        }
        String executorFilter = spec.labelMatchers().get("executor_id");
        List<MetricQueryResult.Series> series = new ArrayList<>();
        for (JsonNode executor : root) {
            String executorId = executor.path("id").asText(executor.path("executorId").asText(""));
            if (executorId.isBlank() || executorId.equals("driver")) {
                continue;
            }
            if (executorFilter != null && !executorFilter.equals(executorId)) {
                continue;
            }
            if (!executor.hasNonNull(field.field())) {
                continue;
            }
            Map<String, String> labels = new LinkedHashMap<>();
            labels.put("application_id", applicationId);
            labels.put("executor_id", executorId);
            series.add(new MetricQueryResult.Series(labels,
                    List.of(new MetricQueryResult.Sample(Instant.now(), value(executor, field)))));
            guardSeries(series.size());
        }
        return new MetricQueryResult(series, false, List.of());
    }

    private void guardSeries(int size) {
        if (size > config.getLimits().getMaxSeries()) {
            throw new BackendException(CapabilityCode.QUERY_TOO_EXPENSIVE,
                    "序列数超过上限 " + config.getLimits().getMaxSeries());
        }
    }

    private static double value(JsonNode node, FieldSpec field) {
        return node.path(field.field()).asDouble() / field.divisor();
    }

    private static Instant firstInstant(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("");
            if (value.isBlank()) {
                continue;
            }
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException ignored) {
                // 尝试下一个字段
            }
        }
        return Instant.now();
    }

    private static String applicationId(MetricQuerySpec spec) {
        return spec.refs().get("applicationId");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record FieldSpec(String field, double divisor) {
    }
}
