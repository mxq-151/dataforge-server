package com.bigdata.modules.telemetry.v1.catalog;

import com.bigdata.modules.telemetry.v1.model.MetricDefinition;
import com.bigdata.modules.telemetry.v1.model.MetricPattern;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.bigdata.modules.telemetry.v1.catalog.MetricSemanticsSupport.*;

/**
 * Flink REST 指标语义：对外保留语义名，pattern 绑定真实 REST 指标 ID / 族。
 *
 * <p>Flink 2.2 的 {@code /jobs/{id}/metrics} 只返回 job 级指标；vertex 级指标在
 * {@code /jobs/{id}/vertices/{vid}/metrics}，名字带 subtask/operator 前缀，因此用后缀族匹配。
 * 背压与 tasks.failed 不在 metrics 列举结果里，按手工维护处理。</p>
 */
@Component
public class FlinkMetricSemantics {

    private static final String JOB_METRICS = "/jobs/{id}/metrics?get=";
    private static final String TM_METRICS = "/taskmanagers/{id}/metrics?get=";
    private static final String VERTEX_METRICS = "/jobs/{id}/vertices/{vid}/subtasks/metrics?get=";
    private static final String JOB = "/jobs/{id}";

    public List<RestMetricSemanticsRegistry.ComponentMetricSemantics> contribute() {
        return List.of(
                new RestMetricSemanticsRegistry.ComponentMetricSemantics(
                        "flink", "flink.jobmanager", jobManagerMetrics()),
                new RestMetricSemanticsRegistry.ComponentMetricSemantics(
                        "flink", "flink.taskmanager", taskManagerMetrics()));
    }

    private List<MetricDefinition> jobManagerMetrics() {
        Map<String, List<String>> checkpointPref = pref(
                List.of("flink-rest", "prometheus", "victoriametrics"),
                List.of("flink-rest", "prometheus", "victoriametrics"));
        Map<String, List<String>> throughputPref = pref(
                List.of("prometheus", "victoriametrics", "flink-rest"),
                List.of("prometheus", "victoriametrics"));

        return List.of(
                rawMetric("flink_checkpoint_duration_seconds", "gauge", "seconds", "job",
                        labels(label("job_id", true, "Flink 作业 ID")), List.of("jobId"),
                        checkpointPref, checkpointBackends(),
                        "lastCheckpointDuration", JOB_METRICS, true,
                        "Flink 返回毫秒，输出按秒换算"),
                rawMetric("flink_checkpoint_size_bytes", "gauge", "bytes", "job",
                        labels(label("job_id", true, "Flink 作业 ID")), List.of("jobId"),
                        checkpointPref, checkpointBackends(),
                        "lastCheckpointSize", JOB_METRICS, true, "最近一次 checkpoint 字节数"),
                rawMetric("flink_checkpoint_completed_total", "counter", "count", "job",
                        labels(label("job_id", true, "Flink 作业 ID")), List.of("jobId"),
                        checkpointPref, checkpointBackends(),
                        "numberOfCompletedCheckpoints", JOB_METRICS, true, "成功 checkpoint 次数"),
                rawMetric("flink_checkpoint_failed_total", "counter", "count", "job",
                        labels(label("job_id", true, "Flink 作业 ID")), List.of("jobId"),
                        checkpointPref, checkpointBackends(),
                        "numberOfFailedCheckpoints", JOB_METRICS, true, "失败 checkpoint 次数"),
                rawMetric("flink_restarts_total", "counter", "count", "job",
                        labels(label("job_id", true, "Flink 作业 ID")), List.of("jobId"),
                        throughputPref, throughputBackends(),
                        "numRestarts", JOB_METRICS, true, "作业重启次数"),
                manualSemanticMetric("flink_records_in_total", "counter", "records", "job",
                        labels(label("job_id", true, "Flink 作业 ID")), List.of("jobId"),
                        throughputPref, throughputBackends(),
                        "numRecordsIn", JOB_METRICS, false,
                        "Flink 2.2 的 /jobs/{id}/metrics 不再返回该指标；真实数据在 vertex 级，"
                                + "Prometheus 侧需按算子聚合；名字表为手工维护"),
                manualSemanticMetric("flink_records_out_total", "counter", "records", "job",
                        labels(label("job_id", true, "Flink 作业 ID")), List.of("jobId"),
                        throughputPref, throughputBackends(),
                        "numRecordsOut", JOB_METRICS, false,
                        "Flink 2.2 的 /jobs/{id}/metrics 不再返回该指标；真实数据在 vertex 级；"
                                + "名字表为手工维护"),
                manualSemanticMetric("flink_bytes_in_total", "counter", "bytes", "job",
                        labels(label("job_id", true, "Flink 作业 ID")), List.of("jobId"),
                        throughputPref, throughputBackends(),
                        "numBytesIn", JOB_METRICS, false,
                        "Flink 2.2 的 /jobs/{id}/metrics 不再返回该指标；真实数据在 vertex 级；"
                                + "名字表为手工维护"),
                manualSemanticMetric("flink_bytes_out_total", "counter", "bytes", "job",
                        labels(label("job_id", true, "Flink 作业 ID")), List.of("jobId"),
                        throughputPref, throughputBackends(),
                        "numBytesOut", JOB_METRICS, false,
                        "Flink 2.2 的 /jobs/{id}/metrics 不再返回该指标；真实数据在 vertex 级；"
                                + "名字表为手工维护"),
                manualSemanticMetric("flink_tasks_failed", "counter", "count", "job",
                        labels(label("job_id", true, "Flink 作业 ID")), List.of("jobId"),
                        throughputPref, throughputBackends(),
                        "tasks.failed", JOB, true,
                        "来自 /jobs/{id}.tasks.failed，不通过 metrics 端点；名字表为手工维护"));
    }

    private List<MetricDefinition> taskManagerMetrics() {
        Map<String, List<String>> jvmPref = pref(
                List.of("flink-rest", "prometheus", "victoriametrics"),
                List.of("prometheus", "victoriametrics"));
        Map<String, List<String>> vertexPref = pref(
                List.of("flink-rest", "prometheus", "victoriametrics"),
                List.of("flink-rest", "prometheus", "victoriametrics"));

        return List.of(
                rawMetric("jvm_heap_used_bytes", "gauge", "bytes", "component",
                        labels(label("taskmanager_id", true, "TaskManager 实例标识")), List.of(),
                        jvmPref, jvmBackends(),
                        "Status.JVM.Memory.Heap.Used", TM_METRICS, true, "堆已用字节"),
                rawMetric("jvm_heap_max_bytes", "gauge", "bytes", "component",
                        labels(label("taskmanager_id", true, "TaskManager 实例标识")), List.of(),
                        jvmPref, jvmBackends(),
                        "Status.JVM.Memory.Heap.Max", TM_METRICS, true, "堆上限字节"),
                rawMetric("jvm_nonheap_used_bytes", "gauge", "bytes", "component",
                        labels(label("taskmanager_id", true, "TaskManager 实例标识")), List.of(),
                        jvmPref, jvmBackends(),
                        "Status.JVM.Memory.NonHeap.Used", TM_METRICS, true, "非堆已用字节"),
                rawMetric("jvm_threads", "gauge", "count", "component",
                        labels(label("taskmanager_id", true, "TaskManager 实例标识")), List.of(),
                        jvmPref, jvmBackends(),
                        "Status.JVM.Threads.Count", TM_METRICS, true, "JVM 线程数"),
                familyMetric("jvm_gc_collections_total", "counter", "count", "component",
                        labels(label("taskmanager_id", true, "TaskManager 实例标识"),
                                label("collector", false, "GC 收集器名称")), List.of(),
                        jvmPref, jvmBackends(),
                        MetricPattern.regex("^Status\\.JVM\\.GarbageCollector\\..+\\.Count$"),
                        TM_METRICS, false,
                        "通配符按 collector 展开后求和；版本差异存在，需现场核对"),
                familyMetric("jvm_gc_collection_seconds_total", "counter", "seconds", "component",
                        labels(label("taskmanager_id", true, "TaskManager 实例标识"),
                                label("collector", false, "GC 收集器名称")), List.of(),
                        jvmPref, jvmBackends(),
                        MetricPattern.regex("^Status\\.JVM\\.GarbageCollector\\..+\\.Time$"),
                        TM_METRICS, false,
                        "Flink 返回毫秒，输出按秒换算；通配符行为需现场核对"),
                manualSemanticMetric("flink_backpressure_busy_ratio", "gauge", "ratio", "vertex",
                        labels(label("job_id", true, "Flink 作业 ID"),
                                label("vertex_id", true, "算子顶点 ID")), List.of("jobId"),
                        vertexPref, vertexBackends(),
                        "busyTimeMsPerSecond", VERTEX_METRICS, false,
                        "来自 /backpressure 的 busyRatio，不在 metrics 列举结果里；名字表为手工维护"),
                manualSemanticMetric("flink_backpressure_backpressured_ratio", "gauge", "ratio", "vertex",
                        labels(label("job_id", true, "Flink 作业 ID"),
                                label("vertex_id", true, "算子顶点 ID")), List.of("jobId"),
                        vertexPref, vertexBackends(),
                        "backPressuredTimeMsPerSecond", VERTEX_METRICS, false,
                        "来自 /backpressure 的 ratio，不在 metrics 列举结果里；名字表为手工维护"),
                manualSemanticMetric("flink_backpressure_idle_ms", "gauge", "ms", "vertex",
                        labels(label("job_id", true, "Flink 作业 ID"),
                                label("vertex_id", true, "算子顶点 ID")), List.of("jobId"),
                        vertexPref, vertexBackends(),
                        "idleTimeMsPerSecond", VERTEX_METRICS, false,
                        "Flink 1.14+ 任务级指标，旧版本名称不同；名字表为手工维护"),
                familyMetric("flink_shuffle_bytes_in_local_total", "counter", "bytes", "vertex",
                        labels(label("job_id", true, "Flink 作业 ID"),
                                label("vertex_id", true, "算子顶点 ID")), List.of("jobId"),
                        vertexPref, vertexBackends(),
                        MetricPattern.suffix(".numBytesInLocal"), VERTEX_METRICS, true, "本地输入字节"),
                familyMetric("flink_shuffle_bytes_in_remote_total", "counter", "bytes", "vertex",
                        labels(label("job_id", true, "Flink 作业 ID"),
                                label("vertex_id", true, "算子顶点 ID")), List.of("jobId"),
                        vertexPref, vertexBackends(),
                        MetricPattern.suffix(".numBytesInRemote"), VERTEX_METRICS, true,
                        "远端输入字节（网络 shuffle）"),
                manualSemanticMetric("flink_shuffle_bytes_out_total", "counter", "bytes", "vertex",
                        labels(label("job_id", true, "Flink 作业 ID"),
                                label("vertex_id", true, "算子顶点 ID")), List.of("jobId"),
                        vertexPref, vertexBackends(),
                        "numBytesOut", VERTEX_METRICS, true,
                        "与 flink_vertex_bytes_out_total 指向同一 raw 指标 numBytesOut；"
                                + "保留为手工别名，避免与 vertex 语义模式冲突"),
                familyMetric("flink_vertex_records_in_total", "counter", "records", "vertex",
                        labels(label("job_id", true, "Flink 作业 ID"),
                                label("vertex_id", true, "算子顶点 ID")), List.of("jobId"),
                        vertexPref, vertexBackends(),
                        MetricPattern.suffix(".numRecordsIn"), VERTEX_METRICS, true,
                        "算子级累计读取记录数（subtasks.sum）"),
                familyMetric("flink_vertex_records_out_total", "counter", "records", "vertex",
                        labels(label("job_id", true, "Flink 作业 ID"),
                                label("vertex_id", true, "算子顶点 ID")), List.of("jobId"),
                        vertexPref, vertexBackends(),
                        MetricPattern.suffix(".numRecordsOut"), VERTEX_METRICS, true,
                        "算子级累计写出记录数（subtasks.sum）"),
                familyMetric("flink_vertex_bytes_in_total", "counter", "bytes", "vertex",
                        labels(label("job_id", true, "Flink 作业 ID"),
                                label("vertex_id", true, "算子顶点 ID")), List.of("jobId"),
                        vertexPref, vertexBackends(),
                        MetricPattern.suffix(".numBytesIn"), VERTEX_METRICS, true,
                        "算子级累计读取字节（subtasks.sum）"),
                familyMetric("flink_vertex_bytes_out_total", "counter", "bytes", "vertex",
                        labels(label("job_id", true, "Flink 作业 ID"),
                                label("vertex_id", true, "算子顶点 ID")), List.of("jobId"),
                        vertexPref, vertexBackends(),
                        MetricPattern.suffix(".numBytesOut"), VERTEX_METRICS, true,
                        "算子级累计写出字节（subtasks.sum）"),
                familyMetric("flink_vertex_records_in_rate", "gauge", "records_per_second", "vertex",
                        labels(label("job_id", true, "Flink 作业 ID"),
                                label("vertex_id", true, "算子顶点 ID")), List.of("jobId"),
                        vertexPref, vertexBackends(),
                        MetricPattern.suffix(".numRecordsInPerSecond"), VERTEX_METRICS, true,
                        "算子级读取速率（subtasks.sum）"),
                familyMetric("flink_vertex_records_out_rate", "gauge", "records_per_second", "vertex",
                        labels(label("job_id", true, "Flink 作业 ID"),
                                label("vertex_id", true, "算子顶点 ID")), List.of("jobId"),
                        vertexPref, vertexBackends(),
                        MetricPattern.suffix(".numRecordsOutPerSecond"), VERTEX_METRICS, true,
                        "算子级写出速率（subtasks.sum）"),
                familyMetric("flink_vertex_bytes_in_rate", "gauge", "bytes_per_second", "vertex",
                        labels(label("job_id", true, "Flink 作业 ID"),
                                label("vertex_id", true, "算子顶点 ID")), List.of("jobId"),
                        vertexPref, vertexBackends(),
                        MetricPattern.suffix(".numBytesInPerSecond"), VERTEX_METRICS, true,
                        "算子级读取字节速率（subtasks.sum）"),
                familyMetric("flink_vertex_bytes_out_rate", "gauge", "bytes_per_second", "vertex",
                        labels(label("job_id", true, "Flink 作业 ID"),
                                label("vertex_id", true, "算子顶点 ID")), List.of("jobId"),
                        vertexPref, vertexBackends(),
                        MetricPattern.suffix(".numBytesOutPerSecond"), VERTEX_METRICS, true,
                        "算子级写出字节速率（subtasks.sum）"));
    }
}
