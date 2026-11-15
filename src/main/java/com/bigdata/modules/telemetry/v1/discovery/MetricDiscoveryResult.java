package com.bigdata.modules.telemetry.v1.discovery;

import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.Requirement;
import com.bigdata.modules.telemetry.v1.model.BackendWarning;

import java.util.List;

/**
 * 指标名发现结果。
 *
 * <p>{@code supported=false} 表示没有任何 provider 能列举该 target 的指标；
 * {@code manual=true} 表示回退到目录中的静态指标声明（仅自定义/兼容场景）。
 * 查询时只有 {@code supported && !manual} 才把「发现结果里没有」判定为实例未暴露。</p>
 */
public record MetricDiscoveryResult(List<DiscoveredMetric> metrics,
                                    boolean supported,
                                    boolean manual,
                                    String providerId,
                                    CapabilityCode code,
                                    String reason,
                                    List<Requirement> requirements,
                                    List<BackendWarning> warnings) {

    public MetricDiscoveryResult {
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static MetricDiscoveryResult supported(String providerId, List<DiscoveredMetric> metrics,
                                                  List<BackendWarning> warnings) {
        return new MetricDiscoveryResult(metrics, true, false, providerId, CapabilityCode.OK,
                null, List.of(), warnings);
    }

    public static MetricDiscoveryResult manual(String providerId, List<DiscoveredMetric> metrics,
                                               String reason) {
        return new MetricDiscoveryResult(metrics, true, true, providerId, CapabilityCode.OK,
                reason, List.of(), List.of(BackendWarning.of("MANUAL_METRIC_NAMES", reason)));
    }

    public static MetricDiscoveryResult unsupported(String providerId, String reason,
                                                    List<Requirement> requirements) {
        return new MetricDiscoveryResult(List.of(), false, false, providerId,
                CapabilityCode.METRIC_DISCOVERY_UNSUPPORTED, reason, requirements, List.of());
    }

    public static MetricDiscoveryResult failed(String providerId, CapabilityCode code, String reason,
                                               List<Requirement> requirements,
                                               List<BackendWarning> warnings) {
        return new MetricDiscoveryResult(List.of(), false, false, providerId, code, reason,
                requirements, warnings);
    }

    public boolean hasMetrics() {
        return !metrics.isEmpty();
    }

    /** 只有非手工的 discoverable 结果才能把“发现结果里没有”当成实例未暴露。 */
    public boolean authoritative() {
        return supported && !manual;
    }
}
