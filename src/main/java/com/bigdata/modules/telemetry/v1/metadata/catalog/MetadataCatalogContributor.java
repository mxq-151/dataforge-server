package com.bigdata.modules.telemetry.v1.metadata.catalog;

import java.util.List;

/**
 * metadata 目录扩展点：provider 用它声明「我能取哪些服务组件的版本与配置」。
 *
 * <p>与 {@code CatalogContributor} 并列但独立：不实现它不影响 metadata 查询，
 * 只是该 provider 不会出现在 {@code mode=catalog} 的 {@code metadata[]} 里。</p>
 */
public interface MetadataCatalogContributor {

    List<MetadataCatalogEntry> contribute();
}
