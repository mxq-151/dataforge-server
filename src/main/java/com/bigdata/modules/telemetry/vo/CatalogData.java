package com.bigdata.modules.telemetry.vo;

import com.bigdata.modules.telemetry.common.model.BackendWarning;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataCatalogEntry;
import java.util.List;

/**
 * 目录快照。{@code metadata} 是「服务版本与配置」的目录，只增字段：
 * {@code catalogVersion} 与 {@code components} 的结构与含义保持不变。
 * {@code metricSemantics} 是身份目录 × 语义声明 × 运行时发现的聚合视图。
 */
public record CatalogData(String catalogVersion, List<ComponentCatalogEntry> components,
                          List<MetadataCatalogEntry> metadata,
                          List<MetricCatalogView> metricSemantics,
                          List<BackendWarning> warnings) {

    public CatalogData(String catalogVersion, List<ComponentCatalogEntry> components,
                       List<MetadataCatalogEntry> metadata) {
        this(catalogVersion, components, metadata, List.of(), List.of());
    }
}
