package com.bigdata.modules.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;
import java.time.LocalDate;

/**
 * asset_usage_daily
 *
 * @author dataforge
 */
@TableName("asset_usage_daily")
public class AssetUsageDaily extends BaseEntity {

    /** 资产类型 table/column/indicator */
    private String assetType;

    /** 资产 ID */
    private Long assetId;

    /** 表元数据 ID（便于按表聚合） */
    private Long tableMetadataId;

    /** 统计日（按日汇总，T+1 生成） */
    private LocalDate statDate;

    /** 浏览数 */
    private Integer browseCount;

    /** 读取数 */
    private Integer readCount;

    /** 收藏数 */
    private Integer bookmarkCount;

    /** 被关联数（引用该资产的规则/指标等） */
    private Integer relateCount;

    public String getAssetType() { return assetType; }
    public void setAssetType(String assetType) { this.assetType = assetType; }
    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }
    public Long getTableMetadataId() { return tableMetadataId; }
    public void setTableMetadataId(Long tableMetadataId) { this.tableMetadataId = tableMetadataId; }
    public LocalDate getStatDate() { return statDate; }
    public void setStatDate(LocalDate statDate) { this.statDate = statDate; }
    public Integer getBrowseCount() { return browseCount; }
    public void setBrowseCount(Integer browseCount) { this.browseCount = browseCount; }
    public Integer getReadCount() { return readCount; }
    public void setReadCount(Integer readCount) { this.readCount = readCount; }
    public Integer getBookmarkCount() { return bookmarkCount; }
    public void setBookmarkCount(Integer bookmarkCount) { this.bookmarkCount = bookmarkCount; }
    public Integer getRelateCount() { return relateCount; }
    public void setRelateCount(Integer relateCount) { this.relateCount = relateCount; }
}
