package com.bigdata.modules.telemetry.vo;
/**
 * 单个指标语义的聚合视图。
 *
 * <p>{@code available=false} 表示发现结果里没有该指标；{@code typeKnown=false}
 * 表示发现到了名字但没有语义模式命中，可查但 rate=true 必须拒绝。</p>
 */
public record MetricCatalogView(String serviceType, String componentId, String name,
                                String type, String unit, boolean rateApplicable,
                                String pattern, String discoveryMode, boolean available,
                                boolean typeKnown, String discoverySource, boolean manual,
                                String note) {
}
