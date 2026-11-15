package com.bigdata.modules.telemetry.v1.runtime.support;

import com.bigdata.modules.telemetry.v1.catalog.TelemetryCatalog;
import com.bigdata.modules.telemetry.v1.model.MetricDefinition;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 指标族：由目录指标名归类，不新增目录字段。
 *
 * <p>目录里的指标名由本模块维护，前缀/关键词稳定；显式 {@code metrics[]} 归 {@code explicit}。
 * 该归类只用于 snapshot/compare 的 family 字段与 families 过滤，不参与路由。</p>
 */
public final class RuntimeFamilies {

    public static final String CHECKPOINT = "checkpoint";
    public static final String THROUGHPUT = "throughput";
    public static final String JVM_GC = "jvm_gc";
    public static final String BACKPRESSURE = "backpressure";
    public static final String SHUFFLE = "shuffle";
    public static final String FAILURES = "failures";
    public static final String EXPLICIT = "explicit";

    private static final List<String> ALL = List.of(CHECKPOINT, THROUGHPUT, JVM_GC,
            BACKPRESSURE, SHUFFLE, FAILURES);

    /** 服务当前无法提供的指标族及原因（与目录一致，不伪造）。 */
    private static final Map<String, Map<String, String>> UNAVAILABLE = Map.of(
            "spark", Map.of(
                    CHECKPOINT, "Spark History Server / live UI REST 不提供 checkpoint 指标；"
                            + "流式 checkpoint 需 StreamingQueryProgress，不在 REST 端点内",
                    BACKPRESSURE, "Spark REST 不提供背压指标；可用 stage 的 shuffle 等待/写盘时间近似"));

    private RuntimeFamilies() {
    }

    public static String familyOf(String metricName) {
        if (metricName == null) {
            return EXPLICIT;
        }
        String lower = metricName.toLowerCase();
        if (lower.startsWith("jvm_") || lower.startsWith("jvm.")) {
            return JVM_GC;
        }
        if (lower.contains("checkpoint")) {
            return CHECKPOINT;
        }
        if (lower.contains("backpressure")) {
            return BACKPRESSURE;
        }
        if (lower.contains("shuffle")) {
            return SHUFFLE;
        }
        if (lower.contains("restart") || lower.contains("failed") || lower.contains("killed")
                || lower.contains("attempt")) {
            return FAILURES;
        }
        return THROUGHPUT;
    }

    /** 未指定 families 时的默认集合：只取该服务目录里真实存在的族。 */
    public static List<String> defaultsFor(String serviceType, TelemetryCatalog catalog) {
        List<String> supported = supportedFor(serviceType, catalog);
        List<String> result = new ArrayList<>();
        for (String family : List.of(CHECKPOINT, THROUGHPUT, FAILURES, JVM_GC)) {
            if (supported.contains(family)) {
                result.add(family);
            }
        }
        return result.isEmpty() ? List.of(THROUGHPUT) : result;
    }

    public static List<String> supportedFor(String serviceType, TelemetryCatalog catalog) {
        Set<String> families = new LinkedHashSet<>();
        for (MetricDefinition metric : catalogMetrics(serviceType, catalog)) {
            families.add(familyOf(metric.name()));
        }
        families.remove(EXPLICIT);
        return new ArrayList<>(families);
    }

    public static Map<String, String> unavailableFor(String serviceType) {
        return UNAVAILABLE.getOrDefault(serviceType, Map.of());
    }

    public static List<String> all() {
        return ALL;
    }

    public static boolean isKnown(String family) {
        return ALL.contains(family) || EXPLICIT.equals(family);
    }

    /** 目录里该服务所有组件声明的指标。 */
    public static List<MetricDefinition> catalogMetrics(String serviceType, TelemetryCatalog catalog) {
        List<MetricDefinition> result = new ArrayList<>();
        for (var entry : catalog.entries()) {
            if (entry.serviceType().equals(serviceType)) {
                result.addAll(entry.metrics());
            }
        }
        return result;
    }
}
