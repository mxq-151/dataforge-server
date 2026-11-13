package com.bigdata.modules.logs.model;

import java.util.Locale;

/**
 * 日志种类 —— 统一日志访问层的第三维。
 *
 * <p>区分运行日志、审计日志、慢查询日志、聚合日志与系统表日志，
 * 因为同一服务不同种类的获取协议与限制完全不同（例如 Doris 审计日志需开启插件、
 * YARN 聚合日志需要额外合并开销）。</p>
 */
public enum LogKind {

    RUNTIME("runtime"),
    /** 作业异常摘要（Flink REST 等），是结构化证据，不是原始日志 */
    EXCEPTION("exception"),
    /** 进程 stdout / stderr */
    STDOUT("stdout"),
    AUDIT("audit"),
    SLOW_QUERY("slow_query"),
    AGGREGATE("aggregate"),
    SYSTEM_TABLE("system_table");

    private final String wireName;

    LogKind(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static LogKind from(String value) {
        if (value == null || value.isBlank()) {
            return RUNTIME;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "runtime", "run", "app", "application" -> RUNTIME;
            case "exception", "exceptions", "error" -> EXCEPTION;
            case "stdout", "out", "console" -> STDOUT;
            case "audit", "audit_log" -> AUDIT;
            case "slow", "slow_query", "slowquery", "slow_log" -> SLOW_QUERY;
            case "aggregate", "aggregated", "aggregation" -> AGGREGATE;
            case "system_table", "systemtable", "system" -> SYSTEM_TABLE;
            default -> throw new IllegalArgumentException("不支持的日志种类: " + value);
        };
    }
}
