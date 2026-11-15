package com.bigdata.modules.telemetry.v1.catalog;

import com.bigdata.modules.telemetry.model.Requirement;
import com.bigdata.modules.telemetry.model.RequirementKind;
import com.bigdata.modules.telemetry.v1.model.BackendBinding;
import com.bigdata.modules.telemetry.v1.model.BackendStatus;
import com.bigdata.modules.telemetry.v1.model.BackendType;
import com.bigdata.modules.telemetry.v1.model.CatalogStatus;
import com.bigdata.modules.telemetry.v1.model.ComponentCatalogEntry;
import com.bigdata.modules.telemetry.v1.model.ComponentCatalogEntry.EngineEvidenceDefinition;
import com.bigdata.modules.telemetry.v1.model.LogDataType;
import com.bigdata.modules.telemetry.v1.model.LogStreamDefinition;
import com.bigdata.modules.telemetry.v1.model.MetricDefinition;
import com.bigdata.modules.telemetry.v1.model.MetricDefinition.LabelDefinition;
import com.bigdata.modules.telemetry.v1.model.QueryMode;
import com.bigdata.modules.telemetry.v1.model.Sampling;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内置目录种子：Spark / Flink 的指标与日志清单，以及其他引擎的「已声明未实现」条目。
 *
 * <p>没有实证来源的指标一律不编造；未实现的服务只声明组件与日志流，查询返回
 * NOT_IMPLEMENTED + requirements。</p>
 */
@Component
public class SeedCatalogContributor implements CatalogContributor {

    private static final String STDOUT = "stdout";
    private static final String STDERR = "stderr";
    private static final String SLOWQUERY = "slowquery";

    private static final Set<QueryMode> BOTH = Set.of(QueryMode.RANGE, QueryMode.INSTANT);
    private static final Set<QueryMode> INSTANT = Set.of(QueryMode.INSTANT);
    private static final Set<Sampling> STEP = Set.of(Sampling.STEP);

    // 引擎原始指标端点（与真实 REST 路径一致；{id}/{appId} 为占位符）
    private static final String FLINK_JOB_METRICS = "/jobs/{id}/metrics?get=";
    private static final String FLINK_TM_METRICS = "/taskmanagers/{id}/metrics?get=";
    private static final String FLINK_VERTEX_METRICS =
            "/jobs/{id}/vertices/{vid}/subtasks/metrics?get=";
    private static final String FLINK_JOB = "/jobs/{id}";
    private static final String SPARK_APP = "/api/v1/applications/{appId}";
    private static final String SPARK_EXECUTORS =
            "/api/v1/applications/{appId}/executors（live）/allexecutors（history）";
    private static final String SPARK_STAGES = "/api/v1/applications/{appId}/stages";
    private static final String SPARK_JOBS = "/api/v1/applications/{appId}/jobs";

    @Override
    public List<ComponentCatalogEntry> contribute() {
        List<ComponentCatalogEntry> entries = new ArrayList<>();
        entries.add(flinkJobManager());
        entries.add(flinkTaskManager());
        entries.add(sparkDriver());
        entries.add(sparkExecutor());

        entries.add(declared("doris", "doris.fe",
                api("metrics.doris.fe", "需要 Doris FE metrics 端点与指标清单"),
                slowQueryRequirement("开启 Doris 审计插件、配置慢查询阈值与审计日志路径")));
        entries.add(declared("doris", "doris.be",
                api("metrics.doris.be", "需要 Doris BE metrics 端点与指标清单"),
                slowQueryRequirement("开启 Doris 审计插件、配置慢查询阈值与审计日志路径")));
        entries.add(declared("starrocks", "starrocks.fe",
                api("metrics.starrocks.fe", "需要 StarRocks FE metrics 端点与指标清单"),
                slowQueryRequirement("开启 StarRocks 审计日志、配置慢查询阈值与审计日志路径")));
        entries.add(declared("starrocks", "starrocks.be",
                api("metrics.starrocks.be", "需要 StarRocks BE metrics 端点与指标清单"),
                slowQueryRequirement("开启 StarRocks 审计日志、配置慢查询阈值与审计日志路径")));
        entries.add(declared("starrocks", "starrocks.cn",
                api("metrics.starrocks.cn", "需要 StarRocks CN metrics 端点与指标清单"),
                slowQueryRequirement("开启 StarRocks 审计日志、配置慢查询阈值与审计日志路径")));
        entries.add(declared("clickhouse", "clickhouse.server",
                api("metrics.clickhouse", "需要 system.metrics / system.events 映射"),
                slowQueryRequirement("启用 system.query_log 并配置慢查询阈值")));
        entries.add(declared("hbase", "hbase.master",
                api("metrics.hbase.jmx", "需要 HBase Master JMX 指标清单"), null));
        entries.add(declared("hbase", "hbase.regionserver",
                api("metrics.hbase.jmx", "需要 HBase RegionServer JMX 指标清单"), null));
        entries.add(kafkaBroker());
        entries.add(kafkaController());
        entries.add(yarnResourceManager());
        entries.add(yarnNodeManager());
        entries.add(declared("pulsar", "pulsar.broker",
                api("metrics.pulsar.prometheus", "需要 Pulsar Broker Prometheus 端点与指标清单"), null));
        entries.add(declared("pulsar", "pulsar.bookkeeper",
                api("metrics.pulsar.prometheus", "需要 BookKeeper Prometheus 端点与指标清单"), null));
        entries.add(declared("pulsar", "pulsar.proxy",
                api("metrics.pulsar.prometheus", "需要 Pulsar Proxy Prometheus 端点与指标清单"), null));
        return entries;
    }

    // ------------------------------------------------------------------ Spark / Flink

    private ComponentCatalogEntry flinkJobManager() {
        Map<String, List<String>> checkpointPref = pref(
                List.of("flink-rest", "prometheus", "victoriametrics"),
                List.of("flink-rest", "prometheus", "victoriametrics"));
        Map<String, List<String>> throughputPref = pref(
                List.of("prometheus", "victoriametrics", "flink-rest"),
                List.of("prometheus", "victoriametrics"));

        List<MetricDefinition> metrics = List.of(
                rawMetric("flink_checkpoint_duration_seconds", "gauge", "seconds", "job",
                        labels(label("job_id", true, "Flink 作业 ID")), List.of("jobId"),
                        checkpointPref, checkpointBackends(),
                        "lastCheckpointDuration", FLINK_JOB_METRICS, true,
                        "Flink 返回毫秒，输出按秒换算"),
                rawMetric("flink_checkpoint_size_bytes", "gauge", "bytes", "job",
                        labels(label("job_id", true, "Flink 作业 ID")), List.of("jobId"),
                        checkpointPref, checkpointBackends(),
                        "lastCheckpointSize", FLINK_JOB_METRICS, true, "最近一次 checkpoint 字节数"),
                rawMetric("flink_checkpoint_completed_total", "counter", "count", "job",
                        labels(label("job_id", true, "Flink 作业 ID")), List.of("jobId"),
                        checkpointPref, checkpointBackends(),
                        "numberOfCompletedCheckpoints", FLINK_JOB_METRICS, true, "成功 checkpoint 次数"),
                rawMetric("flink_checkpoint_failed_total", "counter", "count", "job",
                        labels(label("job_id", true, "Flink 作业 ID")), List.of("jobId"),
                        checkpointPref, checkpointBackends(),
                        "numberOfFailedCheckpoints", FLINK_JOB_METRICS, true, "失败 checkpoint 次数"),
                rawMetric("flink_restarts_total", "counter", "count", "job",
                        labels(label("job_id", true, "Flink 作业 ID")), List.of("jobId"),
                        throughputPref, throughputBackends(),
                        "numRestarts", FLINK_JOB_METRICS, true, "作业重启次数"),
                rawMetric("flink_records_in_total", "counter", "records", "job",
                        labels(label("job_id", true, "Flink 作业 ID")), List.of("jobId"),
                        throughputPref, throughputBackends(),
                        "numRecordsIn", FLINK_JOB_METRICS, false,
                        "Flink 2.2 的 /jobs/{id}/metrics 不再返回该指标；真实数据在 vertex 级，"
                                + "Prometheus 侧需按算子聚合"),
                rawMetric("flink_records_out_total", "counter", "records", "job",
                        labels(label("job_id", true, "Flink 作业 ID")), List.of("jobId"),
                        throughputPref, throughputBackends(),
                        "numRecordsOut", FLINK_JOB_METRICS, false,
                        "Flink 2.2 的 /jobs/{id}/metrics 不再返回该指标；真实数据在 vertex 级"),
                rawMetric("flink_bytes_in_total", "counter", "bytes", "job",
                        labels(label("job_id", true, "Flink 作业 ID")), List.of("jobId"),
                        throughputPref, throughputBackends(),
                        "numBytesIn", FLINK_JOB_METRICS, false,
                        "Flink 2.2 的 /jobs/{id}/metrics 不再返回该指标；真实数据在 vertex 级"),
                rawMetric("flink_bytes_out_total", "counter", "bytes", "job",
                        labels(label("job_id", true, "Flink 作业 ID")), List.of("jobId"),
                        throughputPref, throughputBackends(),
                        "numBytesOut", FLINK_JOB_METRICS, false,
                        "Flink 2.2 的 /jobs/{id}/metrics 不再返回该指标；真实数据在 vertex 级"),
                rawMetric("flink_tasks_failed", "counter", "count", "job",
                        labels(label("job_id", true, "Flink 作业 ID")), List.of("jobId"),
                        throughputPref, throughputBackends(),
                        "tasks.failed", FLINK_JOB, true,
                        "来自 /jobs/{id}.tasks.failed，不通过 metrics 端点"));

        List<LogStreamDefinition> streams = logStreams("flink", "jobId");

        List<EngineEvidenceDefinition> evidence = List.of(new EngineEvidenceDefinition(
                "exception_summary",
                List.of(flinkRest(10, INSTANT, STEP, BackendStatus.IMPLEMENTED,
                        "Flink 异常摘要是结构化证据，不进日志接口"))));

        return new ComponentCatalogEntry("flink", "flink.jobmanager", metrics, streams, evidence,
                CatalogStatus.IMPLEMENTED, List.of());
    }

    private ComponentCatalogEntry flinkTaskManager() {
        Map<String, List<String>> jvmPref = pref(
                List.of("flink-rest", "prometheus", "victoriametrics"),
                List.of("prometheus", "victoriametrics"));
        Map<String, List<String>> vertexPref = pref(
                List.of("flink-rest", "prometheus", "victoriametrics"),
                List.of("flink-rest", "prometheus", "victoriametrics"));

        List<MetricDefinition> metrics = List.of(
                rawMetric("jvm_heap_used_bytes", "gauge", "bytes", "component",
                        labels(label("taskmanager_id", true, "TaskManager 实例标识")), List.of(),
                        jvmPref, jvmBackends(),
                        "Status.JVM.Memory.Heap.Used", FLINK_TM_METRICS, true, "堆已用字节"),
                rawMetric("jvm_heap_max_bytes", "gauge", "bytes", "component",
                        labels(label("taskmanager_id", true, "TaskManager 实例标识")), List.of(),
                        jvmPref, jvmBackends(),
                        "Status.JVM.Memory.Heap.Max", FLINK_TM_METRICS, true, "堆上限字节"),
                rawMetric("jvm_nonheap_used_bytes", "gauge", "bytes", "component",
                        labels(label("taskmanager_id", true, "TaskManager 实例标识")), List.of(),
                        jvmPref, jvmBackends(),
                        "Status.JVM.Memory.NonHeap.Used", FLINK_TM_METRICS, true, "非堆已用字节"),
                rawMetric("jvm_threads", "gauge", "count", "component",
                        labels(label("taskmanager_id", true, "TaskManager 实例标识")), List.of(),
                        jvmPref, jvmBackends(),
                        "Status.JVM.Threads.Count", FLINK_TM_METRICS, true, "JVM 线程数"),
                rawMetric("jvm_gc_collections_total", "counter", "count", "component",
                        labels(label("taskmanager_id", true, "TaskManager 实例标识"),
                                label("collector", false, "GC 收集器名称")), List.of(),
                        jvmPref, jvmBackends(),
                        "Status.JVM.GarbageCollector.*.Count", FLINK_TM_METRICS, false,
                        "通配符按 collector 展开后求和；版本差异存在，需现场核对"),
                rawMetric("jvm_gc_collection_seconds_total", "counter", "seconds", "component",
                        labels(label("taskmanager_id", true, "TaskManager 实例标识"),
                                label("collector", false, "GC 收集器名称")), List.of(),
                        jvmPref, jvmBackends(),
                        "Status.JVM.GarbageCollector.*.Time", FLINK_TM_METRICS, false,
                        "Flink 返回毫秒，输出按秒换算；通配符行为需现场核对"),
                rawMetric("flink_backpressure_busy_ratio", "gauge", "ratio", "vertex",
                        labels(label("job_id", true, "Flink 作业 ID"),
                                label("vertex_id", true, "算子顶点 ID")), List.of("jobId"),
                        vertexPref, vertexBackends(),
                        "busyTimeMsPerSecond", FLINK_VERTEX_METRICS, false,
                        "Flink 返回每毫秒占比（0-1000），输出按 1000 归一到 ratio"),
                rawMetric("flink_backpressure_backpressured_ratio", "gauge", "ratio", "vertex",
                        labels(label("job_id", true, "Flink 作业 ID"),
                                label("vertex_id", true, "算子顶点 ID")), List.of("jobId"),
                        vertexPref, vertexBackends(),
                        "backPressuredTimeMsPerSecond", FLINK_VERTEX_METRICS, false,
                        "Flink 返回每毫秒占比（0-1000），输出按 1000 归一到 ratio"),
                rawMetric("flink_backpressure_idle_ms", "gauge", "ms", "vertex",
                        labels(label("job_id", true, "Flink 作业 ID"),
                                label("vertex_id", true, "算子顶点 ID")), List.of("jobId"),
                        vertexPref, vertexBackends(),
                        "idleTimeMsPerSecond", FLINK_VERTEX_METRICS, false,
                        "Flink 1.14+ 任务级指标，旧版本名称不同"),
                rawMetric("flink_shuffle_bytes_in_local_total", "counter", "bytes", "vertex",
                        labels(label("job_id", true, "Flink 作业 ID"),
                                label("vertex_id", true, "算子顶点 ID")), List.of("jobId"),
                        vertexPref, vertexBackends(),
                        "numBytesInLocal", FLINK_VERTEX_METRICS, true, "本地输入字节"),
                rawMetric("flink_shuffle_bytes_in_remote_total", "counter", "bytes", "vertex",
                        labels(label("job_id", true, "Flink 作业 ID"),
                                label("vertex_id", true, "算子顶点 ID")), List.of("jobId"),
                        vertexPref, vertexBackends(),
                        "numBytesInRemote", FLINK_VERTEX_METRICS, true, "远端输入字节（网络 shuffle）"),
                rawMetric("flink_shuffle_bytes_out_total", "counter", "bytes", "vertex",
                        labels(label("job_id", true, "Flink 作业 ID"),
                                label("vertex_id", true, "算子顶点 ID")), List.of("jobId"),
                        vertexPref, vertexBackends(),
                        "numBytesOut", FLINK_VERTEX_METRICS, true, "输出字节"),
                rawMetric("flink_vertex_records_in_total", "counter", "records", "vertex",
                        labels(label("job_id", true, "Flink 作业 ID"),
                                label("vertex_id", true, "算子顶点 ID")), List.of("jobId"),
                        vertexPref, vertexBackends(),
                        "numRecordsIn", FLINK_VERTEX_METRICS, true,
                        "算子级累计读取记录数（subtasks.sum）"),
                rawMetric("flink_vertex_records_out_total", "counter", "records", "vertex",
                        labels(label("job_id", true, "Flink 作业 ID"),
                                label("vertex_id", true, "算子顶点 ID")), List.of("jobId"),
                        vertexPref, vertexBackends(),
                        "numRecordsOut", FLINK_VERTEX_METRICS, true,
                        "算子级累计写出记录数（subtasks.sum）"),
                rawMetric("flink_vertex_bytes_in_total", "counter", "bytes", "vertex",
                        labels(label("job_id", true, "Flink 作业 ID"),
                                label("vertex_id", true, "算子顶点 ID")), List.of("jobId"),
                        vertexPref, vertexBackends(),
                        "numBytesIn", FLINK_VERTEX_METRICS, true,
                        "算子级累计读取字节（subtasks.sum）"),
                rawMetric("flink_vertex_bytes_out_total", "counter", "bytes", "vertex",
                        labels(label("job_id", true, "Flink 作业 ID"),
                                label("vertex_id", true, "算子顶点 ID")), List.of("jobId"),
                        vertexPref, vertexBackends(),
                        "numBytesOut", FLINK_VERTEX_METRICS, true,
                        "算子级累计写出字节（subtasks.sum）"),
                rawMetric("flink_vertex_records_in_rate", "gauge", "records_per_second", "vertex",
                        labels(label("job_id", true, "Flink 作业 ID"),
                                label("vertex_id", true, "算子顶点 ID")), List.of("jobId"),
                        vertexPref, vertexBackends(),
                        "numRecordsInPerSecond", FLINK_VERTEX_METRICS, true,
                        "算子级读取速率（subtasks.sum）"),
                rawMetric("flink_vertex_records_out_rate", "gauge", "records_per_second", "vertex",
                        labels(label("job_id", true, "Flink 作业 ID"),
                                label("vertex_id", true, "算子顶点 ID")), List.of("jobId"),
                        vertexPref, vertexBackends(),
                        "numRecordsOutPerSecond", FLINK_VERTEX_METRICS, true,
                        "算子级写出速率（subtasks.sum）"),
                rawMetric("flink_vertex_bytes_in_rate", "gauge", "bytes_per_second", "vertex",
                        labels(label("job_id", true, "Flink 作业 ID"),
                                label("vertex_id", true, "算子顶点 ID")), List.of("jobId"),
                        vertexPref, vertexBackends(),
                        "numBytesInPerSecond", FLINK_VERTEX_METRICS, true,
                        "算子级读取字节速率（subtasks.sum）"),
                rawMetric("flink_vertex_bytes_out_rate", "gauge", "bytes_per_second", "vertex",
                        labels(label("job_id", true, "Flink 作业 ID"),
                                label("vertex_id", true, "算子顶点 ID")), List.of("jobId"),
                        vertexPref, vertexBackends(),
                        "numBytesOutPerSecond", FLINK_VERTEX_METRICS, true,
                        "算子级写出字节速率（subtasks.sum）"));

        List<LogStreamDefinition> streams = logStreams("flink", "jobId");

        return new ComponentCatalogEntry("flink", "flink.taskmanager", metrics, streams, List.of(),
                CatalogStatus.IMPLEMENTED, List.of());
    }

    private ComponentCatalogEntry sparkDriver() {
        Map<String, List<String>> pref = sparkPref();
        List<MetricDefinition> metrics = List.of(
                rawMetric("spark_stage_input_records_total", "counter", "records", "stage",
                        labels(label("application_id", true, "Spark 应用 ID"),
                                label("stage_id", true, "Stage ID"),
                                label("attempt_id", false, "Stage attempt ID")), List.of("applicationId"),
                        pref, sparkBackends(), "inputRecords", SPARK_STAGES, true, "stage 输入记录数"),
                rawMetric("spark_stage_output_records_total", "counter", "records", "stage",
                        labels(label("application_id", true, "Spark 应用 ID"),
                                label("stage_id", true, "Stage ID"),
                                label("attempt_id", false, "Stage attempt ID")), List.of("applicationId"),
                        pref, sparkBackends(), "outputRecords", SPARK_STAGES, true, "stage 输出记录数"),
                rawMetric("spark_stage_input_bytes_total", "counter", "bytes", "stage",
                        labels(label("application_id", true, "Spark 应用 ID"),
                                label("stage_id", true, "Stage ID"),
                                label("attempt_id", false, "Stage attempt ID")), List.of("applicationId"),
                        pref, sparkBackends(), "inputBytes", SPARK_STAGES, true, "stage 输入字节"),
                rawMetric("spark_stage_output_bytes_total", "counter", "bytes", "stage",
                        labels(label("application_id", true, "Spark 应用 ID"),
                                label("stage_id", true, "Stage ID"),
                                label("attempt_id", false, "Stage attempt ID")), List.of("applicationId"),
                        pref, sparkBackends(), "outputBytes", SPARK_STAGES, true, "stage 输出字节"),
                rawMetric("spark_stage_shuffle_read_bytes_total", "counter", "bytes", "stage",
                        labels(label("application_id", true, "Spark 应用 ID"),
                                label("stage_id", true, "Stage ID"),
                                label("attempt_id", false, "Stage attempt ID")), List.of("applicationId"),
                        pref, sparkBackends(), "shuffleReadBytes", SPARK_STAGES, true, "shuffle 读取字节"),
                rawMetric("spark_stage_shuffle_write_bytes_total", "counter", "bytes", "stage",
                        labels(label("application_id", true, "Spark 应用 ID"),
                                label("stage_id", true, "Stage ID"),
                                label("attempt_id", false, "Stage attempt ID")), List.of("applicationId"),
                        pref, sparkBackends(), "shuffleWriteBytes", SPARK_STAGES, true, "shuffle 写出字节"),
                rawMetric("spark_stage_shuffle_read_records_total", "counter", "records", "stage",
                        labels(label("application_id", true, "Spark 应用 ID"),
                                label("stage_id", true, "Stage ID"),
                                label("attempt_id", false, "Stage attempt ID")), List.of("applicationId"),
                        pref, sparkBackends(), "shuffleReadRecords", SPARK_STAGES, true,
                        "shuffle 读取记录数"),
                rawMetric("spark_stage_shuffle_write_records_total", "counter", "records", "stage",
                        labels(label("application_id", true, "Spark 应用 ID"),
                                label("stage_id", true, "Stage ID"),
                                label("attempt_id", false, "Stage attempt ID")), List.of("applicationId"),
                        pref, sparkBackends(), "shuffleWriteRecords", SPARK_STAGES, true,
                        "shuffle 写出记录数"),
                rawMetric("spark_stage_shuffle_fetch_wait_ms", "counter", "ms", "stage",
                        labels(label("application_id", true, "Spark 应用 ID"),
                                label("stage_id", true, "Stage ID"),
                                label("attempt_id", false, "Stage attempt ID")), List.of("applicationId"),
                        pref, sparkBackends(), "shuffleFetchWaitTime", SPARK_STAGES, true,
                        "shuffle 拉取等待时间"),
                rawMetric("spark_stage_shuffle_write_time_ms", "counter", "ms", "stage",
                        labels(label("application_id", true, "Spark 应用 ID"),
                                label("stage_id", true, "Stage ID"),
                                label("attempt_id", false, "Stage attempt ID")), List.of("applicationId"),
                        pref, sparkBackends(), "shuffleWriteTime", SPARK_STAGES, true, "shuffle 写盘时间"),
                rawMetric("spark_stage_tasks_failed_total", "counter", "count", "stage",
                        labels(label("application_id", true, "Spark 应用 ID"),
                                label("stage_id", true, "Stage ID"),
                                label("attempt_id", false, "Stage attempt ID")), List.of("applicationId"),
                        pref, sparkBackends(), "numFailedTasks", SPARK_STAGES, true, "stage 失败 task 数"),
                rawMetric("spark_stage_tasks_killed_total", "counter", "count", "stage",
                        labels(label("application_id", true, "Spark 应用 ID"),
                                label("stage_id", true, "Stage ID"),
                                label("attempt_id", false, "Stage attempt ID")), List.of("applicationId"),
                        pref, sparkBackends(), "numKilledTasks", SPARK_STAGES, true, "stage 被杀 task 数"),
                rawMetric("spark_stage_attempts", "gauge", "count", "stage",
                        labels(label("application_id", true, "Spark 应用 ID"),
                                label("stage_id", true, "Stage ID")), List.of("applicationId"),
                        pref, sparkBackends(), "attemptId", SPARK_STAGES, true,
                        "attemptId 从 0 开始，尝试次数 = attemptId + 1"),
                rawMetric("spark_application_attempt_count", "gauge", "count", "application",
                        labels(label("application_id", true, "Spark 应用 ID")), List.of("applicationId"),
                        pref, sparkBackends(), "attempts", SPARK_APP, true, "attempts 数组长度"),
                rawMetric("spark_job_tasks_failed_total", "counter", "count", "job",
                        labels(label("application_id", true, "Spark 应用 ID")), List.of("applicationId"),
                        pref, sparkBackends(), "numFailedTasks", SPARK_JOBS, true, "作业汇总失败 task 数"),
                rawMetric("spark_job_tasks_killed_total", "counter", "count", "job",
                        labels(label("application_id", true, "Spark 应用 ID")), List.of("applicationId"),
                        pref, sparkBackends(), "numKilledTasks", SPARK_JOBS, true, "作业汇总被杀 task 数"));

        List<LogStreamDefinition> streams = logStreams("spark", "applicationId");

        return new ComponentCatalogEntry("spark", "spark.driver", metrics, streams, List.of(),
                CatalogStatus.IMPLEMENTED, List.of());
    }

    private ComponentCatalogEntry sparkExecutor() {
        Map<String, List<String>> pref = sparkPref();
        List<MetricDefinition> metrics = List.of(
                rawMetric("spark_executor_memory_used_bytes", "gauge", "bytes", "executor",
                        executorLabels(), List.of("applicationId"), pref, sparkBackends(),
                        "memoryUsed", SPARK_EXECUTORS, true, "executor 已用内存字节"),
                rawMetric("spark_executor_memory_max_bytes", "gauge", "bytes", "executor",
                        executorLabels(), List.of("applicationId"), pref, sparkBackends(),
                        "maxMemory", SPARK_EXECUTORS, true, "executor 最大内存字节"),
                rawMetric("spark_executor_gc_seconds_total", "counter", "seconds", "executor",
                        executorLabels(), List.of("applicationId"), pref, sparkBackends(),
                        "totalGCTime", SPARK_EXECUTORS, true, "Spark 返回毫秒，输出按秒换算"),
                rawMetric("spark_executor_run_seconds_total", "counter", "seconds", "executor",
                        executorLabels(), List.of("applicationId"), pref, sparkBackends(),
                        "totalDuration", SPARK_EXECUTORS, true, "Spark 返回毫秒，输出按秒换算"),
                rawMetric("spark_executor_cores", "gauge", "count", "executor",
                        executorLabels(), List.of("applicationId"), pref, sparkBackends(),
                        "totalCores", SPARK_EXECUTORS, true, "executor 核数"),
                rawMetric("spark_executor_active_tasks", "gauge", "count", "executor",
                        executorLabels(), List.of("applicationId"), pref, sparkBackends(),
                        "activeTasks", SPARK_EXECUTORS, true, "当前活跃 task 数"),
                rawMetric("spark_executor_input_bytes_total", "counter", "bytes", "executor",
                        executorLabels(), List.of("applicationId"), pref, sparkBackends(),
                        "totalInputBytes", SPARK_EXECUTORS, true, "executor 累计读取字节"),
                rawMetric("spark_executor_tasks_failed_total", "counter", "count", "executor",
                        executorLabels(), List.of("applicationId"), pref, sparkBackends(),
                        "failedTasks", SPARK_EXECUTORS, true, "executor 失败 task 数"),
                rawMetric("spark_executor_tasks_completed_total", "counter", "count", "executor",
                        executorLabels(), List.of("applicationId"), pref, sparkBackends(),
                        "completedTasks", SPARK_EXECUTORS, true, "executor 完成 task 数"),
                rawMetric("spark_executor_tasks_total", "counter", "count", "executor",
                        executorLabels(), List.of("applicationId"), pref, sparkBackends(),
                        "totalTasks", SPARK_EXECUTORS, true, "executor 累计 task 数"));

        List<LogStreamDefinition> streams = logStreams("spark", "applicationId");

        return new ComponentCatalogEntry("spark", "spark.executor", metrics, streams, List.of(),
                CatalogStatus.IMPLEMENTED, List.of());
    }

    // ------------------------------------------------------------------ Kafka / YARN

    private ComponentCatalogEntry kafkaBroker() {
        List<MetricDefinition> metrics = List.of(
                metric("kafka_server_broker_topic_metrics_messagesinpersec_total", "counter",
                        "messages", "broker", List.of(), List.of(), vmPref(), vmOnly()),
                metric("kafka_server_broker_topic_metrics_bytesinpersec_total", "counter",
                        "bytes", "broker", List.of(), List.of(), vmPref(), vmOnly()),
                metric("kafka_network_request_channel_requestqueuesize_value", "gauge",
                        "count", "broker", List.of(), List.of(), vmPref(), vmOnly()),
                metric("kafka_server_replicamanager_produceridcount_value", "gauge",
                        "count", "broker", List.of(), List.of(), vmPref(), vmOnly()));
        return new ComponentCatalogEntry("kafka", "kafka.broker", metrics,
                logStreams("kafka", null), List.of(), CatalogStatus.IMPLEMENTED, List.of());
    }

    private ComponentCatalogEntry kafkaController() {
        List<MetricDefinition> metrics = List.of(
                metric("kafka_controller_activebrokercount", "gauge", "count", "controller",
                        List.of(), List.of(), vmPref(), vmOnly()));
        return new ComponentCatalogEntry("kafka", "kafka.controller", metrics,
                logStreams("kafka", null), List.of(), CatalogStatus.IMPLEMENTED, List.of());
    }

    private ComponentCatalogEntry yarnResourceManager() {
        List<MetricDefinition> metrics = List.of(
                metric("yarn_cluster_apps_submitted_total", "counter", "count", "cluster",
                        List.of(), List.of(), vmPref(), vmOnly()),
                metric("yarn_cluster_memory_total_bytes", "gauge", "bytes", "cluster",
                        List.of(), List.of(), vmPref(), vmOnly()),
                metric("yarn_cluster_nodes_active", "gauge", "count", "cluster",
                        List.of(), List.of(), vmPref(), vmOnly()));
        return new ComponentCatalogEntry("yarn", "yarn.resourcemanager", metrics,
                List.of(), List.of(), CatalogStatus.IMPLEMENTED, List.of());
    }

    private ComponentCatalogEntry yarnNodeManager() {
        List<MetricDefinition> metrics = List.of(
                metric("yarn_nodemanager_up", "gauge", "boolean", "node",
                        List.of(), List.of(), vmPref(), vmOnly()),
                metric("yarn_nodemanager_healthy", "gauge", "boolean", "node",
                        List.of(), List.of(), vmPref(), vmOnly()));
        return new ComponentCatalogEntry("yarn", "yarn.nodemanager", metrics,
                List.of(), List.of(), CatalogStatus.IMPLEMENTED, List.of());
    }

    // ------------------------------------------------------------------ 构造辅助

    private static ComponentCatalogEntry declared(String serviceType, String componentId,
                                                  Requirement metricRequirement,
                                                  Requirement slowQueryRequirement) {
        List<LogStreamDefinition> streams = new ArrayList<>(logStreams(serviceType, null));
        if (slowQueryRequirement != null) {
            streams.add(new LogStreamDefinition(LogDataType.SERVICE, SLOWQUERY, List.of(), List.of(),
                    List.of(localDisk(10, BackendStatus.DECLARED_NOT_IMPLEMENTED,
                                    serviceType + "-logs", "需要审计日志/query_log 解析实现"),
                            elasticsearch(20, BackendStatus.DECLARED_NOT_IMPLEMENTED,
                                    serviceType + "-logs", "需要审计日志/query_log 索引")),
                    List.of(slowQueryRequirement)));
        }
        return new ComponentCatalogEntry(serviceType, componentId, List.of(), streams, List.of(),
                CatalogStatus.DECLARED_NOT_IMPLEMENTED, List.of(metricRequirement));
    }

    private static Requirement api(String key, String description) {
        return new Requirement(RequirementKind.API, key, description);
    }

    private static Requirement slowQueryRequirement(String description) {
        return new Requirement(RequirementKind.CONFIG,
                "telemetry.backends.local-disk[].sources[stream=slowquery]", description);
    }

    /**
     * 一个组件的四类日志流：服务日志与任务日志各自声明，后端不交叉。
     *
     * <p>服务日志挂 local-disk / ES；任务日志只挂 YARN，并要求 {@code taskId} 或 {@code jobName}
     * 至少提供一个（{@code requiredAnyRefs}）。slowquery 只在服务日志分类下声明。</p>
     */
    private static List<LogStreamDefinition> logStreams(String serviceType, String serviceRef) {
        List<String> refs = serviceRef == null ? List.of() : List.of(serviceRef);
        return List.of(
                serviceLogStream(serviceType, STDOUT, refs),
                serviceLogStream(serviceType, STDERR, refs),
                taskLogStream(STDOUT),
                taskLogStream(STDERR));
    }

    private static LogStreamDefinition serviceLogStream(String serviceType, String stream,
                                                        List<String> requiredRefs) {
        String group = serviceType + "-logs";
        return new LogStreamDefinition(LogDataType.SERVICE, stream, requiredRefs, List.of(),
                List.of(localDisk(10, BackendStatus.IMPLEMENTED, group, null),
                        elasticsearch(20, BackendStatus.IMPLEMENTED, group, null)),
                List.of());
    }

    private static LogStreamDefinition taskLogStream(String stream) {
        return new LogStreamDefinition(LogDataType.TASK, stream, List.of(), List.of("taskId", "jobName"),
                List.of(yarn(10)),
                List.of(new Requirement(RequirementKind.CONFIG, "telemetry.backends.yarn[]",
                        "配置 YARN RM 地址与 yarn 命令后可按 log.task 查询聚合日志")));
    }

    private static MetricDefinition metric(String name, String type, String unit, String scope,
                                           List<LabelDefinition> labels, List<String> requiredRefs,
                                           Map<String, List<String>> preference,
                                           BackendBinding[] backends) {
        return new MetricDefinition(name, type, unit, scope, labels, requiredRefs, preference,
                List.of(backends));
    }

    /** 带引擎原始指标来源的目录项；raw 名必须能在对应端点上真实取到。 */
    private static MetricDefinition rawMetric(String name, String type, String unit, String scope,
                                              List<LabelDefinition> labels, List<String> requiredRefs,
                                              Map<String, List<String>> preference,
                                              BackendBinding[] backends, String rawMetric,
                                              String endpoint, boolean verified, String note) {
        return new MetricDefinition(name, type, unit, scope, labels, requiredRefs, preference,
                List.of(backends), rawMetric, endpoint, verified, note);
    }

    private static List<LabelDefinition> executorLabels() {
        return labels(label("application_id", true, "Spark 应用 ID"),
                label("executor_id", true, "Executor ID"));
    }

    private static List<LabelDefinition> labels(LabelDefinition... labels) {
        return List.of(labels);
    }

    private static LabelDefinition label(String name, boolean required, String description) {
        return new LabelDefinition(name, required, description);
    }

    private static Map<String, List<String>> pref(List<String> instant, List<String> range) {
        return Map.of("instant", instant, "range", range);
    }

    private static Map<String, List<String>> sparkPref() {
        return pref(List.of("spark-rest", "prometheus", "victoriametrics"),
                List.of("prometheus", "victoriametrics"));
    }

    private static BackendBinding[] checkpointBackends() {
        return new BackendBinding[]{
                flinkRest(10, BOTH, Set.of(Sampling.RAW), BackendStatus.IMPLEMENTED,
                        "checkpoint history 是离散事件序列，不做等步长插值"),
                prometheus(20, false),
                victoriametrics(30, false)};
    }

    private static BackendBinding[] throughputBackends() {
        return new BackendBinding[]{
                prometheus(10, true),
                victoriametrics(20, false)};
    }

    private static BackendBinding[] jvmBackends() {
        return new BackendBinding[]{
                prometheus(10, false),
                victoriametrics(20, false)};
    }

    private static BackendBinding[] vertexBackends() {
        return new BackendBinding[]{
                flinkRest(10, INSTANT, STEP, BackendStatus.IMPLEMENTED,
                        "背压只有当前快照，REST 不支持 range"),
                prometheus(20, false),
                victoriametrics(30, false)};
    }

    private static BackendBinding[] sparkBackends() {
        return new BackendBinding[]{
                sparkRest(10),
                prometheus(20, false),
                victoriametrics(30, false)};
    }

    private static BackendBinding[] vmOnly() {
        return new BackendBinding[]{victoriametrics(10, true)};
    }

    private static Map<String, List<String>> vmPref() {
        return pref(List.of("victoriametrics"), List.of("victoriametrics"));
    }

    private static BackendBinding prometheus(int priority, boolean authoritative) {
        return new BackendBinding("prometheus", BackendType.PROMETHEUS, BackendStatus.IMPLEMENTED,
                priority, BOTH, STEP, authoritative, null,
                "PromQL 历史序列；未配置 reporter 时指标缺席，不补 0");
    }

    private static BackendBinding victoriametrics(int priority, boolean authoritative) {
        return new BackendBinding("victoriametrics", BackendType.VICTORIAMETRICS, BackendStatus.IMPLEMENTED,
                priority, BOTH, STEP, authoritative, null,
                "与 Prometheus 默认不互为等价组，不会自动互相回退");
    }

    private static BackendBinding flinkRest(int priority, Set<QueryMode> modes, Set<Sampling> sampling,
                                            BackendStatus status, String notes) {
        return new BackendBinding("flink-rest", BackendType.FLINK_REST, status, priority,
                modes, sampling, true, null, notes);
    }

    private static BackendBinding sparkRest(int priority) {
        return new BackendBinding("spark-rest", BackendType.SPARK_REST, BackendStatus.IMPLEMENTED,
                priority, INSTANT, Set.of(Sampling.RAW), true, null,
                "SHS 是完成态快照，只支持 instant；live 用 mode=live");
    }

    private static BackendBinding localDisk(int priority, BackendStatus status, String group, String notes) {
        return new BackendBinding("local-disk", BackendType.LOCAL_DISK, status, priority,
                Set.of(), Set.of(), false, group, notes);
    }

    private static BackendBinding elasticsearch(int priority, BackendStatus status, String group, String notes) {
        return new BackendBinding("es-logs", BackendType.ELASTICSEARCH, status, priority,
                Set.of(), Set.of(), false, group, notes);
    }

    /** YARN 聚合日志：只承载 log.task；不声明 equivalenceGroup，永远不与服务日志互相回退。 */
    private static BackendBinding yarn(int priority) {
        return new BackendBinding("yarn", BackendType.YARN, BackendStatus.IMPLEMENTED, priority,
                Set.of(), Set.of(), false, null,
                "YARN 聚合日志；按 ref.taskId 或 ref.jobName 定位，taskId 优先");
    }
}
