package com.bigdata.modules.telemetry.service.metricdata.model;

/**
 * 指标的发现方式。
 *
 * <p>{@link #DISCOVERED} 表示该指标应能在实例的列举结果中找到；查询时会先校验发现结果。
 * {@link #MANUAL} 表示引擎没有列举能力，名字表为手工维护（如 Spark SHS、Flink 背压字段），
 * 查询时跳过发现校验，但仍需显式标注。</p>
 */
public enum MetricDiscoveryMode {
    DISCOVERED,
    MANUAL
}
