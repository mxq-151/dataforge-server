package com.bigdata.modules.telemetry.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 服务组件目录：五层模型第二层的规范 ID。
 *
 * <p><b>「九引擎」约束只针对可诊断引擎</b>（{@link #DIAGNOSABLE_ENGINES}），两边标识必须一致：</p>
 * <ul>
 *   <li>{@code file} 只是 transport（旧 {@code ServiceType.FILE} 保留为兼容别名，不进能力树）；</li>
 *   <li>{@code ds}/{@code yarn} 是调度与资源管理的接入方式，不是引擎服务类型，不进诊断能力树；
 *       {@code ds} 只声明任务日志来源（{@code ds.worker} + {@code log.task}），
 *       {@code yarn} 只声明集群指标 target，两者都不进诊断路由；</li>
 *   <li>{@code hudi} 是表格式，没有独立服务组件：channels 为空，证据 handoff 给 spark，
 *       对应 agentkit 的「channels 为空 + handoff 给 spark」表述，不要当成漏项。</li>
 * </ul>
 *
 * <p><b>服务目录比可诊断引擎宽</b>：{@code yarn} 需要独立的指标 target；
 * {@code ds} 只提供任务日志来源；{@code hdfs}/{@code hive}/{@code zookeeper} 只服务
 * 「服务版本与配置查询」（metadata-only），不声明指标 / 日志 / 引擎证据，也不进诊断路由。</p>
 *
 * <p>Phase 1 只提供组件清单，host 统一为 {@code cluster}；host inventory 单列一步，
 * 等 telemetry 骨架稳定后再补。</p>
 */
public final class ServiceComponentCatalog {

    /** Phase 1 的 host 占位值：表示数据不归属单台主机（REST/SHS/聚合查询）。 */
    public static final String CLUSTER_HOST = "cluster";

    /** 可诊断引擎子集：指标 / 日志 / 运行时诊断路由只接受这些服务类型。 */
    public static final Set<String> DIAGNOSABLE_ENGINES = Set.of(
            "spark", "flink", "doris", "starrocks", "clickhouse", "hbase", "hudi",
            "kafka", "pulsar");

    /**
     * metadata-only 服务：只为「服务版本与配置查询」提供 serviceType / componentId，
     * 没有 engineEvidence、不进诊断路由。
     */
    private static final Set<String> METADATA_ONLY_SERVICES = Set.of("hdfs", "hive", "zookeeper");

    private static final Map<String, List<String>> COMPONENTS = build();

    private ServiceComponentCatalog() {
    }

    public static Map<String, List<String>> components() {
        return COMPONENTS;
    }

    public static List<String> componentsOf(String serviceType) {
        return COMPONENTS.getOrDefault(serviceType, List.of());
    }

    public static boolean supports(String serviceType, String componentId) {
        return componentsOf(serviceType).contains(componentId);
    }

    /** 是否属于可诊断引擎（九引擎）。 */
    public static boolean isDiagnosableEngine(String serviceType) {
        return DIAGNOSABLE_ENGINES.contains(serviceType);
    }

    /** 是否只用于 metadata 查询（无指标 / 日志 / 引擎证据，不进诊断路由）。 */
    public static boolean isMetadataOnly(String serviceType) {
        return METADATA_ONLY_SERVICES.contains(serviceType);
    }

    private static Map<String, List<String>> build() {
        Map<String, List<String>> catalog = new LinkedHashMap<>();
        catalog.put("spark", List.of("spark.driver", "spark.executor", "spark.shs"));
        catalog.put("flink", List.of("flink.jobmanager", "flink.taskmanager"));
        catalog.put("doris", List.of("doris.fe", "doris.be"));
        catalog.put("starrocks", List.of("starrocks.fe", "starrocks.be", "starrocks.cn"));
        catalog.put("clickhouse", List.of("clickhouse.server"));
        catalog.put("hbase", List.of("hbase.master", "hbase.regionserver"));
        // Hadoop 生态服务：metadata-only，只服务版本与配置查询。
        catalog.put("hdfs", List.of("hdfs.namenode", "hdfs.datanode", "hdfs.journalnode",
                "hdfs.zkfc"));
        catalog.put("hive", List.of("hive.hiveserver2", "hive.metastore"));
        catalog.put("zookeeper", List.of("zookeeper.server"));

        // Hudi：表格式，无独立服务组件；channels 为空，证据 handoff 给 spark。
        catalog.put("hudi", List.of());
        catalog.put("kafka", List.of("kafka.broker", "kafka.controller"));
        catalog.put("pulsar", List.of("pulsar.broker", "pulsar.bookkeeper", "pulsar.proxy"));
        // YARN：不作为引擎服务进入调度域，但 RM/NM 的集群指标需要独立 target。
        catalog.put("yarn", List.of("yarn.resourcemanager", "yarn.nodemanager"));
        // DolphinScheduler：只作为任务日志来源，不进入可诊断引擎子集。
        catalog.put("ds", List.of("ds.worker"));
        return java.util.Collections.unmodifiableMap(catalog);
    }
}
