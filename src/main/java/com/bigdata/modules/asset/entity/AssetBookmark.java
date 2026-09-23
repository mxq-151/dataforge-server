package com.bigdata.modules.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;

/**
 * asset_bookmark
 *
 * @author dataforge
 */
@TableName("asset_bookmark")
public class AssetBookmark extends BaseEntity {

    /** 用户 ID */
    private Long userId;

    /** 资产类型 table/column/indicator */
    private String assetType;

    /** 资产 ID */
    private Long assetId;

    /** 表元数据 ID（便于按表聚合） */
    private Long tableMetadataId;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getAssetType() { return assetType; }
    public void setAssetType(String assetType) { this.assetType = assetType; }
    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }
    public Long getTableMetadataId() { return tableMetadataId; }
    public void setTableMetadataId(Long tableMetadataId) { this.tableMetadataId = tableMetadataId; }
}
