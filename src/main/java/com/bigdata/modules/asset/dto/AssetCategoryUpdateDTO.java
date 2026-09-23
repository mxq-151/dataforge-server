package com.bigdata.modules.asset.dto;

import java.io.Serializable;

/**
 * 分类与治理属性变更入参。
 *
 * <p>字段名与前端 src/api/assetCatalog.ts 的契约逐字对应，改动需两端同步。</p>
 *
 * @author dataforge
 */
public class AssetCategoryUpdateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 人工覆盖的分类编码；传空串表示恢复为表名解析结果 */
    private String categoryCode;

    /** 主题域 */
    private String topic;

    /** 可见性：public / internal / restricted */
    private String visibility;

    /** 资产等级 */
    private String assetLevel;

    public String getCategoryCode() { return categoryCode; }
    public void setCategoryCode(String categoryCode) { this.categoryCode = categoryCode; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public String getAssetLevel() { return assetLevel; }
    public void setAssetLevel(String assetLevel) { this.assetLevel = assetLevel; }
}
