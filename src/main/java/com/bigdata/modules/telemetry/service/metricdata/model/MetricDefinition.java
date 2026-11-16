package com.bigdata.modules.telemetry.service.metricdata.model;

import java.util.List;
import java.util.Map;
import com.bigdata.modules.telemetry.common.model.BackendBinding;
import com.bigdata.modules.telemetry.common.model.QueryMode;

/**
 * 指标语义声明：名称、类型、单位、模式与可用后端。
 *
 * <p>目录不再存物理全名；{@code pattern} 负责把运行时发现到的指标名映射到本语义。
 * {@code rawMetric} 只保留给必须精确绑定的 REST 字段（如 Flink / Spark），
 * 可发现指标应使用族/前缀/后缀/正则模式。</p>
 *
 * <p>{@code discovery=MANUAL} 表示该语义所在引擎没有指标名列举能力，名字表为手工维护；
 * 查询时跳过发现校验，但必须在文档与目录里显式标注。</p>
 */
public record MetricDefinition(String name,
                               String type,
                               String unit,
                               String scope,
                               List<LabelDefinition> labels,
                               List<String> requiredRefs,
                               Map<String, List<String>> backendPreference,
                               List<BackendBinding> supportedBackends,
                               String rawMetric,
                               String endpoint,
                               Boolean verified,
                               String note,
                               MetricPattern pattern,
                               MetricDiscoveryMode discovery) {

    public record LabelDefinition(String name, boolean required, String description) {
    }

    public MetricDefinition {
        labels = labels == null ? List.of() : List.copyOf(labels);
        requiredRefs = requiredRefs == null ? List.of() : List.copyOf(requiredRefs);
        backendPreference = backendPreference == null ? Map.of() : Map.copyOf(backendPreference);
        supportedBackends = supportedBackends == null ? List.of() : List.copyOf(supportedBackends);
        if (pattern == null) {
            pattern = MetricPattern.exact(rawMetric != null && !rawMetric.isBlank() ? rawMetric : name);
        }
        if (discovery == null) {
            discovery = MetricDiscoveryMode.MANUAL;
        }
    }

    /** 兼容既有目录声明：不带引擎原始指标信息，按手工维护处理。 */
    public MetricDefinition(String name, String type, String unit, String scope,
                            List<LabelDefinition> labels, List<String> requiredRefs,
                            Map<String, List<String>> backendPreference,
                            List<BackendBinding> supportedBackends) {
        this(name, type, unit, scope, labels, requiredRefs, backendPreference, supportedBackends,
                null, null, null, null, MetricPattern.exact(name), MetricDiscoveryMode.MANUAL);
    }

    public List<String> preferenceFor(QueryMode mode) {
        return backendPreference.getOrDefault(mode.wire(), List.of());
    }

    /** rate 仅适用于 counter；类型未知（null）时一律拒绝。 */
    public boolean rateApplicable() {
        return "counter".equalsIgnoreCase(type);
    }

    public boolean patternMatches(String metricName) {
        return pattern != null && pattern.matches(metricName);
    }
}
