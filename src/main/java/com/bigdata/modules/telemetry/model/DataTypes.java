package com.bigdata.modules.telemetry.model;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据类型词汇表，与 agentkit 保持一致。
 *
 * <p>已实现：log.runtime / log.stdout / log.audit / log.exception_summary /
 * app.metrics / jvm.runtime / jvm.gc；声明未实现：os.cpu / os.memory / os.disk /
 * log.slow_query。未实现的类型在能力判定中固定返回 NOT_IMPLEMENTED。</p>
 */
public final class DataTypes {

    public static final String LOG_RUNTIME = "log.runtime";
    public static final String LOG_STDOUT = "log.stdout";
    public static final String LOG_AUDIT = "log.audit";
    public static final String LOG_EXCEPTION_SUMMARY = "log.exception_summary";
    public static final String APP_METRICS = "app.metrics";
    public static final String JVM_RUNTIME = "jvm.runtime";
    public static final String JVM_GC = "jvm.gc";
    public static final String OS_CPU = "os.cpu";
    public static final String OS_MEMORY = "os.memory";
    public static final String OS_DISK = "os.disk";
    public static final String LOG_SLOW_QUERY = "log.slow_query";

    private static final Map<String, DataTypeDefinition> CATALOG = buildCatalog();

    private DataTypes() {
    }

    public static DataTypeDefinition get(String dataTypeId) {
        return dataTypeId == null ? null : CATALOG.get(dataTypeId.toLowerCase());
    }

    public static Collection<DataTypeDefinition> all() {
        return CATALOG.values();
    }

    public static boolean isLogType(String dataTypeId) {
        return dataTypeId != null && dataTypeId.toLowerCase().startsWith("log.");
    }

    private static Map<String, DataTypeDefinition> buildCatalog() {
        Map<String, DataTypeDefinition> catalog = new LinkedHashMap<>();
        register(catalog, LOG_RUNTIME, "服务运行日志", DataTypeStatus.IMPLEMENTED,
                "{lines[],cursor,nextCursor,truncated}",
                "file", "ds-rest", "yarn-rest", "yarn-cli", "flink-rest");
        register(catalog, LOG_STDOUT, "标准输出日志", DataTypeStatus.IMPLEMENTED,
                "{lines[],cursor,nextCursor,truncated}",
                "file", "yarn-cli", "yarn-rest");
        register(catalog, LOG_AUDIT, "审计日志", DataTypeStatus.IMPLEMENTED,
                "{lines[],cursor,nextCursor,truncated}",
                "file", "ds-rest");
        register(catalog, LOG_EXCEPTION_SUMMARY, "作业异常摘要", DataTypeStatus.IMPLEMENTED,
                "{status,failures[],numRestarts,checkpoint}",
                "flink-rest");
        register(catalog, APP_METRICS, "应用运行指标", DataTypeStatus.IMPLEMENTED,
                "{samples[{name,value,unit,scope,verified}]}",
                "flink-rest", "spark-shs");
        register(catalog, JVM_RUNTIME, "JVM 运行态", DataTypeStatus.IMPLEMENTED,
                "{heapUsed,heapMax,nonHeapUsed,threads}",
                "flink-rest", "spark-shs", "jolokia", "jmx");
        register(catalog, JVM_GC, "JVM GC", DataTypeStatus.IMPLEMENTED,
                "{collections,timeMs,byPool}",
                "flink-rest", "spark-shs", "jolokia", "jmx");
        register(catalog, OS_CPU, "主机 CPU", DataTypeStatus.DECLARED_NOT_IMPLEMENTED,
                "null", List.of());
        register(catalog, OS_MEMORY, "主机内存", DataTypeStatus.DECLARED_NOT_IMPLEMENTED,
                "null", List.of());
        register(catalog, OS_DISK, "主机磁盘", DataTypeStatus.DECLARED_NOT_IMPLEMENTED,
                "null", List.of());
        register(catalog, LOG_SLOW_QUERY, "慢查询日志", DataTypeStatus.DECLARED_NOT_IMPLEMENTED,
                "null", List.of());
        return Map.copyOf(catalog);
    }

    private static void register(Map<String, DataTypeDefinition> catalog,
                                 String id,
                                 String label,
                                 DataTypeStatus status,
                                 String payloadSchema,
                                 String... transports) {
        register(catalog, id, label, status, payloadSchema, List.of(transports));
    }

    private static void register(Map<String, DataTypeDefinition> catalog,
                                 String id,
                                 String label,
                                 DataTypeStatus status,
                                 String payloadSchema,
                                 List<String> transports) {
        catalog.put(id, new DataTypeDefinition(id, label, status, payloadSchema, transports));
    }
}
