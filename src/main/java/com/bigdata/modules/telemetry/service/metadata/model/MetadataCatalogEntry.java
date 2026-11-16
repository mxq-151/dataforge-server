package com.bigdata.modules.telemetry.service.metadata.model;

import java.util.List;

/**
 * 「服务版本与配置」的目录条目。
 *
 * <p>与指标目录（{@code ComponentCatalogEntry}）平级、互不依赖：这里只说明
 * 「哪些服务组件的版本 / 配置可以由哪些 provider 取到」，不声明指标与日志。</p>
 *
 * @param serviceType      五层模型第一层
 * @param componentId      五层模型第二层
 * @param versionSupported 是否有 provider 能取版本
 * @param sections         可取的配置 section（空表示 provider 未声明固定 section）
 * @param providerIds      声明支持的 providerId 列表
 */
public record MetadataCatalogEntry(String serviceType,
                                   String componentId,
                                   boolean versionSupported,
                                   List<String> sections,
                                   List<String> providerIds) {

    public MetadataCatalogEntry {
        sections = sections == null ? List.of() : List.copyOf(sections);
        providerIds = providerIds == null ? List.of() : List.copyOf(providerIds);
    }
}
