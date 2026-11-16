package com.bigdata.modules.telemetry.service.catalog;

import com.bigdata.modules.telemetry.common.model.BackendBinding;
import com.bigdata.modules.telemetry.service.logdata.model.LogStreamDefinition;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.bigdata.modules.telemetry.service.metricdata.catalog.RestMetricSemanticsRegistry;
import com.bigdata.modules.telemetry.vo.ComponentCatalogEntry;
import com.bigdata.modules.telemetry.vo.ComponentCatalogEntry.EngineEvidenceDefinition;
import com.bigdata.modules.telemetry.service.catalog.spi.CatalogContributor;

/**
 * 启动时合并全部 {@link CatalogContributor}，并冻结为 {@link TelemetryCatalog}。
 *
 * <p>重复组件、组件内重复指标名、重复日志流都会在启动期失败，避免目录里出现歧义条目。</p>
 */
@Component
public class TelemetryCatalogRegistry {

    /** 目录版本：与设计文档一致；目录结构变化时递增。 */
    public static final String CATALOG_VERSION = "2026-11-15.2";

    private final TelemetryCatalog catalog;

    public TelemetryCatalogRegistry(List<CatalogContributor> contributors,
                                    RestMetricSemanticsRegistry semanticsRegistry) {
        List<ComponentCatalogEntry> merged = new ArrayList<>();
        Set<String> componentKeys = new HashSet<>();
        for (CatalogContributor contributor : contributors) {
            for (ComponentCatalogEntry identity : contributor.contribute()) {
                String key = TelemetryCatalog.key(identity.serviceType(), identity.componentId());
                if (!componentKeys.add(key)) {
                    throw new IllegalStateException("重复的目录组件: " + key);
                }
                List<MetricDefinition> metrics =
                        semanticsRegistry.forComponent(identity.serviceType(), identity.componentId());
                if (metrics.isEmpty()) {
                    metrics = identity.metrics();
                }
                ComponentCatalogEntry entry = new ComponentCatalogEntry(
                        identity.serviceType(), identity.componentId(), metrics,
                        identity.logStreams(), identity.engineEvidence(),
                        identity.metricCatalogStatus(), identity.metricRequirements());
                validateEntry(entry);
                merged.add(entry);
            }
        }
        this.catalog = new TelemetryCatalog(CATALOG_VERSION, merged);
    }

    public TelemetryCatalog catalog() {
        return catalog;
    }

    private void validateEntry(ComponentCatalogEntry entry) {
        String prefix = entry.serviceType() + "." + entry.componentId();
        Set<String> metricNames = new HashSet<>();
        for (MetricDefinition metric : entry.metrics()) {
            if (!metricNames.add(metric.name())) {
                throw new IllegalStateException(prefix + " 重复的指标定义: " + metric.name());
            }
            validateBackends(prefix + " 指标 " + metric.name(), metric.supportedBackends());
        }
        Set<String> streams = new HashSet<>();
        for (LogStreamDefinition stream : entry.logStreams()) {
            String key = stream.dataType().wire() + "/" + stream.stream();
            if (!streams.add(key)) {
                throw new IllegalStateException(prefix + " 重复的日志流定义: " + key);
            }
            validateBackends(prefix + " 日志流 " + key, stream.supportedBackends());
        }
        for (ComponentCatalogEntry.EngineEvidenceDefinition evidence : entry.engineEvidence()) {
            validateBackends(prefix + " 证据 " + evidence.name(), evidence.supportedBackends());
        }
    }

    private void validateBackends(String owner, List<BackendBinding> bindings) {
        Set<String> ids = new HashSet<>();
        for (BackendBinding binding : bindings) {
            if (!ids.add(binding.backendId())) {
                throw new IllegalStateException(owner + " 重复的后端绑定: " + binding.backendId());
            }
        }
    }
}
