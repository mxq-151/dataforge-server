package com.bigdata.modules.telemetry.service.metricdata.model;

/**
 * 运行时发现到的一个指标名。
 *
 * <p>{@code type}/{@code unit} 只有来源直接给出时才填；否则为 null，由语义模式补全。</p>
 */
public record DiscoveredMetric(String name, String providerId, String source,
                               String type, String unit) {
}
