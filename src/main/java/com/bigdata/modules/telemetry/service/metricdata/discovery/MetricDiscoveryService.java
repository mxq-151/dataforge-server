package com.bigdata.modules.telemetry.service.metricdata.discovery;

import com.bigdata.modules.telemetry.service.catalog.TelemetryCatalogRegistry;
import com.bigdata.modules.telemetry.vo.ComponentCatalogEntry;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDefinition;
import com.bigdata.modules.telemetry.common.model.Target;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.bigdata.modules.telemetry.service.metricdata.model.DiscoveredMetric;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDiscoveryQuery;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDiscoveryResult;

/**
 * 指标发现编排：优先真实发现；没有任何 provider 支持时回退到目录中的静态指标声明
 * （仅自定义/兼容场景，Spark/Kafka/YARN/Doris/ClickHouse 不再依赖手工表）。
 *
 * <p>回退只发生在 {@code supported=false}；如果引擎明确支持列举但结果为空，
 * 不再用静态声明冒充“实例暴露了该指标”。</p>
 */
@Service
public class MetricDiscoveryService {

    public static final String MANUAL_PROVIDER_ID = "catalog:manual";

    private final MetricDiscoveryRegistry registry;
    private final TelemetryCatalogRegistry catalogRegistry;

    public MetricDiscoveryService(MetricDiscoveryRegistry registry,
                                  TelemetryCatalogRegistry catalogRegistry) {
        this.registry = registry;
        this.catalogRegistry = catalogRegistry;
    }

    public MetricDiscoveryResult discover(Target target, Map<String, String> refs,
                                          Duration timeout, int limit) {
        MetricDiscoveryResult result = registry.discover(
                new MetricDiscoveryQuery(target, refs, timeout, limit));
        if (result.supported()) {
            return result;
        }
        Optional<ComponentCatalogEntry> entry = catalogRegistry.catalog()
                .entry(target.serviceType(), target.componentId());
        if (entry.isEmpty() || entry.get().metrics().isEmpty()) {
            return result;
        }
        List<DiscoveredMetric> manual = entry.get().metrics().stream()
                .map(this::manual)
                .toList();
        return MetricDiscoveryResult.manual(MANUAL_PROVIDER_ID, manual,
                "该引擎无指标名发现能力，名字表为手工维护（" + target.serviceType() + "/"
                        + target.componentId() + "）");
    }

    private DiscoveredMetric manual(MetricDefinition definition) {
        return new DiscoveredMetric(definition.name(), MANUAL_PROVIDER_ID, "catalog:manual",
                definition.type(), definition.unit());
    }
}
