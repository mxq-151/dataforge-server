package com.bigdata.modules.telemetry.v1.catalog;

import com.bigdata.modules.telemetry.v1.model.ComponentCatalogEntry;

import java.util.List;

/**
 * 目录扩展点：用户可注册自己的服务/组件/数据类型清单。
 *
 * <p>启动时所有实现会被合并；同一 {@code serviceType + componentId} 重复注册直接启动失败，
 * 避免后注册的条目静默覆盖前一个。</p>
 */
public interface CatalogContributor {

    List<ComponentCatalogEntry> contribute();
}
