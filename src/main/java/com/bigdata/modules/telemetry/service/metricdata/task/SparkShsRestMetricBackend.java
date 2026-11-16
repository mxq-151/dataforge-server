package com.bigdata.modules.telemetry.service.metricdata.task;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.Requirement;
import com.bigdata.modules.telemetry.common.model.RequirementKind;
import com.bigdata.modules.telemetry.common.http.EngineRestClient;
import com.bigdata.modules.telemetry.common.model.BackendType;
import com.bigdata.modules.telemetry.common.model.BackendWarning;
import com.bigdata.modules.telemetry.common.model.QueryMode;
import com.bigdata.modules.telemetry.common.model.BackendCapability;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.bigdata.modules.telemetry.service.metricdata.spi.MetricBackend;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricQueryResult;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricQuerySpec;
import com.bigdata.modules.telemetry.common.http.SparkShsAttempts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.bigdata.modules.telemetry.config.properties.SparkShsRestConfig;
import com.bigdata.modules.telemetry.util.Urls;
import com.bigdata.modules.telemetry.util.JsonNodes;
import com.bigdata.modules.telemetry.util.Strings;

/**
 * Spark SHS REST 指标后端：把 completion / stage / task / executor 字段暴露成指标。
 *
 * <p>证据边界：
 * <ul>
 *   <li>SHS 是事件日志的即时快照，不是时间序列；所有指标只支持 instant，range 返回
 *       QUERY_MODE_UNSUPPORTED；</li>
 *   <li>运行中应用同样可查（SHS 会读未完成的事件日志），但数字是滞后快照：实测 stage 进度
 *       落后 driver live UI 20–50 秒，stage 状态与 active task 数可能过期，响应里用
 *       {@code SPARK_APP_INCOMPLETE} 告警标注；task 分位只统计已完成 task；</li>
 *   <li>字段类型能判定的按 counter / gauge 声明；无法判定的不塞进本后端，避免猜测；</li>
 *   <li>stage / task / executor 样本都带 application_id 等标签，task 级额外带 stage_id /
 *       attempt_id / quantile；</li>
 *   <li>local 模式没有独立 executor，driver 同时承担执行器角色，按 {@code executor_id=driver}
 *       返回；YARN 模式下会同时出现 driver 与真实 executor；</li>
 *   <li>SHS 的 {@code /logs} 是事件日志 zip，不是文本日志，本后端不提供日志。</li>
 * </ul>
 */
public final class SparkShsRestMetricBackend implements MetricBackend {

    private static final String APPLICATION = "spark_application_attempt_count";
    private static final String TASK_QUANTILES = "0.05,0.25,0.5,0.75,0.95";
    private static final String RUNNING_WARNING = "SPARK_APP_INCOMPLETE";

    private static final Map<String, FieldSpec> APPLICATION_METRICS = Map.of(
            APPLICATION, new FieldSpec(List.of("attempts"), 1.0, 0.0));

    private static final Map<String, FieldSpec> STAGE_METRICS = Map.ofEntries(
            Map.entry("spark_stage_input_records_total",
                    new FieldSpec(List.of("inputRecords"), 1.0, 0.0)),
            Map.entry("spark_stage_output_records_total",
                    new FieldSpec(List.of("outputRecords"), 1.0, 0.0)),
            Map.entry("spark_stage_shuffle_read_bytes_total",
                    new FieldSpec(List.of("shuffleReadBytes"), 1.0, 0.0)),
            Map.entry("spark_stage_shuffle_write_bytes_total",
                    new FieldSpec(List.of("shuffleWriteBytes"), 1.0, 0.0)),
            Map.entry("spark_stage_shuffle_read_records_total",
                    new FieldSpec(List.of("shuffleReadRecords"), 1.0, 0.0)),
            Map.entry("spark_stage_shuffle_write_records_total",
                    new FieldSpec(List.of("shuffleWriteRecords"), 1.0, 0.0)),
            Map.entry("spark_stage_memory_bytes_spilled_total",
                    new FieldSpec(List.of("memoryBytesSpilled"), 1.0, 0.0)),
            Map.entry("spark_stage_disk_bytes_spilled_total",
                    new FieldSpec(List.of("diskBytesSpilled"), 1.0, 0.0)),
            Map.entry("spark_stage_peak_execution_memory_bytes",
                    new FieldSpec(List.of("peakExecutionMemory"), 1.0, 0.0)),
            Map.entry("spark_stage_tasks_failed_total",
                    new FieldSpec(List.of("numFailedTasks"), 1.0, 0.0)),
            Map.entry("spark_stage_tasks_active",
                    new FieldSpec(List.of("numActiveTasks"), 1.0, 0.0)),
            Map.entry("spark_stage_tasks_complete",
                    new FieldSpec(List.of("numCompleteTasks"), 1.0, 0.0)),
            Map.entry("spark_stage_attempts",
                    new FieldSpec(List.of("attemptId"), 1.0, 1.0)),
            Map.entry("spark_stage_executor_run_seconds_total",
                    new FieldSpec(List.of("executorRunTime"), 1000.0, 0.0)));

    private static final Map<String, FieldSpec> EXECUTOR_METRICS = Map.ofEntries(
            // 历史态没有瞬时 memoryUsed（SHS 不记录周期性 executor 内存，该字段恒 0），
            // 只有 stage 级峰值；需要瞬时值请用 driver live UI 或 jmx_exporter。
            Map.entry("spark_executor_jvm_heap_peak_bytes",
                    new FieldSpec(List.of("peakMemoryMetrics.JVMHeapMemory"), 1.0, 0.0)),
            Map.entry("spark_executor_memory_max_bytes",
                    new FieldSpec(List.of("maxMemory"), 1.0, 0.0)),
            Map.entry("spark_executor_gc_seconds_total",
                    new FieldSpec(List.of("totalGCTime"), 1000.0, 0.0)),
            Map.entry("spark_executor_run_seconds_total",
                    new FieldSpec(List.of("totalDuration"), 1000.0, 0.0)),
            Map.entry("spark_executor_cores",
                    new FieldSpec(List.of("totalCores"), 1.0, 0.0)),
            Map.entry("spark_executor_active_tasks",
                    new FieldSpec(List.of("activeTasks"), 1.0, 0.0)),
            Map.entry("spark_executor_tasks_complete",
                    new FieldSpec(List.of("completedTasks"), 1.0, 0.0)),
            Map.entry("spark_executor_tasks_failed_total",
                    new FieldSpec(List.of("failedTasks"), 1.0, 0.0)),
            Map.entry("spark_executor_tasks_total",
                    new FieldSpec(List.of("totalTasks"), 1.0, 0.0)),
            Map.entry("spark_executor_input_bytes_total",
                    new FieldSpec(List.of("totalInputBytes"), 1.0, 0.0)),
            Map.entry("spark_executor_shuffle_read_bytes_total",
                    new FieldSpec(List.of("totalShuffleRead"), 1.0, 0.0)),
            Map.entry("spark_executor_shuffle_write_bytes_total",
                    new FieldSpec(List.of("totalShuffleWrite"), 1.0, 0.0)));

    private static final Map<String, FieldSpec> TASK_METRICS = Map.ofEntries(
            Map.entry("spark_task_duration_seconds",
                    new FieldSpec(List.of("duration"), 1000.0, 0.0)),
            Map.entry("spark_task_executor_run_seconds",
                    new FieldSpec(List.of("executorRunTime"), 1000.0, 0.0)),
            Map.entry("spark_task_jvm_gc_seconds",
                    new FieldSpec(List.of("jvmGcTime"), 1000.0, 0.0)),
            Map.entry("spark_task_result_size_bytes",
                    new FieldSpec(List.of("resultSize"), 1.0, 0.0)),
            Map.entry("spark_task_peak_execution_memory_bytes",
                    new FieldSpec(List.of("peakExecutionMemory"), 1.0, 0.0)),
            Map.entry("spark_task_memory_bytes_spilled",
                    new FieldSpec(List.of("memoryBytesSpilled"), 1.0, 0.0)),
            Map.entry("spark_task_disk_bytes_spilled",
                    new FieldSpec(List.of("diskBytesSpilled"), 1.0, 0.0)),
            Map.entry("spark_task_input_records",
                    new FieldSpec(List.of("inputMetrics.recordsRead"), 1.0, 0.0)),
            Map.entry("spark_task_input_bytes",
                    new FieldSpec(List.of("inputMetrics.bytesRead"), 1.0, 0.0)),
            Map.entry("spark_task_output_records",
                    new FieldSpec(List.of("outputMetrics.recordsWritten"), 1.0, 0.0)),
            Map.entry("spark_task_output_bytes",
                    new FieldSpec(List.of("outputMetrics.bytesWritten"), 1.0, 0.0)),
            Map.entry("spark_task_shuffle_read_bytes",
                    new FieldSpec(List.of("shuffleReadMetrics.readBytes"), 1.0, 0.0)),
            Map.entry("spark_task_shuffle_write_bytes",
                    new FieldSpec(List.of("shuffleWriteMetrics.writeBytes"), 1.0, 0.0)),
            Map.entry("spark_task_shuffle_read_records",
                    new FieldSpec(List.of("shuffleReadMetrics.readRecords"), 1.0, 0.0)),
            Map.entry("spark_task_shuffle_write_records",
                    new FieldSpec(List.of("shuffleWriteMetrics.writeRecords"), 1.0, 0.0)));

    private final String backendId;
    private final SparkShsRestConfig config;
    private final EngineRestClient client;

    public SparkShsRestMetricBackend(String backendId, SparkShsRestConfig config,
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
        return BackendType.SPARK_SHS_REST;
    }

    @Override
    public BackendCapability evaluate(MetricQuerySpec spec) {
        if (Strings.isBlank(config.getBaseUrl())) {
            return BackendCapability.unavailable(CapabilityCode.NOT_CONFIGURED,
                    "未配置 Spark SHS REST 地址",
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "telemetry.backends.spark-shs-rest[].base-url",
                            "配置 Spark History Server 地址")));
        }
        if (spec.mode() != QueryMode.INSTANT) {
            return BackendCapability.unavailable(CapabilityCode.QUERY_MODE_UNSUPPORTED,
                    "Spark SHS 是完成态快照，只支持 instant，不支持 range；"
                            + "时间序列请使用 VictoriaMetrics 抓取的 spark.shs JVM 指标", List.of());
        }
        if (spec.rate()) {
            return BackendCapability.unavailable(CapabilityCode.INVALID_ARGUMENT,
                    "Spark SHS 完成态快照不支持 rate", List.of());
        }
        if (Strings.isBlank(applicationId(spec))) {
            return BackendCapability.unavailable(CapabilityCode.INVALID_ARGUMENT,
                    "缺少 ref.applicationId", List.of());
        }
        String metric = spec.definition().name();
        if (!APPLICATION_METRICS.containsKey(metric) && !STAGE_METRICS.containsKey(metric)
                && !EXECUTOR_METRICS.containsKey(metric) && !TASK_METRICS.containsKey(metric)) {
            return BackendCapability.unavailable(CapabilityCode.DATA_TYPE_NOT_IMPLEMENTED,
                    "Spark SHS REST 未实现指标: " + metric, List.of());
        }
        if (TASK_METRICS.containsKey(metric) && Strings.isBlank(stageId(spec))) {
            return BackendCapability.unavailable(CapabilityCode.INVALID_ARGUMENT,
                    "任务级指标需要 ref.stageId", List.of());
        }
        return BackendCapability.available();
    }

    @Override
    public MetricQueryResult fetch(MetricQuerySpec spec) {
        String applicationId = applicationId(spec);
        String metric = spec.definition().name();
        if (APPLICATION_METRICS.containsKey(metric)) {
            return application(applicationId, APPLICATION_METRICS.get(metric));
        }
        FieldSpec stageField = STAGE_METRICS.get(metric);
        if (stageField != null) {
            return stages(spec, applicationId, stageField);
        }
        FieldSpec executorField = EXECUTOR_METRICS.get(metric);
        if (executorField != null) {
            return executors(spec, applicationId, executorField);
        }
        FieldSpec taskField = TASK_METRICS.get(metric);
        if (taskField != null) {
            return taskSummary(spec, applicationId, taskField);
        }
        throw new BackendException(CapabilityCode.DATA_TYPE_NOT_IMPLEMENTED,
                "Spark SHS REST 未实现指标: " + metric);
    }

    // ------------------------------------------------------------------ application

    private MetricQueryResult application(String applicationId, FieldSpec field) {
        JsonNode root = client.getJson("/api/v1/applications/" + Urls.encode(applicationId));
        JsonNode value = JsonNodes.path(root, field.paths().get(0));
        int attempts = value.isArray() ? value.size() : 0;
        Map<String, String> labels = Map.of("application_id", applicationId);
        List<MetricQueryResult.Sample> samples = List.of(
                new MetricQueryResult.Sample(Instant.now(),
                        (attempts + field.offset()) / field.divisor()));
        return new MetricQueryResult(List.of(new MetricQueryResult.Series(labels, samples)),
                false, List.of());
    }

    // ------------------------------------------------------------------ stage / executor

    private MetricQueryResult stages(MetricQuerySpec spec, String applicationId, FieldSpec field) {
        SparkShsAttempts.AttemptRef attempt = SparkShsAttempts.load(client, applicationId);
        JsonNode root = client.getJson("/api/v1/applications/" + Urls.encode(applicationId)
                + attempt.segment() + "/stages");
        if (!root.isArray()) {
            throw new BackendException(CapabilityCode.PARSE_ERROR, "SHS stages 响应不是数组");
        }
        String stageFilter = firstNonBlank(spec.refs().get("stageId"),
                spec.labelMatchers().get("stage_id"));
        String attemptFilter = firstNonBlank(spec.refs().get("attemptId"),
                spec.labelMatchers().get("attempt_id"));
        List<MetricQueryResult.Series> series = new ArrayList<>();
        for (JsonNode stage : root) {
            if (stageFilter != null && !stageFilter.equals(stage.path("stageId").asText())) {
                continue;
            }
            if (attemptFilter != null && !attemptFilter.equals(stage.path("attemptId").asText())) {
                continue;
            }
            Double value = value(stage, field);
            if (value == null) {
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
                    List.of(new MetricQueryResult.Sample(timestamp, value))));
            guardSeries(series.size());
        }
        return new MetricQueryResult(series, false, warnings(applicationId, attempt));
    }

    private MetricQueryResult executors(MetricQuerySpec spec, String applicationId, FieldSpec field) {
        SparkShsAttempts.AttemptRef attempt = SparkShsAttempts.load(client, applicationId);
        String path = "live".equalsIgnoreCase(config.getMode()) ? "/executors" : "/allexecutors";
        JsonNode root = client.getJson("/api/v1/applications/" + Urls.encode(applicationId)
                + attempt.segment() + path);
        if (!root.isArray()) {
            throw new BackendException(CapabilityCode.PARSE_ERROR, "SHS executors 响应不是数组");
        }
        String executorFilter = firstNonBlank(spec.refs().get("executorId"),
                spec.labelMatchers().get("executor_id"));
        List<MetricQueryResult.Series> series = new ArrayList<>();
        for (JsonNode executor : root) {
            String executorId = executor.path("id").asText(executor.path("executorId").asText(""));
            if (executorId.isBlank()) {
                continue;
            }
            if (executorFilter != null && !executorFilter.equals(executorId)) {
                continue;
            }
            Double value = value(executor, field);
            if (value == null) {
                continue;
            }
            Map<String, String> labels = new LinkedHashMap<>();
            labels.put("application_id", applicationId);
            labels.put("executor_id", executorId);
            series.add(new MetricQueryResult.Series(labels,
                    List.of(new MetricQueryResult.Sample(Instant.now(), value))));
            guardSeries(series.size());
        }
        return new MetricQueryResult(series, false, warnings(applicationId, attempt));
    }

    // ------------------------------------------------------------------ task summary

    private MetricQueryResult taskSummary(MetricQuerySpec spec, String applicationId,
                                          FieldSpec field) {
        String stageId = stageId(spec);
        SparkShsAttempts.AttemptRef attempt = SparkShsAttempts.load(client, applicationId);
        String attemptId = firstNonBlank(spec.refs().get("attemptId"),
                spec.labelMatchers().get("attempt_id"));
        final String resolvedAttemptId = attemptId == null
                ? latestAttempt(applicationId, stageId, attempt.segment()) : attemptId;
        String path = "/api/v1/applications/" + Urls.encode(applicationId)
                + attempt.segment() + "/stages/" + Urls.encode(stageId) + "/"
                + Urls.encode(resolvedAttemptId)
                + "/taskSummary?quantiles=" + Urls.encode(TASK_QUANTILES);
        JsonNode root = client.getJson(path);
        List<QuantileSample> points = quantileSamples(root, field);
        String quantileFilter = spec.labelMatchers().get("quantile");
        List<MetricQueryResult.Series> series = new ArrayList<>();
        for (QuantileSample point : points) {
            if (quantileFilter != null && !quantileFilter.equals(point.quantile())) {
                continue;
            }
            Map<String, String> labels = new LinkedHashMap<>();
            labels.put("application_id", applicationId);
            labels.put("stage_id", stageId);
            labels.put("attempt_id", resolvedAttemptId);
            labels.put("quantile", point.quantile());
            series.add(new MetricQueryResult.Series(labels,
                    List.of(new MetricQueryResult.Sample(Instant.now(), point.value()))));
            guardSeries(series.size());
        }
        series.sort((left, right) -> Double.compare(
                Double.parseDouble(left.labels().get("quantile")),
                Double.parseDouble(right.labels().get("quantile"))));
        return new MetricQueryResult(series, false, warnings(applicationId, attempt));
    }

    /**
     * 解析 SHS taskSummary 的两种返回形状（实测 3.5.9 是第一种）：
     *
     * <ul>
     *   <li>{@code {"quantiles":[0.05,0.5,...], "duration":[...]}}：分位数组与字段数组按下标
     *       一一对应；单分位请求时字段退化为标量；</li>
     *   <li>{@code {"0.5":{...}, "0.9":{...}}}：以分位为 key 的对象形态（早期 fixture 的写法）。</li>
     * </ul>
     *
     * <p>解析不到任何分位时返回空列表，由上层给出 {@code resultState=empty}，不伪造 0 值。</p>
     */
    private static List<QuantileSample> quantileSamples(JsonNode root, FieldSpec field) {
        List<QuantileSample> points = new ArrayList<>();
        JsonNode quantiles = root.path("quantiles");
        String fieldPath = field.paths().get(0);
        if (quantiles.isArray()) {
            JsonNode values = JsonNodes.path(root, fieldPath);
            for (int i = 0; i < quantiles.size(); i++) {
                JsonNode node = values != null && values.isArray() ? values.get(i) : values;
                Double value = JsonNodes.numeric(node);
                if (value != null) {
                    points.add(sample(quantiles.get(i).asDouble(), value, field));
                }
            }
            return points;
        }
        if (quantiles.isNumber()) {
            Double value = JsonNodes.numeric(JsonNodes.path(root, fieldPath));
            if (value != null) {
                points.add(sample(quantiles.asDouble(), value, field));
            }
            return points;
        }
        var entries = root.fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (!entry.getValue().isObject()) {
                continue;
            }
            double quantile;
            try {
                quantile = Double.parseDouble(entry.getKey());
            } catch (NumberFormatException ignored) {
                continue;
            }
            Double value = JsonNodes.numeric(JsonNodes.path(entry.getValue(), fieldPath));
            if (value != null) {
                points.add(sample(quantile, value, field));
            }
        }
        return points;
    }

    private static QuantileSample sample(double quantile, double value, FieldSpec field) {
        return new QuantileSample(quantile, formatQuantile(quantile),
                value / field.divisor() + field.offset());
    }

    /** 0.50 → "0.5"，1.0E-4 → "0.0001"；避免把浮点尾数写进标签。 */
    private static String formatQuantile(double quantile) {
        return java.math.BigDecimal.valueOf(quantile).stripTrailingZeros().toPlainString();
    }


    private String latestAttempt(String applicationId, String stageId, String attemptSegment) {
        JsonNode root = client.getJson("/api/v1/applications/" + Urls.encode(applicationId)
                + attemptSegment + "/stages");
        if (!root.isArray()) {
            throw new BackendException(CapabilityCode.PARSE_ERROR, "SHS stages 响应不是数组");
        }
        int latest = -1;
        for (JsonNode stage : root) {
            if (!stageId.equals(stage.path("stageId").asText())) {
                continue;
            }
            latest = Math.max(latest, stage.path("attemptId").asInt(0));
        }
        if (latest < 0) {
            throw new BackendException(CapabilityCode.NOT_FOUND,
                    "SHS 中未找到 stageId=" + stageId + " 的 attempt");
        }
        return String.valueOf(latest);
    }

    // ------------------------------------------------------------------ 辅助

    private void guardSeries(int size) {
        if (size > config.getLimits().getMaxSeries()) {
            throw new BackendException(CapabilityCode.QUERY_TOO_EXPENSIVE,
                    "序列数超过上限 " + config.getLimits().getMaxSeries());
        }
    }

    /**
     * 运行中应用的数据来自未完成的事件日志，必须随响应返回滞后告警，
     * 不能把过期快照当终值。live 模式直连 driver UI，是实时值，不加告警。
     */
    private List<BackendWarning> warnings(String applicationId,
                                          SparkShsAttempts.AttemptRef attempt) {
        if (attempt.completed() || "live".equalsIgnoreCase(config.getMode())) {
            return List.of();
        }
        String updated = attempt.lastUpdated() == null ? ""
                : "；SHS 最后一次解析到的日志时间 " + attempt.lastUpdated();
        return List.of(BackendWarning.of(RUNNING_WARNING,
                "应用 " + applicationId + " 仍在运行：SHS 读的是未完成事件日志，数字是滞后快照"
                        + "（实测 stage 进度落后 driver live UI 20–50 秒）" + updated
                        + "；task 分位只统计已完成 task，需要实时值请查 driver live UI"));
    }

    private static Double value(JsonNode node, FieldSpec field) {
        double sum = 0;
        boolean found = false;
        for (String path : field.paths()) {
            JsonNode value = JsonNodes.path(node, path);
            if (value == null || value.isMissingNode() || value.isNull()) {
                continue;
            }
            if (value.isNumber()) {
                sum += value.asDouble();
                found = true;
            } else if (value.isArray()) {
                // attempts 数组按长度计数
                sum += value.size();
                found = true;
            } else if (value.isBoolean()) {
                sum += value.asBoolean() ? 1.0 : 0.0;
                found = true;
            }
        }
        if (!found) {
            return null;
        }
        return (sum / field.divisor()) + field.offset();
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

    private static String stageId(MetricQuerySpec spec) {
        return firstNonBlank(spec.refs().get("stageId"), spec.labelMatchers().get("stage_id"));
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }


    private record FieldSpec(List<String> paths, double divisor, double offset) {
    }

    private record QuantileSample(double numericQuantile, String quantile, double value) {
    }
}
