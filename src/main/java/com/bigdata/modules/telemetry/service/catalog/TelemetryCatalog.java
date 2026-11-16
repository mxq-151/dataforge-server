package com.bigdata.modules.telemetry.service.catalog;

import com.bigdata.modules.telemetry.service.metricdata.model.MetricDefinition;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import com.bigdata.modules.telemetry.vo.ComponentCatalogEntry;

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

    public static String key(String serviceType, String componentId) {
        return serviceType + "|" + componentId;
    }
}
