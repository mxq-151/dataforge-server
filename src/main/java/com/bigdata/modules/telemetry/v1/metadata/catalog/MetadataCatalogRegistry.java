package com.bigdata.modules.telemetry.v1.metadata.catalog;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 合并全部 {@link MetadataCatalogContributor}，供 {@code mode=catalog} 的 {@code metadata[]} 使用。
 *
 * <p>同一 {@code serviceType + componentId} 被多个 provider 声明是正常情况（例如 Doris 的
 * FE 与手动兜底），按 providerId 去重合并，不视为冲突；只有完全重复的 providerId 才忽略。</p>
 */
@Component
public class MetadataCatalogRegistry {

    private final List<MetadataCatalogEntry> entries;

    @Autowired
    public MetadataCatalogRegistry(ObjectProvider<MetadataCatalogContributor> contributors) {
        this(contributors.orderedStream().toList());
    }

    public MetadataCatalogRegistry(List<MetadataCatalogContributor> contributors) {
        Map<String, MetadataCatalogEntry> merged = new LinkedHashMap<>();
        for (MetadataCatalogContributor contributor : contributors) {
            for (MetadataCatalogEntry entry : contributor.contribute()) {
                String key = entry.serviceType() + "|" + entry.componentId();
                merged.merge(key, entry, MetadataCatalogRegistry::merge);
            }
        }
        this.entries = List.copyOf(merged.values());
    }

    public List<MetadataCatalogEntry> entries() {
        return entries;
    }

    public List<MetadataCatalogEntry> entriesOf(String serviceType, String componentId) {
        List<MetadataCatalogEntry> matched = new ArrayList<>();
        for (MetadataCatalogEntry entry : entries) {
            if (!entry.serviceType().equals(serviceType)) {
                continue;
            }
            if (componentId != null && !componentId.isBlank()
                    && !entry.componentId().equals(componentId)) {
                continue;
            }
            matched.add(entry);
        }
        return matched;
    }

    private static MetadataCatalogEntry merge(MetadataCatalogEntry left, MetadataCatalogEntry right) {
        Set<String> sections = new LinkedHashSet<>(left.sections());
        sections.addAll(right.sections());
        Set<String> providerIds = new LinkedHashSet<>(left.providerIds());
        providerIds.addAll(right.providerIds());
        return new MetadataCatalogEntry(left.serviceType(), left.componentId(),
                left.versionSupported() || right.versionSupported(),
                List.copyOf(sections), List.copyOf(providerIds));
    }
}
