package com.bigdata.modules.telemetry.v1.model;

import java.util.List;
import java.util.Map;

/**
 * 指标目录定义：名称、类型、单位、标签与可用后端。
 *
 * <p>指标名、类型、单位遵循 Prometheus 约定；{@code backendPreference} 按查询模式给出
 * 目录级默认顺序，配置规则可以覆盖。</p>
 *
 * <p>可选的 {@code rawMetric} / {@code endpoint} / {@code verified} / {@code note} 描述引擎原始指标来源：
 * 只有能从真实端点取到的字段才填写，未核实的填 {@code verified=false} 并在 note 里说明。</p>
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
                               String note) {

    public record LabelDefinition(String name, boolean required, String description) {
    }

    /** 兼容既有目录声明：不带引擎原始指标信息。 */
    public MetricDefinition(String name, String type, String unit, String scope,
                            List<LabelDefinition> labels, List<String> requiredRefs,
                            Map<String, List<String>> backendPreference,
                            List<BackendBinding> supportedBackends) {
        this(name, type, unit, scope, labels, requiredRefs, backendPreference, supportedBackends,
                null, null, null, null);
    }

    public List<String> preferenceFor(QueryMode mode) {
        return backendPreference.getOrDefault(mode.wire(), List.of());
    }
}
