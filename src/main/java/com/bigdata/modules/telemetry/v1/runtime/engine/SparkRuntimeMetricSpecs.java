package com.bigdata.modules.telemetry.v1.runtime.engine;

import com.bigdata.modules.telemetry.v1.model.MetricDefinition;

import java.util.List;

/**
 * Spark 作业级运行时快照的字段映射（仅供 {@link SparkRuntimeEngine} 使用）。
 *
 * <p>这些定义不出现在指标目录里，也不参与 {@code metrics/query} 的发现：SHS 只读事件日志，
 * 不创建 SparkContext，因此没有 driver/executor 的运行时时间序列。这里只把 SHS REST
 * 完成态快照里的字段映射成 {@code /api/runtime/**} 可返回的指标；作业级时间序列仍是缺口。</p>
 */
final class SparkRuntimeMetricSpecs {

    static final String EXECUTOR_ENDPOINT = "/api/v1/applications/{id}/allexecutors";
    static final String STAGE_ENDPOINT = "/api/v1/applications/{id}/stages";
    static final String JOB_ENDPOINT = "/api/v1/applications/{id}/jobs";
    static final String APPLICATION_ENDPOINT = "/api/v1/applications/{id}";

    static final List<MetricDefinition> DEFINITIONS = List.of(
            metric("spark_application_attempt_count", "gauge", "count", "application",
                    "attempts", APPLICATION_ENDPOINT, "SHS /applications/{id} 的 attempts 数组长度"),
            metric("spark_executor_memory_used_bytes", "gauge", "bytes", "executor",
                    "memoryUsed", EXECUTOR_ENDPOINT, "executor 当前已用内存"),
            metric("spark_executor_memory_max_bytes", "gauge", "bytes", "executor",
                    "maxMemory", EXECUTOR_ENDPOINT, "executor 最大可用内存"),
            metric("spark_executor_gc_seconds_total", "counter", "seconds", "executor",
                    "totalGCTime", EXECUTOR_ENDPOINT, "executor 累计 GC 时间（SHS 返回毫秒，按 1000 换算）"),
            metric("spark_executor_run_seconds_total", "counter", "seconds", "executor",
                    "totalDuration", EXECUTOR_ENDPOINT, "executor 累计运行时间（SHS 返回毫秒，按 1000 换算）"),
            metric("spark_executor_cores", "gauge", "count", "executor",
                    "totalCores", EXECUTOR_ENDPOINT, "executor 分配的 core 数"),
            metric("spark_executor_active_tasks", "gauge", "count", "executor",
                    "activeTasks", EXECUTOR_ENDPOINT, "executor 当前活跃 task 数"),
            metric("spark_executor_input_bytes_total", "counter", "bytes", "executor",
                    "totalInputBytes", EXECUTOR_ENDPOINT, "executor 累计输入字节"),
            metric("spark_executor_tasks_failed_total", "counter", "count", "executor",
                    "failedTasks", EXECUTOR_ENDPOINT, "executor 累计失败 task 数"),
            metric("spark_stage_input_records_total", "counter", "count", "stage",
                    "inputRecords", STAGE_ENDPOINT, "stage 输入记录数"),
            metric("spark_stage_output_records_total", "counter", "count", "stage",
                    "outputRecords", STAGE_ENDPOINT, "stage 输出记录数"),
            metric("spark_stage_shuffle_read_bytes_total", "counter", "bytes", "stage",
                    "shuffleReadBytes", STAGE_ENDPOINT, "stage shuffle 读取字节"),
            metric("spark_stage_shuffle_write_bytes_total", "counter", "bytes", "stage",
                    "shuffleWriteBytes", STAGE_ENDPOINT, "stage shuffle 写入字节"),
            metric("spark_stage_tasks_failed_total", "counter", "count", "stage",
                    "numFailedTasks", STAGE_ENDPOINT, "stage 失败 task 数"),
            metric("spark_stage_attempts", "gauge", "count", "stage",
                    "attemptId", STAGE_ENDPOINT, "stage attempt 序号（attemptId + 1）"),
            metric("spark_job_tasks_failed_total", "counter", "count", "job",
                    "numFailedTasks", JOB_ENDPOINT, "作业所有 job 的失败 task 总数"),
            metric("spark_job_tasks_killed_total", "counter", "count", "job",
                    "numKilledTasks", JOB_ENDPOINT, "作业所有 job 的 killed task 总数"));

    private SparkRuntimeMetricSpecs() {
    }

    private static MetricDefinition metric(String name, String type, String unit, String scope,
                                           String rawMetric, String endpoint, String note) {
        return new MetricDefinition(name, type, unit, scope,
                List.of(), List.of(), java.util.Map.of(), List.of(),
                rawMetric, endpoint, true, note);
    }
}
