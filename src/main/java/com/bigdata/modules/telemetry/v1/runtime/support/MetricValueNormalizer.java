package com.bigdata.modules.telemetry.v1.runtime.support;

/**
 * 引擎原始值 → 目录声明单位的换算。
 *
 * <p>只处理目录里真实存在的差异：Flink/Spark 的 duration/GC 时间是毫秒而目录单位是秒；
 * Flink 的 {@code *TimeMsPerSecond} 是 0-1000 的每毫秒占比而目录单位是 ratio。
 * 其余原样返回，不做任何猜测。</p>
 */
public final class MetricValueNormalizer {

    private MetricValueNormalizer() {
    }

    public static double normalize(String rawMetric, String unit, double value) {
        if (rawMetric == null || unit == null) {
            return value;
        }
        if ("seconds".equalsIgnoreCase(unit)) {
            if ("lastCheckpointDuration".equals(rawMetric)
                    || "totalGCTime".equals(rawMetric)
                    || "totalDuration".equals(rawMetric)
                    || (rawMetric.contains("GarbageCollector") && rawMetric.endsWith(".Time"))) {
                return value / 1000.0;
            }
        }
        if ("ratio".equalsIgnoreCase(unit) && rawMetric.endsWith("TimeMsPerSecond")) {
            return value / 1000.0;
        }
        return value;
    }
}
