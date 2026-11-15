package com.bigdata.modules.telemetry.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务组件目录：五层模型第二层的规范 ID。
 *
 * <p><b>服务类型只收敛到 agentkit 的九个引擎</b>，两边标识必须一致：</p>
 * <ul>
 *   <li>{@code file} 只是 transport（旧 {@code ServiceType.FILE} 保留为兼容别名，不进能力树）；</li>
 *   <li>{@code ds}/{@code yarn} 是调度与资源管理的接入方式，不是引擎服务类型，不进能力树，
 *       其日志继续由旧 {@code /api/logs/*} 提供；</li>
 *   <li>{@code hudi} 是表格式，没有独立服务组件：channels 为空，证据 handoff 给 spark，
 *       对应 agentkit 的「channels 为空 + handoff 给 spark」表述，不要当成漏项。</li>
 * </ul>
 *
 * <p>Phase 1 只提供组件清单，host 统一为 {@code cluster}；host inventory 单列一步，
 * 等 telemetry 骨架稳定后再补。</p>
 */
public final class ServiceComponentCatalog {

    /** Phase 1 的 host 占位值：表示数据不归属单台主机（REST/SHS/聚合查询）。 */
    public static final String CLUSTER_HOST = "cluster";

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

    private static Map<String, List<String>> build() {
        Map<String, List<String>> catalog = new LinkedHashMap<>();
        catalog.put("spark", List.of("spark.driver", "spark.executor"));
        catalog.put("flink", List.of("flink.jobmanager", "flink.taskmanager"));
        catalog.put("doris", List.of("doris.fe", "doris.be"));
        catalog.put("starrocks", List.of("starrocks.fe", "starrocks.be", "starrocks.cn"));
        catalog.put("clickhouse", List.of("clickhouse.server"));
        catalog.put("hbase", List.of("hbase.master", "hbase.regionserver"));
        // Hudi：表格式，无独立服务组件；channels 为空，证据 handoff 给 spark。
        catalog.put("hudi", List.of());
        catalog.put("kafka", List.of("kafka.broker", "kafka.controller"));
        catalog.put("pulsar", List.of("pulsar.broker", "pulsar.bookkeeper", "pulsar.proxy"));
        // YARN：不作为引擎服务进入调度域，但 RM/NM 的集群指标需要独立 target。
        catalog.put("yarn", List.of("yarn.resourcemanager", "yarn.nodemanager"));
        return java.util.Collections.unmodifiableMap(catalog);
    }
}
