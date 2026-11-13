package com.bigdata.modules.runtime.model;

import java.util.Locale;

/**
 * 指标族：默认按族取数，避免一次拉全量指标把集群打满。
 */
public enum MetricFamily {

    JVM_GC("jvm_gc"),
    CHECKPOINT("checkpoint"),
    THROUGHPUT("throughput"),
    BACKPRESSURE("backpressure"),
    SHUFFLE("shuffle"),
    FAILURES("failures");

    private final String wireName;

    MetricFamily(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static MetricFamily from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("指标族不能为空");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "jvm_gc", "jvm", "gc", "jvm/gc" -> JVM_GC;
            case "checkpoint", "checkpoints" -> CHECKPOINT;
            case "throughput", "records", "io" -> THROUGHPUT;
            case "backpressure", "back_pressure", "latency" -> BACKPRESSURE;
            case "shuffle" -> SHUFFLE;
            case "failures", "failure", "retries", "retry" -> FAILURES;
            default -> throw new IllegalArgumentException("不支持的指标族: " + value);
        };
    }
}
