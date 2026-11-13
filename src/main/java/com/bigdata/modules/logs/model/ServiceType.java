package com.bigdata.modules.logs.model;

import java.util.Locale;

/**
 * 日志服务类型 —— 统一日志访问层的第一维。
 *
 * <p>新增一种服务时只新增枚举值 + 适配器 + 配置，不改 Controller 与查询编排。</p>
 */
public enum ServiceType {

    /** DolphinScheduler（master / worker / api） */
    DS("ds"),
    /** YARN（ResourceManager / NodeManager / 聚合日志） */
    YARN("yarn"),
    /** Spark（History Server / driver / executor） */
    SPARK("spark"),
    /** Flink（JobManager REST / JobManager、TaskManager 日志） */
    FLINK("flink"),
    /** 本机或共享挂载目录中的日志文件 */
    FILE("file");

    private final String wireName;

    ServiceType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /**
     * 解析配置或调用方传入的服务类型；支持常见别名（如 dolphinscheduler、spark-shs）。
     *
     * @throws IllegalArgumentException 取值非法（由服务层转成 400）
     */
    public static ServiceType from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "ds", "dolphin", "dolphinscheduler" -> DS;
            case "yarn", "hadoop_yarn" -> YARN;
            case "spark", "spark_shs", "shs", "spark_history_server" -> SPARK;
            case "flink", "flink_rest", "flink_jobmanager" -> FLINK;
            case "file", "local", "local_file" -> FILE;
            default -> throw new IllegalArgumentException("不支持的服务类型: " + value);
        };
    }
}
