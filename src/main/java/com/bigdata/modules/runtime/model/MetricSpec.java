package com.bigdata.modules.runtime.model;

/**
 * 一条指标定义（对照表的一行）：逻辑名、原始指标名、取数层级、获取端点、单位与版本备注。
 */
public class MetricSpec {

    private final MetricFamily family;
    private final String name;
    private final String rawMetric;
    private final MetricScope scope;
    private final String endpoint;
    private final String unit;
    /** false 表示指标名随版本/发行版变化，需要在真实环境验证 */
    private final boolean verified;
    private final String note;

    public MetricSpec(MetricFamily family, String name, String rawMetric, MetricScope scope,
                      String endpoint, String unit, boolean verified, String note) {
        this.family = family;
        this.name = name;
        this.rawMetric = rawMetric;
        this.scope = scope;
        this.endpoint = endpoint;
        this.unit = unit;
        this.verified = verified;
        this.note = note;
    }

    public MetricFamily getFamily() {
        return family;
    }

    public String getName() {
        return name;
    }

    public String getRawMetric() {
        return rawMetric;
    }

    public MetricScope getScope() {
        return scope;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getUnit() {
        return unit;
    }

    public boolean isVerified() {
        return verified;
    }

    public String getNote() {
        return note;
    }

    public boolean isWildcard() {
        return rawMetric != null && rawMetric.contains("*");
    }
}
