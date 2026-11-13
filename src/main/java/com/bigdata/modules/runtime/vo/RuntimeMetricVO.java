package com.bigdata.modules.runtime.vo;

/**
 * 一条指标读数。
 *
 * <p>{@code availability} 区分四种情况，避免把「取不到」误读成 0：
 * AVAILABLE（有值）、ZERO（有值且为 0）、UNAVAILABLE（端点可用但指标缺失/非数值）、
 * NOT_SUPPORTED（该服务/版本不提供该指标）。</p>
 */
public class RuntimeMetricVO {

    public static final String AVAILABLE = "AVAILABLE";
    public static final String ZERO = "ZERO";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String NOT_SUPPORTED = "NOT_SUPPORTED";

    private String name;
    /** 指标族（jvm_gc / checkpoint / throughput / backpressure / shuffle / failures） */
    private String family;
    /** 组件标识：TM id / executor id / vertex / stage / job */
    private String component;
    /** taskmanager / executor / vertex / stage / job */
    private String componentType;
    /** 上游原始指标名，便于对照版本 */
    private String sourceMetric;
    private Double value;
    /** bytes / ms / count / records / records_per_second / bytes_per_second / ratio */
    private String unit;
    private String availability;
    private String reason;
    private Long capturedAt;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFamily() {
        return family;
    }

    public void setFamily(String family) {
        this.family = family;
    }

    public String getComponent() {
        return component;
    }

    public void setComponent(String component) {
        this.component = component;
    }

    public String getComponentType() {
        return componentType;
    }

    public void setComponentType(String componentType) {
        this.componentType = componentType;
    }

    public String getSourceMetric() {
        return sourceMetric;
    }

    public void setSourceMetric(String sourceMetric) {
        this.sourceMetric = sourceMetric;
    }

    public Double getValue() {
        return value;
    }

    public void setValue(Double value) {
        this.value = value;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public String getAvailability() {
        return availability;
    }

    public void setAvailability(String availability) {
        this.availability = availability;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Long getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(Long capturedAt) {
        this.capturedAt = capturedAt;
    }
}
