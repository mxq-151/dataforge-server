package com.bigdata.modules.telemetry.vo;

import java.util.Map;

/**
 * {@code scope=targets} 返回的一条作业目标。
 *
 * <p>{@code ref} 只放解析后的强引用：Spark 为 {@code applicationId}，Flink 为 {@code jobId}；
 * {@code name} / {@code state} 来自引擎 API 原值，不做推断。</p>
 */
public record TargetView(String serviceType,
                         String componentId,
                         Map<String, String> ref,
                         String name,
                         String state) {

    public TargetView {
        ref = ref == null ? Map.of() : Map.copyOf(ref);
    }
}
