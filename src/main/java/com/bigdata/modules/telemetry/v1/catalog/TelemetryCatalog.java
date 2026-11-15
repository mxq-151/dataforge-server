package com.bigdata.modules.telemetry.v1.catalog;

import com.bigdata.modules.telemetry.v1.model.ComponentCatalogEntry;
import com.bigdata.modules.telemetry.v1.model.LogStreamDefinition;
import com.bigdata.modules.telemetry.v1.model.MetricDefinition;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 合并后的只读目录快照。目录显式标注「数据类型 × 支持的后端」，调用方据此判断能查什么。
 */
public final class TelemetryCatalog {

    private final String version;
    private final Map<String, ComponentCatalogEntry> entries;

    public TelemetryCatalog(String version, Collection<ComponentCatalogEntry> entries) {
        this.version = version;
        Map<String, ComponentCatalogEntry> map = new LinkedHashMap<>();
        for (ComponentCatalogEntry entry : entries) {
            map.put(key(entry.serviceType(), entry.componentId()), entry);
        }
        this.entries = Map.copyOf(map);
    }

    public String version() {
        return version;
    }

    public Collection<ComponentCatalogEntry> entries() {
        return entries.values();
    }

    public Optional<ComponentCatalogEntry> entry(String serviceType, String componentId) {
        return Optional.ofNullable(entries.get(key(serviceType, componentId)));
    }

    public Optional<MetricDefinition> metric(String serviceType, String componentId, String name) {
        return entry(serviceType, componentId).flatMap(entry -> entry.metrics().stream()
                .filter(metric -> metric.name().equals(name))
                .findFirst());
    }

    public Optional<LogStreamDefinition> logStream(String serviceType, String componentId, String stream) {
        return entry(serviceType, componentId).flatMap(entry -> entry.logStreams().stream()
                .filter(item -> item.stream().equals(stream))
                .findFirst());
    }

    public List<MetricDefinition> metricsOf(String serviceType, String componentId) {
        return entry(serviceType, componentId).map(ComponentCatalogEntry::metrics).orElse(List.of());
    }

    public static String key(String serviceType, String componentId) {
        return serviceType + "|" + componentId;
    }
}
