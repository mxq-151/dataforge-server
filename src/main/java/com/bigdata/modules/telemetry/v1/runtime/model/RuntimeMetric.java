package com.bigdata.modules.telemetry.v1.runtime.model;

/**
 * 一条运行时指标。
 *
 * <p>{@code value} 只在 availability 为 {@code AVAILABLE} / {@code ZERO} 时有意义；
 * 取不到时固定为 null 并带 reason，禁止填 0 冒充真实值。</p>
 */
public record RuntimeMetric(String name,
                            String family,
                            String sourceMetric,
                            Double value,
                            String unit,
                            String availability,
                            String reason) {

    public static final String AVAILABLE = "AVAILABLE";
    public static final String ZERO = "ZERO";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String NOT_SUPPORTED = "NOT_SUPPORTED";

    public static RuntimeMetric available(String name, String family, String sourceMetric,
                                          double value, String unit) {
        return new RuntimeMetric(name, family, sourceMetric, value, unit,
                value == 0 ? ZERO : AVAILABLE, null);
    }

    public static RuntimeMetric unavailable(String name, String family, String sourceMetric,
                                            String unit, String reason) {
        return new RuntimeMetric(name, family, sourceMetric, null, unit, UNAVAILABLE, reason);
    }

    public boolean hasValue() {
        return value != null;
    }
}
