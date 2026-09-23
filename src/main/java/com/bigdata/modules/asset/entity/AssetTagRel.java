package com.bigdata.modules.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;

/**
 * asset_tag_rel
 *
 * @author dataforge
 */
@TableName("asset_tag_rel")
public class AssetTagRel extends BaseEntity {

    /** 标签 ID */
    private Long tagId;

    /** 资产类型 table/column/indicator */
    private String assetType;

    /** 资产 ID */
    private Long assetId;

    /** 表元数据 ID（便于按表聚合） */
    private Long tableMetadataId;

    public Long getTagId() { return tagId; }
    public void setTagId(Long tagId) { this.tagId = tagId; }
    public String getAssetType() { return assetType; }
    public void setAssetType(String assetType) { this.assetType = assetType; }
    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }
    public Long getTableMetadataId() { return tableMetadataId; }
    public void setTableMetadataId(Long tableMetadataId) { this.tableMetadataId = tableMetadataId; }
}
