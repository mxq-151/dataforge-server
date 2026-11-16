package com.bigdata.modules.telemetry.service.metricdata.catalog;

import com.bigdata.modules.telemetry.service.metricdata.model.MetricDefinition;
import static com.bigdata.modules.telemetry.service.metricdata.catalog.MetricSemanticsSupport.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;


/**
 * Spark SHS REST 指标语义：事件日志快照字段 → 语义名 + 类型 + 单位 + rate 适用性。
 *
 * <p>SHS 没有指标名列举能力，因此全部使用 {@code discovery=MANUAL} 与语义名模式；
 * 只支持 instant。counter 字段保留 counter 类型（rate 语义正确），但 SHS 不提供时间序列，
 * 查询 range / rate 会由后端明确拒绝，不伪造历史。</p>
 *
 * <p>运行中应用同样可查：SHS 会读未完成的事件日志，但数字滞后 driver live UI 20–50 秒，
 * 响应里带 {@code SPARK_APP_INCOMPLETE} 告警；task 分位只统计已完成 task。
 * local 模式没有独立 executor，driver 同时承担执行器角色，按 {@code executor_id=driver} 返回。</p>
 */
@Component
public class SparkShsMetricSemantics {

    private static final String APPLICATION_ENDPOINT = "/api/v1/applications/{id}";
    private static final String STAGE_ENDPOINT = "/api/v1/applications/{id}/stages";
    private static final String TASK_ENDPOINT =
            "/api/v1/applications/{id}/stages/{stageId}/{attemptId}/taskSummary";
    private static final String EXECUTOR_ENDPOINT = "/api/v1/applications/{id}/allexecutors";

    private static final String SHS_NOTE =
            "SHS 事件日志快照（运行中作业也可查，但滞后 live UI 20–50 秒），只支持 instant；"
                    + "不是时间序列，rate 不适用";

    public List<RestMetricSemanticsRegistry.ComponentMetricSemantics> contribute() {
        return List.of(
                new RestMetricSemanticsRegistry.ComponentMetricSemantics(
                        "spark", "spark.driver", driverMetrics()),
                new RestMetricSemanticsRegistry.ComponentMetricSemantics(
                        "spark", "spark.executor", executorMetrics()));
    }

    private List<MetricDefinition> driverMetrics() {
        List<MetricDefinition> metrics = new java.util.ArrayList<>();
        metrics.add(manualSemanticMetric("spark_application_attempt_count", "gauge", "count",
                "application", labels(label("application_id", true, "Spark 应用 ID")),
                List.of("applicationId"), sparkShsPref(), sparkShsBackends(),
                "attempts", APPLICATION_ENDPOINT, true,
                SHS_NOTE + "；取 /applications/{id}.attempts 数组长度"));

        metrics.add(manualSemanticMetric("spark_stage_input_records_total", "counter", "count",
                "stage", stageLabels(), List.of("applicationId"), sparkShsPref(), sparkShsBackends(),
                "inputRecords", STAGE_ENDPOINT, true, SHS_NOTE + "；stage 输入记录数"));
        metrics.add(manualSemanticMetric("spark_stage_output_records_total", "counter", "count",
                "stage", stageLabels(), List.of("applicationId"), sparkShsPref(), sparkShsBackends(),
                "outputRecords", STAGE_ENDPOINT, true, SHS_NOTE + "；stage 输出记录数"));
        metrics.add(manualSemanticMetric("spark_stage_shuffle_read_bytes_total", "counter", "bytes",
                "stage", stageLabels(), List.of("applicationId"), sparkShsPref(), sparkShsBackends(),
                "shuffleReadBytes", STAGE_ENDPOINT, true, SHS_NOTE + "；stage shuffle 读取字节"));
        metrics.add(manualSemanticMetric("spark_stage_shuffle_write_bytes_total", "counter", "bytes",
                "stage", stageLabels(), List.of("applicationId"), sparkShsPref(), sparkShsBackends(),
                "shuffleWriteBytes", STAGE_ENDPOINT, true, SHS_NOTE + "；stage shuffle 写入字节"));
        metrics.add(manualSemanticMetric("spark_stage_shuffle_read_records_total", "counter", "count",
                "stage", stageLabels(), List.of("applicationId"), sparkShsPref(), sparkShsBackends(),
                "shuffleReadRecords", STAGE_ENDPOINT, true, SHS_NOTE + "；stage shuffle 读取记录数"));
        metrics.add(manualSemanticMetric("spark_stage_shuffle_write_records_total", "counter", "count",
                "stage", stageLabels(), List.of("applicationId"), sparkShsPref(), sparkShsBackends(),
                "shuffleWriteRecords", STAGE_ENDPOINT, true, SHS_NOTE + "；stage shuffle 写入记录数"));
        metrics.add(manualSemanticMetric("spark_stage_memory_bytes_spilled_total", "counter", "bytes",
                "stage", stageLabels(), List.of("applicationId"), sparkShsPref(), sparkShsBackends(),
                "memoryBytesSpilled", STAGE_ENDPOINT, true, SHS_NOTE + "；stage 内存 spill 字节"));
        metrics.add(manualSemanticMetric("spark_stage_disk_bytes_spilled_total", "counter", "bytes",
                "stage", stageLabels(), List.of("applicationId"), sparkShsPref(), sparkShsBackends(),
                "diskBytesSpilled", STAGE_ENDPOINT, true, SHS_NOTE + "；stage 磁盘 spill 字节"));
        metrics.add(manualSemanticMetric("spark_stage_peak_execution_memory_bytes", "gauge", "bytes",
                "stage", stageLabels(), List.of("applicationId"), sparkShsPref(), sparkShsBackends(),
                "peakExecutionMemory", STAGE_ENDPOINT, true, SHS_NOTE + "；stage 峰值执行内存"));
        metrics.add(manualSemanticMetric("spark_stage_tasks_failed_total", "counter", "count",
                "stage", stageLabels(), List.of("applicationId"), sparkShsPref(), sparkShsBackends(),
                "numFailedTasks", STAGE_ENDPOINT, true, SHS_NOTE + "；stage 失败 task 数"));
        metrics.add(manualSemanticMetric("spark_stage_tasks_active", "gauge", "count",
                "stage", stageLabels(), List.of("applicationId"), sparkShsPref(), sparkShsBackends(),
                "numActiveTasks", STAGE_ENDPOINT, true, SHS_NOTE + "；stage 当前活跃 task 数"));
        metrics.add(manualSemanticMetric("spark_stage_tasks_complete", "gauge", "count",
                "stage", stageLabels(), List.of("applicationId"), sparkShsPref(), sparkShsBackends(),
                "numCompleteTasks", STAGE_ENDPOINT, true, SHS_NOTE + "；stage 已完成 task 数"));
        metrics.add(manualSemanticMetric("spark_stage_attempts", "gauge", "count",
                "stage", stageLabels(), List.of("applicationId"), sparkShsPref(), sparkShsBackends(),
                "attemptId", STAGE_ENDPOINT, true, SHS_NOTE + "；attempt 序号（attemptId + 1）"));
        metrics.add(manualSemanticMetric("spark_stage_executor_run_seconds_total", "counter", "seconds",
                "stage", stageLabels(), List.of("applicationId"), sparkShsPref(), sparkShsBackends(),
                "executorRunTime", STAGE_ENDPOINT, true,
                SHS_NOTE + "；SHS 返回毫秒，输出按秒换算"));

        metrics.add(manualSemanticMetric("spark_task_duration_seconds", "gauge", "seconds",
                "task", taskLabels(), List.of("applicationId", "stageId"), sparkShsPref(),
                sparkShsBackends(), "duration", TASK_ENDPOINT, true,
                SHS_NOTE + "；task 耗时分布，按 quantile 标签返回"));
        metrics.add(manualSemanticMetric("spark_task_executor_run_seconds", "gauge", "seconds",
                "task", taskLabels(), List.of("applicationId", "stageId"), sparkShsPref(),
                sparkShsBackends(), "executorRunTime", TASK_ENDPOINT, true,
                SHS_NOTE + "；SHS 返回毫秒，输出按秒换算"));
        metrics.add(manualSemanticMetric("spark_task_jvm_gc_seconds", "gauge", "seconds",
                "task", taskLabels(), List.of("applicationId", "stageId"), sparkShsPref(),
                sparkShsBackends(), "jvmGcTime", TASK_ENDPOINT, true,
                SHS_NOTE + "；SHS 返回毫秒，输出按秒换算"));
        metrics.add(manualSemanticMetric("spark_task_result_size_bytes", "gauge", "bytes",
                "task", taskLabels(), List.of("applicationId", "stageId"), sparkShsPref(),
                sparkShsBackends(), "resultSize", TASK_ENDPOINT, true, SHS_NOTE + "；task 结果大小"));
        metrics.add(manualSemanticMetric("spark_task_peak_execution_memory_bytes", "gauge", "bytes",
                "task", taskLabels(), List.of("applicationId", "stageId"), sparkShsPref(),
                sparkShsBackends(), "peakExecutionMemory", TASK_ENDPOINT, true,
                SHS_NOTE + "；task 峰值执行内存"));
        metrics.add(manualSemanticMetric("spark_task_memory_bytes_spilled", "gauge", "bytes",
                "task", taskLabels(), List.of("applicationId", "stageId"), sparkShsPref(),
                sparkShsBackends(), "memoryBytesSpilled", TASK_ENDPOINT, true,
                SHS_NOTE + "；task 内存 spill 字节"));
        metrics.add(manualSemanticMetric("spark_task_disk_bytes_spilled", "gauge", "bytes",
                "task", taskLabels(), List.of("applicationId", "stageId"), sparkShsPref(),
                sparkShsBackends(), "diskBytesSpilled", TASK_ENDPOINT, true,
                SHS_NOTE + "；task 磁盘 spill 字节"));
        metrics.add(manualSemanticMetric("spark_task_input_records", "gauge", "count",
                "task", taskLabels(), List.of("applicationId", "stageId"), sparkShsPref(),
                sparkShsBackends(), "inputMetrics.recordsRead", TASK_ENDPOINT, true,
                SHS_NOTE + "；task 输入记录数"));
        metrics.add(manualSemanticMetric("spark_task_input_bytes", "gauge", "bytes",
                "task", taskLabels(), List.of("applicationId", "stageId"), sparkShsPref(),
                sparkShsBackends(), "inputMetrics.bytesRead", TASK_ENDPOINT, true,
                SHS_NOTE + "；task 输入字节"));
        metrics.add(manualSemanticMetric("spark_task_output_records", "gauge", "count",
                "task", taskLabels(), List.of("applicationId", "stageId"), sparkShsPref(),
                sparkShsBackends(), "outputMetrics.recordsWritten", TASK_ENDPOINT, true,
                SHS_NOTE + "；task 输出记录数"));
        metrics.add(manualSemanticMetric("spark_task_output_bytes", "gauge", "bytes",
                "task", taskLabels(), List.of("applicationId", "stageId"), sparkShsPref(),
                sparkShsBackends(), "outputMetrics.bytesWritten", TASK_ENDPOINT, true,
                SHS_NOTE + "；task 输出字节"));
        metrics.add(manualSemanticMetric("spark_task_shuffle_read_bytes", "gauge", "bytes",
                "task", taskLabels(), List.of("applicationId", "stageId"), sparkShsPref(),
                sparkShsBackends(), "shuffleReadMetrics.readBytes",
                TASK_ENDPOINT, true, SHS_NOTE + "；task shuffle 读取字节"));
        metrics.add(manualSemanticMetric("spark_task_shuffle_write_bytes", "gauge", "bytes",
                "task", taskLabels(), List.of("applicationId", "stageId"), sparkShsPref(),
                sparkShsBackends(), "shuffleWriteMetrics.writeBytes", TASK_ENDPOINT, true,
                SHS_NOTE + "；task shuffle 写入字节"));
        metrics.add(manualSemanticMetric("spark_task_shuffle_read_records", "gauge", "count",
                "task", taskLabels(), List.of("applicationId", "stageId"), sparkShsPref(),
                sparkShsBackends(), "shuffleReadMetrics.readRecords", TASK_ENDPOINT, true,
                SHS_NOTE + "；task shuffle 读取记录数"));
        metrics.add(manualSemanticMetric("spark_task_shuffle_write_records", "gauge", "count",
                "task", taskLabels(), List.of("applicationId", "stageId"), sparkShsPref(),
                sparkShsBackends(), "shuffleWriteMetrics.writeRecords", TASK_ENDPOINT, true,
                SHS_NOTE + "；task shuffle 写入记录数"));
        return metrics;
    }

    private List<MetricDefinition> executorMetrics() {
        Map<String, List<String>> pref = sparkShsPref();
        return List.of(
                // SHS 的 allexecutors.memoryUsed 在历史态恒为 0（事件日志不记录周期性 executor
                // 内存），只有 peakMemoryMetrics 是真实值，因此不提供「已用内存」这个名字。
                manualSemanticMetric("spark_executor_jvm_heap_peak_bytes", "gauge", "bytes",
                        "executor", executorLabels(), List.of("applicationId"), pref,
                        sparkShsBackends(), "peakMemoryMetrics.JVMHeapMemory", EXECUTOR_ENDPOINT, true,
                        SHS_NOTE + "；stage 级别 JVM 堆峰值（历史态没有瞬时已用内存）"),
                manualSemanticMetric("spark_executor_memory_max_bytes", "gauge", "bytes",
                        "executor", executorLabels(), List.of("applicationId"), pref,
                        sparkShsBackends(), "maxMemory", EXECUTOR_ENDPOINT, true,
                        SHS_NOTE + "；executor 最大可用内存"),
                manualSemanticMetric("spark_executor_gc_seconds_total", "counter", "seconds",
                        "executor", executorLabels(), List.of("applicationId"), pref,
                        sparkShsBackends(), "totalGCTime", EXECUTOR_ENDPOINT, true,
                        SHS_NOTE + "；SHS 返回毫秒，输出按秒换算"),
                manualSemanticMetric("spark_executor_run_seconds_total", "counter", "seconds",
                        "executor", executorLabels(), List.of("applicationId"), pref,
                        sparkShsBackends(), "totalDuration", EXECUTOR_ENDPOINT, true,
                        SHS_NOTE + "；SHS 返回毫秒，输出按秒换算"),
                manualSemanticMetric("spark_executor_cores", "gauge", "count",
                        "executor", executorLabels(), List.of("applicationId"), pref,
                        sparkShsBackends(), "totalCores", EXECUTOR_ENDPOINT, true,
                        SHS_NOTE + "；executor 分配的 core 数"),
                manualSemanticMetric("spark_executor_active_tasks", "gauge", "count",
                        "executor", executorLabels(), List.of("applicationId"), pref,
                        sparkShsBackends(), "activeTasks", EXECUTOR_ENDPOINT, true,
                        SHS_NOTE + "；executor 当前活跃 task 数"),
                manualSemanticMetric("spark_executor_tasks_complete", "counter", "count",
                        "executor", executorLabels(), List.of("applicationId"), pref,
                        sparkShsBackends(), "completedTasks", EXECUTOR_ENDPOINT, true,
                        SHS_NOTE + "；executor 累计完成任务数"),
                manualSemanticMetric("spark_executor_tasks_failed_total", "counter", "count",
                        "executor", executorLabels(), List.of("applicationId"), pref,
                        sparkShsBackends(), "failedTasks", EXECUTOR_ENDPOINT, true,
                        SHS_NOTE + "；executor 累计失败任务数"),
                manualSemanticMetric("spark_executor_tasks_total", "counter", "count",
                        "executor", executorLabels(), List.of("applicationId"), pref,
                        sparkShsBackends(), "totalTasks", EXECUTOR_ENDPOINT, true,
                        SHS_NOTE + "；executor 累计任务数"),
                manualSemanticMetric("spark_executor_input_bytes_total", "counter", "bytes",
                        "executor", executorLabels(), List.of("applicationId"), pref,
                        sparkShsBackends(), "totalInputBytes", EXECUTOR_ENDPOINT, true,
                        SHS_NOTE + "；executor 累计输入字节"),
                manualSemanticMetric("spark_executor_shuffle_read_bytes_total", "counter", "bytes",
                        "executor", executorLabels(), List.of("applicationId"), pref,
                        sparkShsBackends(), "totalShuffleRead", EXECUTOR_ENDPOINT, true,
                        SHS_NOTE + "；executor 累计 shuffle 读取字节"),
                manualSemanticMetric("spark_executor_shuffle_write_bytes_total", "counter", "bytes",
                        "executor", executorLabels(), List.of("applicationId"), pref,
                        sparkShsBackends(), "totalShuffleWrite", EXECUTOR_ENDPOINT, true,
                        SHS_NOTE + "；executor 累计 shuffle 写入字节"));
    }

    private static List<MetricDefinition.LabelDefinition> stageLabels() {
        return labels(label("application_id", true, "Spark 应用 ID"),
                label("stage_id", true, "stage ID"),
                label("attempt_id", false, "stage attempt ID"));
    }

    private static List<MetricDefinition.LabelDefinition> taskLabels() {
        return labels(label("application_id", true, "Spark 应用 ID"),
                label("stage_id", true, "stage ID"),
                label("attempt_id", true, "stage attempt ID"),
                label("quantile", true, "task 指标分位点"));
    }
}
