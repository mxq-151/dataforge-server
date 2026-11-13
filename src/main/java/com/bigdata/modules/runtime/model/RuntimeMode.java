package com.bigdata.modules.runtime.model;

import java.util.Locale;

/**
 * 运行时数据源形态。
 *
 * <p>必须显式区分「作业结束后可查」与「运行中可查」：History Server 只保留已结束应用，
 * live UI / JobManager REST 只反映当前进程。两者是不同数据源，不合并成一个。</p>
 */
public enum RuntimeMode {

    /** 运行中：Flink JobManager REST / Spark live UI（或经 YARN tracking URL 代理） */
    LIVE("live"),
    /** 已结束：Spark History Server */
    HISTORY("history");

    private final String wireName;

    RuntimeMode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static RuntimeMode from(String value) {
        if (value == null || value.isBlank()) {
            return LIVE;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "live", "running", "runtime", "ui" -> LIVE;
            case "history", "historical", "shs", "history_server" -> HISTORY;
            default -> throw new IllegalArgumentException("不支持的运行时模式: " + value);
        };
    }
}
