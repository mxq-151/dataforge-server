package com.bigdata.modules.telemetry.v1.catalog;

import com.bigdata.modules.telemetry.v1.model.MetricDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 目前仅 Flink 使用的 REST 最小语义注册表。
 *
 * <p>Flink 通过 REST 只给原始指标 ID，没有类型/单位信息，必须保留
 * 「语义名 → 原始 ID / 正则族 + 类型 + 单位 + rate 适用性」的绑定。
 * Spark 已改为 SHS jmx_exporter + VictoriaMetrics 运行时发现；
 * Kafka / YARN / Doris / ClickHouse 等走 Prometheus 文本或 VM 抓取的引擎
 * 不在此声明，指标名与类型一律从运行时发现获取。</p>
 */
@Component
public class RestMetricSemanticsRegistry {

    public record ComponentMetricSemantics(String serviceType, String componentId,
                                           List<MetricDefinition> metrics) {
        public ComponentMetricSemantics {
            metrics = metrics == null ? List.of() : List.copyOf(metrics);
        }
    }

    private final Map<String, List<MetricDefinition>> byComponent = new HashMap<>();

    public RestMetricSemanticsRegistry(FlinkMetricSemantics flink) {
        merge(flink.contribute());
    }

    public List<MetricDefinition> forComponent(String serviceType, String componentId) {
        return byComponent.getOrDefault(serviceType + "/" + componentId, List.of());
    }

    public List<MetricDefinition> all() {
        return byComponent.values().stream().flatMap(List::stream).toList();
    }

    private void merge(List<ComponentMetricSemantics> components) {
        for (ComponentMetricSemantics component : components) {
            List<MetricDefinition> metrics = byComponent.computeIfAbsent(
                    component.serviceType() + "/" + component.componentId(),
                    ignored -> new ArrayList<>());
            for (MetricDefinition metric : component.metrics()) {
                if (metrics.stream().anyMatch(existing -> existing.name().equals(metric.name()))) {
                    throw new IllegalStateException("重复的 REST 指标语义: "
                            + component.serviceType() + "/" + component.componentId()
                            + "/" + metric.name());
                }
                if (metric.pattern() != null) {
                    String pattern = metric.pattern().wire();
                    metrics.stream()
                            .filter(existing -> existing.pattern() != null
                                    && existing.pattern().wire().equals(pattern))
                            .findFirst()
                            .ifPresent(existing -> {
                                throw new IllegalStateException("指标模式冲突: "
                                        + component.serviceType() + "/" + component.componentId()
                                        + " pattern=" + pattern + " 同时绑定 "
                                        + existing.name() + " 与 " + metric.name()
                                        + "；改用语义名模式或按 order 显式区分");
                            });
                }
                metrics.add(metric);
            }
        }
    }
}
