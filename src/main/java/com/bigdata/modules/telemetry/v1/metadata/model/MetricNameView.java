package com.bigdata.modules.telemetry.v1.metadata.model;

/**
 * 运行时发现到的一个指标名。
 *
 * <p>{@code manual=true} 表示该名字来自目录里的静态指标声明（仅自定义/兼容场景；
 * Flink 用最小语义表，Spark/Kafka/YARN/Doris/ClickHouse 均走运行时发现）。
 * {@code type}/{@code unit} 可能为 null，表示发现来源未给出，需由语义模式补全。</p>
 */
public record MetricNameView(MetadataTarget target,
                             String providerId,
                             String name,
                             String source,
                             String type,
                             String unit,
                             boolean manual) {
}
