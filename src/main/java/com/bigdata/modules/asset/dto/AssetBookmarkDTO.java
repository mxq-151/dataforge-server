package com.bigdata.modules.asset.dto;

import java.io.Serializable;

/**
 * 收藏入参。
 *
 * <p>字段名与前端 src/api/assetCatalog.ts 的契约逐字对应，改动需两端同步。</p>
 *
 * @author dataforge
 */
public class AssetBookmarkDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 资产类型：table / column / indicator */
    private String assetType;

    /** 资产 id */
    private Long assetId;

    /** 所属表元数据 id（column 类型必填，便于按表跳转） */
    private Long tableMetadataId;

    public String getAssetType() { return assetType; }
    public void setAssetType(String assetType) { this.assetType = assetType; }
    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }
    public Long getTableMetadataId() { return tableMetadataId; }
    public void setTableMetadataId(Long tableMetadataId) { this.tableMetadataId = tableMetadataId; }
}
