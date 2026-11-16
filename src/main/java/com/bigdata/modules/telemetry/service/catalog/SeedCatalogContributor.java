package com.bigdata.modules.telemetry.service.catalog;

import com.bigdata.modules.telemetry.common.model.Requirement;
import com.bigdata.modules.telemetry.common.model.RequirementKind;
import com.bigdata.modules.telemetry.common.model.BackendBinding;
import com.bigdata.modules.telemetry.common.model.BackendStatus;
import com.bigdata.modules.telemetry.common.model.BackendType;
import com.bigdata.modules.telemetry.service.logdata.model.LogDataType;
import com.bigdata.modules.telemetry.service.logdata.model.LogStreamDefinition;
import com.bigdata.modules.telemetry.common.model.QueryMode;
import com.bigdata.modules.telemetry.common.model.Sampling;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.bigdata.modules.telemetry.service.metricdata.catalog.FlinkMetricSemantics;
import com.bigdata.modules.telemetry.service.metricdata.catalog.SparkShsMetricSemantics;
import com.bigdata.modules.telemetry.vo.CatalogStatus;
import com.bigdata.modules.telemetry.vo.ComponentCatalogEntry;
import com.bigdata.modules.telemetry.vo.ComponentCatalogEntry.EngineEvidenceDefinition;
import com.bigdata.modules.telemetry.service.catalog.spi.CatalogContributor;

/**
 * 身份目录种子：只回答环境里有哪些服务、组件、host/role 与 dataType。
 *
 * <p>指标语义（名称、类型、单位、rate 适用性、原始查询方式）只保留在
 * {@link FlinkMetricSemantics} 与 {@link SparkShsMetricSemantics} 中；Spark 进程 JVM
 * 指标走 SHS jmx_exporter + VictoriaMetrics 运行时发现，本类不再包含任何指标物理名。</p>
 */
@Component
public class SeedCatalogContributor implements CatalogContributor {

    private static final String STDOUT = "stdout";
    private static final String STDERR = "stderr";
    private static final String SLOWQUERY = "slowquery";

    private static final Set<QueryMode> INSTANT = Set.of(QueryMode.INSTANT);
    private static final Set<Sampling> STEP = Set.of(Sampling.STEP);

    @Override
    public List<ComponentCatalogEntry> contribute() {
        List<ComponentCatalogEntry> entries = new ArrayList<>();
        entries.add(identity("flink", "flink.jobmanager", logStreams("flink", "jobId"),
                List.of(flinkExceptionEvidence("作业级异常；Flink 2.2 只有列表接口，无单条子资源"))));
        entries.add(identity("flink", "flink.taskmanager", logStreams("flink", "jobId"),
                List.of(flinkExceptionEvidence("作业级异常；Flink 2.2 只有列表接口，无单条子资源"))));
        entries.add(identity("spark", "spark.driver", sparkLogStreams("applicationId"), List.of(),
                List.of(sparkJobMetricGap())));
        entries.add(identity("spark", "spark.executor", sparkLogStreams("applicationId"), List.of(),
                List.of(sparkJobMetricGap())));
        // SHS 进程自身的体检指标由 jmx_exporter 暴露、VM 抓取；作业级指标仍是缺口。
        entries.add(identity("spark", "spark.shs", List.of(), List.of(),
                List.of(sparkJobMetricGap())));

        entries.add(doris("doris.fe", "需要 Doris FE metrics 端点与指标清单"));
        entries.add(doris("doris.be", "需要 Doris BE metrics 端点与指标清单"));
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
        entries.add(identity("kafka", "kafka.broker", logStreams("kafka", null), List.of()));
        entries.add(identity("kafka", "kafka.controller", logStreams("kafka", null), List.of()));
        entries.add(identity("yarn", "yarn.resourcemanager", List.of(), List.of()));
        entries.add(identity("yarn", "yarn.nodemanager", List.of(), List.of()));
        entries.add(dsWorker());
        entries.add(declared("pulsar", "pulsar.broker",
                api("metrics.pulsar.prometheus", "需要 Pulsar Broker Prometheus 端点与指标清单"), null));
        entries.add(declared("pulsar", "pulsar.bookkeeper",
                api("metrics.pulsar.prometheus", "需要 BookKeeper Prometheus 端点与指标清单"), null));
        entries.add(declared("pulsar", "pulsar.proxy",
                api("metrics.pulsar.prometheus", "需要 Pulsar Proxy Prometheus 端点与指标清单"), null));
        return entries;
    }

    // ------------------------------------------------------------------ 身份构造

    private static ComponentCatalogEntry identity(String serviceType, String componentId,
                                                  List<LogStreamDefinition> streams,
                                                  List<EngineEvidenceDefinition> evidence) {
        return identity(serviceType, componentId, streams, evidence, List.of());
    }

    private static ComponentCatalogEntry identity(String serviceType, String componentId,
                                                  List<LogStreamDefinition> streams,
                                                  List<EngineEvidenceDefinition> evidence,
                                                  List<Requirement> metricRequirements) {
        return new ComponentCatalogEntry(serviceType, componentId, List.of(), streams, evidence,
                CatalogStatus.IMPLEMENTED, metricRequirements);
    }

    /**
     * Spark 作业级指标的已知缺口：SHS 只读事件日志，不创建 SparkContext，
     * 因此没有 driver/executor 的运行时时间序列，不能伪装成 JMX 发现。
     */
    private static Requirement sparkJobMetricGap() {
        return new Requirement(RequirementKind.API, "spark.job.metrics",
                "SHS 只有事件日志，没有 driver/executor 的运行时时间序列：运行中作业可查到滞后快照"
                        + "（实测落后 driver live UI 20–50 秒，响应带 SPARK_APP_INCOMPLETE 告警），"
                        + "作业结束后可查终值；range/rate 一律拒绝。要时间序列需抓运行中的 "
                        + "driver/executor JMX 或把事件日志解析落库，本期不做");
    }

    private static EngineEvidenceDefinition flinkExceptionEvidence(String note) {
        return new EngineEvidenceDefinition("exception_summary",
                List.of(new BackendBinding("flink-rest", BackendType.FLINK_REST,
                        BackendStatus.IMPLEMENTED, 10, INSTANT, STEP, true, null, note)));
    }

    /**
     * DolphinScheduler 只作为任务日志来源：不声明指标 / 引擎证据，也不声明服务自身日志，
     * 只提供 {@code log.task} 的 stdout / stderr。
     */
    private static ComponentCatalogEntry dsWorker() {
        List<LogStreamDefinition> streams = List.of(
                dsTaskLogStream(STDOUT),
                dsTaskLogStream(STDERR));
        return new ComponentCatalogEntry("ds", "ds.worker", List.of(), streams, List.of(),
                CatalogStatus.IMPLEMENTED, List.of());
    }

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

    /**
     * Doris 的慢查询已实现两条通道：{@code __internal_schema.audit_log} 优先（doris-audit），
     * FE 的 {@code fe.audit.log} 由 {@code local-disk} 解析作为退路。两者仅在配置显式把
     * {@code doris-slowquery} 等价组同时包含时才允许回退。
     *
     * <p>指标语义由指标后端声明；Doris 当前没有指标查询后端，因此组件状态保持
     * {@code declared_not_implemented}。</p>
     */
    private static ComponentCatalogEntry doris(String componentId, String metricDescription) {
        List<LogStreamDefinition> streams = new ArrayList<>(logStreams("doris", null));
        String group = "doris-slowquery";
        streams.add(new LogStreamDefinition(LogDataType.SERVICE, SLOWQUERY, List.of(), List.of(),
                List.of(dorisAudit(10, group),
                        localDisk(20, BackendStatus.IMPLEMENTED, group,
                                "退路：解析 FE 的 fe.audit.log，时间戳取行首 'AuditLogger ' 后的写入时间"
                                        + "（与行内 Timestamp= 查询开始时间可能差数秒），"
                                        + "慢查询阈值需按 Time(ms)= 自行过滤")),
                List.of(
                        new Requirement(RequirementKind.CONFIG, "telemetry.backends.doris-audit[]",
                                "FE 开启 enable_audit_plugin=true 且 __internal_schema.audit_log 存在时"
                                        + "按 query_time 阈值查询；表不存在则回退 local-disk"),
                        new Requirement(RequirementKind.CONFIG,
                                "telemetry.backends.local-disk[].sources[stream=slowquery]",
                                "退路需要 allow-root-dirs 与指向 fe.audit.log 的 sources；"
                                        + "timestamp-pattern 用 \"'AuditLogger 'yyyy-MM-dd HH:mm:ss,SSS\""))));
        return new ComponentCatalogEntry("doris", componentId, List.of(), streams, List.of(),
                CatalogStatus.DECLARED_NOT_IMPLEMENTED,
                List.of(api("metrics." + componentId, metricDescription)));
    }

    private static Requirement slowQueryRequirement(String description) {
        return new Requirement(RequirementKind.CONFIG,
                "telemetry.backends.local-disk[].sources[stream=slowquery]", description);
    }

    // ------------------------------------------------------------------ 日志流

    /**
     * 一个组件的四类日志流：服务日志与任务日志各自声明，后端不交叉。
     *
     * <p>服务日志挂 local-disk / ES；任务日志挂 YARN，并要求 {@code taskId} 或 {@code jobName}
     * 至少提供一个（{@code requiredAnyRefs}）。DS 任务日志单独声明，要求 {@code jobId} 或
     * {@code jobName}。slowquery 只在服务日志分类下声明。</p>
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
                        "配置 YARN RM 地址与 yarn 命令后可按 log.task 查询聚合日志；"
                                + "聚合未完成时回退 NM 本地目录，需挂载并配置 node-log-root，"
                                + "未挂盘的容器在聚合完成前不可获取")));
    }

    /**
     * Spark 的日志流：服务日志走 local-disk / ES；任务日志唯一通道是 YARN（按 applicationId）。
     * SHS 的 {@code /logs} 是事件日志 zip，不声明为文本日志；standalone / local 模式没有
     * applicationId 与聚合日志，请求会按不可用返回（不会静默缺失）。
     */
    private static List<LogStreamDefinition> sparkLogStreams(String serviceRef) {
        List<String> refs = List.of(serviceRef);
        List<Requirement> requirements = List.of(
                new Requirement(RequirementKind.CONFIG, "telemetry.backends.yarn[]",
                        "Spark 任务日志只从 YARN 取：配置 RM 地址与 yarn 命令，ref.taskId 用 application_*"),
                new Requirement(RequirementKind.API, "spark.shs.logs",
                        "SHS /api/v1/applications/{appId}/logs 是事件日志 zip（实测 122323 字节 deflate），"
                                + "不是文本日志；standalone/local 模式没有聚合日志与 applicationId，"
                                + "因此 Spark 任务 stdout/stderr 无可用通道，按不可用返回"));
        return List.of(
                serviceLogStream("spark", STDOUT, refs),
                serviceLogStream("spark", STDERR, refs),
                new LogStreamDefinition(LogDataType.TASK, STDOUT, List.of(), List.of("taskId", "jobName"),
                        List.of(yarn(10)), requirements),
                new LogStreamDefinition(LogDataType.TASK, STDERR, List.of(), List.of("taskId", "jobName"),
                        List.of(yarn(10)), requirements));
    }

    /** DS 任务日志：jobId 即 taskInstanceId；jobName 不全局唯一，需 workflowInstanceId 消歧。 */
    private static LogStreamDefinition dsTaskLogStream(String stream) {
        return new LogStreamDefinition(LogDataType.TASK, stream, List.of(), List.of("jobId", "jobName"),
                List.of(dsApi(10)),
                List.of(new Requirement(RequirementKind.CONFIG, "telemetry.backends.ds-api[]",
                        "配置 DolphinScheduler base-url 与访问令牌（token）后可按 log.task 查询任务日志")));
    }

    // ------------------------------------------------------------------ 后端绑定

    private static BackendBinding localDisk(int priority, BackendStatus status, String group, String notes) {
        return new BackendBinding("local-disk", BackendType.LOCAL_DISK, status, priority,
                Set.of(), Set.of(), false, group, notes);
    }

    private static BackendBinding elasticsearch(int priority, BackendStatus status, String group, String notes) {
        return new BackendBinding("es-logs", BackendType.ELASTICSEARCH, status, priority,
                Set.of(), Set.of(), false, group, notes);
    }

    /** YARN 容器日志：只承载 log.task；不声明 equivalenceGroup，永远不与服务日志互相回退。 */
    private static BackendBinding yarn(int priority) {
        return new BackendBinding("yarn", BackendType.YARN, BackendStatus.IMPLEMENTED, priority,
                Set.of(), Set.of(), false, null,
                "YARN 容器日志：已聚合走 yarn logs，未聚合回退 NM 本地目录；"
                        + "每条样本带 containerId / nmHost / source(aggregated|node-local)");
    }

    /** DS 任务日志：只承载 log.task；不与 YARN 聚合日志自动互相回退。 */
    private static BackendBinding dsApi(int priority) {
        return new BackendBinding("ds-api", BackendType.DS_API, BackendStatus.IMPLEMENTED, priority,
                Set.of(), Set.of(), false, null,
                "DolphinScheduler 任务日志；ref.jobId 即 taskInstanceId，jobName 需 workflowInstanceId 消歧");
    }

    /** Doris 审计表：固定只读 SQL 查询 __internal_schema.audit_log，无任意 SQL 入口。 */
    private static BackendBinding dorisAudit(int priority, String group) {
        return new BackendBinding("doris-audit", BackendType.DORIS_AUDIT,
                BackendStatus.IMPLEMENTED, priority, Set.of(), Set.of(), false, group,
                "查询 __internal_schema.audit_log，按 query_time 阈值与时间窗过滤；"
                        + "需 FE enable_audit_plugin=true，表不存在时回退 local-disk");
    }
}
