package com.bigdata.modules.runtime.model;

import com.bigdata.modules.logs.model.ServiceType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标对照表（代码内的白名单）。
 *
 * <p>默认只按族取这些指标；调用方也可通过 {@code metrics} 显式指定原始指标名。
 * 标注 {@code verified=false} 的条目表示指标名/通配符行为随版本变化，需要在真实环境核对。</p>
 *
 * <h3>Flink metrics 端点行为</h3>
 * <ul>
 *   <li>{@code get=} 必须是逗号分隔的完整指标名；同一个请求里重复写多个 {@code get} 参数的行为随版本不同，
 *       本实现统一合并为一个 {@code get=}。</li>
 *   <li>支持 {@code *} 通配（如 {@code taskmanager_job_task_operator_*}），但会把该作用域下所有匹配项一次返回，
 *       数量不可控；因此默认只对 GC 这类数量固定的族使用通配符，不对 operator 全量使用。</li>
 *   <li>未注册/拼错的指标名不会报错，通常直接缺席；适配器据此标记 {@code UNAVAILABLE}，而不是当成 0。</li>
 *   <li>job 级指标只能在 {@code /jobs/{id}/metrics} 取，TM/operator 指标必须到 TM 或 vertex/subtask 端点取，
 *       作用域混用会拿不到值。</li>
 * </ul>
 */
public final class MetricCatalog {

    public static final String UNIT_BYTES = "bytes";
    public static final String UNIT_MS = "ms";
    public static final String UNIT_COUNT = "count";
    public static final String UNIT_RECORDS = "records";
    public static final String UNIT_RECORDS_PER_SECOND = "records_per_second";
    public static final String UNIT_BYTES_PER_SECOND = "bytes_per_second";
    public static final String UNIT_RATIO = "ratio";

    private static final String FLINK_TM_METRICS =
            "/taskmanagers/{id}/metrics?get=<metrics>";
    private static final String FLINK_JOB_METRICS =
            "/jobs/{id}/metrics?get=<metrics>";
    private static final String FLINK_VERTEX_METRICS =
            "/jobs/{id}/vertices/{vid}/subtasks/metrics?get=<metrics>";
    private static final String FLINK_CHECKPOINTS =
            "/jobs/{id}/checkpoints";
    private static final String FLINK_JOB =
            "/jobs/{id}";
    private static final String SPARK_EXECUTORS =
            "/api/v1/applications/{appId}/executors（live）/ allexecutors（history）";
    private static final String SPARK_STAGES =
            "/api/v1/applications/{appId}/stages";
    private static final String SPARK_JOBS =
            "/api/v1/applications/{appId}/jobs";

    private static final List<MetricSpec> FLINK = new ArrayList<>();
    private static final List<MetricSpec> SPARK = new ArrayList<>();
    private static final Map<ServiceType, List<MetricSpec>> BY_SERVICE = new EnumMap<>(ServiceType.class);

    static {
        // ---------------- Flink: JVM / GC（TaskManager 级） ----------------
        flink(MetricFamily.JVM_GC, "jvm.heap.used", "Status.JVM.Memory.Heap.Used",
                MetricScope.TASKMANAGER, FLINK_TM_METRICS, UNIT_BYTES, true, "堆已用字节");
        flink(MetricFamily.JVM_GC, "jvm.heap.max", "Status.JVM.Memory.Heap.Max",
                MetricScope.TASKMANAGER, FLINK_TM_METRICS, UNIT_BYTES, true, "堆上限字节");
        flink(MetricFamily.JVM_GC, "jvm.nonheap.used", "Status.JVM.Memory.NonHeap.Used",
                MetricScope.TASKMANAGER, FLINK_TM_METRICS, UNIT_BYTES, true, "非堆已用字节");
        flink(MetricFamily.JVM_GC, "jvm.gc.count", "Status.JVM.GarbageCollector.*.Count",
                MetricScope.TASKMANAGER, FLINK_TM_METRICS, UNIT_COUNT, false,
                "按具体 collector 通配后求和；版本不支持通配符时标记 UNAVAILABLE");
        flink(MetricFamily.JVM_GC, "jvm.gc.time", "Status.JVM.GarbageCollector.*.Time",
                MetricScope.TASKMANAGER, FLINK_TM_METRICS, UNIT_MS, false,
                "GC 累计耗时，单位毫秒；新版另有 TimeMs，单位一致");
        flink(MetricFamily.JVM_GC, "jvm.threads", "Status.JVM.Threads.Count",
                MetricScope.TASKMANAGER, FLINK_TM_METRICS, UNIT_COUNT, true, "JVM 线程数");

        // ---------------- Flink: checkpoint ----------------
        flink(MetricFamily.CHECKPOINT, "checkpoint.last.duration", "lastCheckpointDuration",
                MetricScope.JOB, FLINK_JOB_METRICS, UNIT_MS, true, "最近一次成功 checkpoint 耗时");
        flink(MetricFamily.CHECKPOINT, "checkpoint.last.size", "lastCheckpointSize",
                MetricScope.JOB, FLINK_JOB_METRICS, UNIT_BYTES, true, "最近一次 checkpoint 大小");
        flink(MetricFamily.CHECKPOINT, "checkpoint.last.fullSize", "lastCheckpointFullSize",
                MetricScope.JOB, FLINK_JOB_METRICS, UNIT_BYTES, true, "最近一次 checkpoint 全量大小");
        flink(MetricFamily.CHECKPOINT, "checkpoint.completed.count", "numberOfCompletedCheckpoints",
                MetricScope.JOB, FLINK_JOB_METRICS, UNIT_COUNT, true, "成功 checkpoint 次数");
        flink(MetricFamily.CHECKPOINT, "checkpoint.failed.count", "numberOfFailedCheckpoints",
                MetricScope.JOB, FLINK_JOB_METRICS, UNIT_COUNT, true, "失败 checkpoint 次数");
        flink(MetricFamily.CHECKPOINT, "checkpoint.history", "history/latest",
                MetricScope.JOB, FLINK_CHECKPOINTS, UNIT_COUNT, true,
                "由 /checkpoints 的 counts/latest/history 组装，含失败原因与最近 N 次");

        // ---------------- Flink: 吞吐与记录数 ----------------
        flink(MetricFamily.THROUGHPUT, "records.in.total", "numRecordsIn",
                MetricScope.JOB, FLINK_JOB_METRICS, UNIT_RECORDS, true, "作业累计读取记录数");
        flink(MetricFamily.THROUGHPUT, "records.out.total", "numRecordsOut",
                MetricScope.JOB, FLINK_JOB_METRICS, UNIT_RECORDS, true, "作业累计写出记录数");
        flink(MetricFamily.THROUGHPUT, "bytes.in.total", "numBytesIn",
                MetricScope.JOB, FLINK_JOB_METRICS, UNIT_BYTES, true, "作业累计读取字节数");
        flink(MetricFamily.THROUGHPUT, "bytes.out.total", "numBytesOut",
                MetricScope.JOB, FLINK_JOB_METRICS, UNIT_BYTES, true, "作业累计写出字节数");
        flink(MetricFamily.THROUGHPUT, "records.in.rate", "numRecordsInPerSecond",
                MetricScope.JOB, FLINK_JOB_METRICS, UNIT_RECORDS_PER_SECOND, true, "读取速率");
        flink(MetricFamily.THROUGHPUT, "records.out.rate", "numRecordsOutPerSecond",
                MetricScope.JOB, FLINK_JOB_METRICS, UNIT_RECORDS_PER_SECOND, true, "写出速率");
        flink(MetricFamily.THROUGHPUT, "bytes.in.rate", "numBytesInPerSecond",
                MetricScope.JOB, FLINK_JOB_METRICS, UNIT_BYTES_PER_SECOND, true, "读取字节速率");
        flink(MetricFamily.THROUGHPUT, "bytes.out.rate", "numBytesOutPerSecond",
                MetricScope.JOB, FLINK_JOB_METRICS, UNIT_BYTES_PER_SECOND, true, "写出字节速率");
        flink(MetricFamily.THROUGHPUT, "records.in.total", "numRecordsIn",
                MetricScope.VERTEX, FLINK_VERTEX_METRICS, UNIT_RECORDS, true, "按 vertex 汇总读取记录数");
        flink(MetricFamily.THROUGHPUT, "records.out.total", "numRecordsOut",
                MetricScope.VERTEX, FLINK_VERTEX_METRICS, UNIT_RECORDS, true, "按 vertex 汇总写出记录数");
        flink(MetricFamily.THROUGHPUT, "records.in.rate", "numRecordsInPerSecond",
                MetricScope.VERTEX, FLINK_VERTEX_METRICS, UNIT_RECORDS_PER_SECOND, true, "按 vertex 汇总读取速率");
        flink(MetricFamily.THROUGHPUT, "records.out.rate", "numRecordsOutPerSecond",
                MetricScope.VERTEX, FLINK_VERTEX_METRICS, UNIT_RECORDS_PER_SECOND, true, "按 vertex 汇总写出速率");

        // ---------------- Flink: 背压 / 延迟 ----------------
        flink(MetricFamily.BACKPRESSURE, "backpressure.busy", "busyTimeMsPerSecond",
                MetricScope.VERTEX, FLINK_VERTEX_METRICS, UNIT_MS, false,
                "Flink 1.14+ 任务级；旧版本为 busyTime 比例，需现场核对");
        flink(MetricFamily.BACKPRESSURE, "backpressure.backpressured", "backPressuredTimeMsPerSecond",
                MetricScope.VERTEX, FLINK_VERTEX_METRICS, UNIT_MS, false,
                "Flink 1.14+ 任务级；旧版本为 backPressured 比例");
        flink(MetricFamily.BACKPRESSURE, "backpressure.idle", "idleTimeMsPerSecond",
                MetricScope.VERTEX, FLINK_VERTEX_METRICS, UNIT_MS, false,
                "Flink 1.14+ 任务级；旧版本为 idle 比例");

        // ---------------- Flink: shuffle / 网络 ----------------
        flink(MetricFamily.SHUFFLE, "shuffle.bytes.in.local", "numBytesInLocal",
                MetricScope.VERTEX, FLINK_VERTEX_METRICS, UNIT_BYTES, true, "本地输入字节");
        flink(MetricFamily.SHUFFLE, "shuffle.bytes.in.remote", "numBytesInRemote",
                MetricScope.VERTEX, FLINK_VERTEX_METRICS, UNIT_BYTES, true, "远端输入字节（网络 shuffle）");
        flink(MetricFamily.SHUFFLE, "shuffle.bytes.out", "numBytesOut",
                MetricScope.VERTEX, FLINK_VERTEX_METRICS, UNIT_BYTES, true, "输出字节");

        // ---------------- Flink: 失败与重启 ----------------
        flink(MetricFamily.FAILURES, "restarts", "numRestarts",
                MetricScope.JOB, FLINK_JOB_METRICS, UNIT_COUNT, true, "作业重启次数");
        flink(MetricFamily.FAILURES, "tasks.failed", "tasks.failed",
                MetricScope.JOB, FLINK_JOB, UNIT_COUNT, true, "来自 /jobs/{id}.tasks.failed，不通过 metrics 端点");
        flink(MetricFamily.FAILURES, "vertex.tasks.failed", "tasks.failed",
                MetricScope.VERTEX, FLINK_JOB, UNIT_COUNT, true, "来自 /jobs/{id}.vertices[].tasks.failed");

        // ---------------- Spark: JVM / GC（executor 级） ----------------
        spark(MetricFamily.JVM_GC, "executor.gc.time", "totalGCTime",
                MetricScope.EXECUTOR, SPARK_EXECUTORS, UNIT_MS, true, "executor 累计 GC 时间，毫秒");
        spark(MetricFamily.JVM_GC, "executor.run.time", "totalDuration",
                MetricScope.EXECUTOR, SPARK_EXECUTORS, UNIT_MS, true, "executor 累计运行时间，毫秒");
        spark(MetricFamily.JVM_GC, "executor.gc.ratio", "totalGCTime/totalDuration",
                MetricScope.EXECUTOR, SPARK_EXECUTORS, UNIT_RATIO, true, "GC 时间占比，实时计算");
        spark(MetricFamily.JVM_GC, "executor.memory.used", "memoryUsed",
                MetricScope.EXECUTOR, SPARK_EXECUTORS, UNIT_BYTES, true, "executor 已用内存字节");
        spark(MetricFamily.JVM_GC, "executor.memory.max", "maxMemory",
                MetricScope.EXECUTOR, SPARK_EXECUTORS, UNIT_BYTES, true, "executor 最大内存字节");
        spark(MetricFamily.JVM_GC, "executor.cores", "totalCores",
                MetricScope.EXECUTOR, SPARK_EXECUTORS, UNIT_COUNT, true, "executor 核数");
        spark(MetricFamily.JVM_GC, "executor.activeTasks", "activeTasks",
                MetricScope.EXECUTOR, SPARK_EXECUTORS, UNIT_COUNT, true, "当前活跃 task 数");

        // ---------------- Spark: 吞吐与记录数 ----------------
        spark(MetricFamily.THROUGHPUT, "stage.input.records", "inputRecords",
                MetricScope.STAGE, SPARK_STAGES, UNIT_RECORDS, true, "stage 输入记录数");
        spark(MetricFamily.THROUGHPUT, "stage.output.records", "outputRecords",
                MetricScope.STAGE, SPARK_STAGES, UNIT_RECORDS, true, "stage 输出记录数");
        spark(MetricFamily.THROUGHPUT, "stage.input.bytes", "inputBytes",
                MetricScope.STAGE, SPARK_STAGES, UNIT_BYTES, true, "stage 输入字节");
        spark(MetricFamily.THROUGHPUT, "stage.output.bytes", "outputBytes",
                MetricScope.STAGE, SPARK_STAGES, UNIT_BYTES, true, "stage 输出字节");
        spark(MetricFamily.THROUGHPUT, "executor.input.bytes", "totalInputBytes",
                MetricScope.EXECUTOR, SPARK_EXECUTORS, UNIT_BYTES, true, "executor 累计读取字节");
        spark(MetricFamily.FAILURES, "executor.tasks.failed", "failedTasks",
                MetricScope.EXECUTOR, SPARK_EXECUTORS, UNIT_COUNT, true, "executor 失败 task 数");
        spark(MetricFamily.FAILURES, "executor.tasks.completed", "completedTasks",
                MetricScope.EXECUTOR, SPARK_EXECUTORS, UNIT_COUNT, true, "executor 完成 task 数");
        spark(MetricFamily.FAILURES, "executor.tasks.total", "totalTasks",
                MetricScope.EXECUTOR, SPARK_EXECUTORS, UNIT_COUNT, true, "executor 累计 task 数");

        // ---------------- Spark: shuffle ----------------
        spark(MetricFamily.SHUFFLE, "stage.shuffle.read.records", "shuffleReadRecords",
                MetricScope.STAGE, SPARK_STAGES, UNIT_RECORDS, true, "shuffle 读取记录数");
        spark(MetricFamily.SHUFFLE, "stage.shuffle.read.bytes", "shuffleReadBytes",
                MetricScope.STAGE, SPARK_STAGES, UNIT_BYTES, true, "shuffle 读取字节");
        spark(MetricFamily.SHUFFLE, "stage.shuffle.write.records", "shuffleWriteRecords",
                MetricScope.STAGE, SPARK_STAGES, UNIT_RECORDS, true, "shuffle 写出记录数");
        spark(MetricFamily.SHUFFLE, "stage.shuffle.write.bytes", "shuffleWriteBytes",
                MetricScope.STAGE, SPARK_STAGES, UNIT_BYTES, true, "shuffle 写出字节");
        spark(MetricFamily.SHUFFLE, "stage.shuffle.fetch.wait", "shuffleFetchWaitTime",
                MetricScope.STAGE, SPARK_STAGES, UNIT_MS, true, "shuffle 拉取等待时间");
        spark(MetricFamily.SHUFFLE, "stage.shuffle.write.time", "shuffleWriteTime",
                MetricScope.STAGE, SPARK_STAGES, UNIT_MS, true, "shuffle 写盘时间");

        // ---------------- Spark: 失败与重试 ----------------
        spark(MetricFamily.FAILURES, "stage.tasks.failed", "numFailedTasks",
                MetricScope.STAGE, SPARK_STAGES, UNIT_COUNT, true, "stage 失败 task 数");
        spark(MetricFamily.FAILURES, "stage.tasks.killed", "numKilledTasks",
                MetricScope.STAGE, SPARK_STAGES, UNIT_COUNT, true, "stage 被杀 task 数");
        spark(MetricFamily.FAILURES, "stage.attempt.count", "attemptId",
                MetricScope.STAGE, SPARK_STAGES, UNIT_COUNT, true, "attemptId+1 即该 stage 的尝试次数（重试）");
        spark(MetricFamily.FAILURES, "job.tasks.failed", "numFailedTasks",
                MetricScope.JOB, SPARK_JOBS, UNIT_COUNT, true, "作业汇总失败 task 数");
        spark(MetricFamily.FAILURES, "job.tasks.killed", "numKilledTasks",
                MetricScope.JOB, SPARK_JOBS, UNIT_COUNT, true, "作业汇总被杀 task 数");
        spark(MetricFamily.FAILURES, "application.attempt.count", "attempts",
                MetricScope.JOB, "/api/v1/applications/{appId}", UNIT_COUNT, true,
                "attempts 数组长度即应用重试次数");

        BY_SERVICE.put(ServiceType.FLINK, Collections.unmodifiableList(FLINK));
        BY_SERVICE.put(ServiceType.SPARK, Collections.unmodifiableList(SPARK));
    }

    private MetricCatalog() {
    }

    private static void flink(MetricFamily family, String name, String raw, MetricScope scope,
                              String endpoint, String unit, boolean verified, String note) {
        FLINK.add(new MetricSpec(family, name, raw, scope, endpoint, unit, verified, note));
    }

    private static void spark(MetricFamily family, String name, String raw, MetricScope scope,
                              String endpoint, String unit, boolean verified, String note) {
        SPARK.add(new MetricSpec(family, name, raw, scope, endpoint, unit, verified, note));
    }

    public static List<MetricSpec> definitions(ServiceType serviceType) {
        return BY_SERVICE.getOrDefault(serviceType, Collections.emptyList());
    }

    public static List<MetricSpec> forFamily(ServiceType serviceType, MetricFamily family) {
        List<MetricSpec> result = new ArrayList<>();
        for (MetricSpec spec : definitions(serviceType)) {
            if (spec.getFamily() == family) {
                result.add(spec);
            }
        }
        return result;
    }

    public static List<MetricSpec> forScope(ServiceType serviceType, MetricFamily family,
                                            MetricScope scope) {
        List<MetricSpec> result = new ArrayList<>();
        for (MetricSpec spec : definitions(serviceType)) {
            if (spec.getFamily() == family && spec.getScope() == scope) {
                result.add(spec);
            }
        }
        return result;
    }

    public static boolean supports(ServiceType serviceType, MetricFamily family) {
        return !forFamily(serviceType, family).isEmpty();
    }

    /** 该服务当前无法提供的指标族及原因（用于 /sources 如实标注） */
    public static Map<String, String> unavailableFamilies(ServiceType serviceType) {
        Map<String, String> result = new LinkedHashMap<>();
        if (serviceType == ServiceType.SPARK) {
            result.put(MetricFamily.CHECKPOINT.wireName(),
                    "Spark History Server / live UI REST 不提供 checkpoint 指标；"
                            + "流式 checkpoint 需 StreamingQueryProgress，不在 REST 端点内");
            result.put(MetricFamily.BACKPRESSURE.wireName(),
                    "Spark REST 不提供背压指标；可用 stage.shuffle.fetch.wait / task 时长近似");
        }
        return result;
    }

    /** 该服务支持的指标族 */
    public static List<MetricFamily> supportedFamilies(ServiceType serviceType) {
        List<MetricFamily> result = new ArrayList<>();
        for (MetricFamily family : MetricFamily.values()) {
            if (supports(serviceType, family)) {
                result.add(family);
            }
        }
        return result;
    }
}
