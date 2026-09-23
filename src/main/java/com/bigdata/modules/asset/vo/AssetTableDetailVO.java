package com.bigdata.modules.asset.vo;

import java.io.Serializable;
import java.util.List;

/**
 * 资产详情聚合体：基础信息 + 字段 + 分区 + 分类标签 + 热度 + 治理项。
 *
 * <p>字段名与前端 src/api/assetCatalog.ts 的契约逐字对应，改动需两端同步。</p>
 *
 * @author dataforge
 */
public class AssetTableDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 表元数据 id */
    private Long id;

    /** 数据源 id */
    private Long datasourceId;

    /** 数据源名称 */
    private String datasourceName;

    /** 存储引擎 */
    private String storageEngine;

    /** 库名 */
    private String databaseName;

    /** 表名 */
    private String tableName;

    /** 表类型 */
    private String tableType;

    /** 表注释（技术侧） */
    private String tableComment;

    /** markdown 使用说明（来自 asset_doc，区别于 tableComment） */
    private String description;

    /** 业务负责人 */
    private String businessOwner;

    /** 技术负责人 */
    private String techOwner;

    /** 主题域 */
    private String topic;

    /** 可见性：public / internal / restricted */
    private String visibility;

    /** 人工覆盖的分类编码；空表示采用表名解析结果 */
    private String categoryCode;

    /** 分层编码（解析结果） */
    private String layerCode;

    /** 业务域编码（解析结果） */
    private String domainCode;

    /** 命名规范是否解析成功；false 时计入治理项 */
    private Boolean conventionParsed;

    /** 刷新周期后缀（d/h/w/m/y/r/f） */
    private String refreshCycle;

    /** 资产等级 */
    private String assetLevel;

    /** 行数 */
    private Long rowCount;

    /** 存储量 */
    private Long sizeBytes;

    /** 热数据窗口天数 */
    private Integer hotDays;

    /** 冷数据窗口天数 */
    private Integer coldDays;

    /** 保留天数 */
    private Integer keepDays;

    /** 存储格式 */
    private String storageFormat;

    /** 存储位置 */
    private String location;

    /** 分区字段 */
    private List<String> partitionColumns;

    /** 最近变更时间 */
    private String lastModifyTime;

    /** 元数据同步时间 */
    private String syncTime;

    /** 近30日浏览数 */
    private Integer browseCount30d;

    /** 近30日读取数 */
    private Integer readCount30d;

    /** 收藏数 */
    private Integer bookmarkCount;

    /** 最近访问时间 */
    private String lastAccessTime;

    /** 治理项计数（非加权评分） */
    private Integer healthIssueCount;

    /** 当前登录用户是否已收藏 */
    private Boolean bookmarked;

    /** 字段列表 */
    private List<AssetColumnVO> columns;

    /** 标签列表 */
    private List<AssetTagVO> tags;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDatasourceId() { return datasourceId; }
    public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }
    public String getDatasourceName() { return datasourceName; }
    public void setDatasourceName(String datasourceName) { this.datasourceName = datasourceName; }
    public String getStorageEngine() { return storageEngine; }
    public void setStorageEngine(String storageEngine) { this.storageEngine = storageEngine; }
    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public String getTableType() { return tableType; }
    public void setTableType(String tableType) { this.tableType = tableType; }
    public String getTableComment() { return tableComment; }
    public void setTableComment(String tableComment) { this.tableComment = tableComment; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getBusinessOwner() { return businessOwner; }
    public void setBusinessOwner(String businessOwner) { this.businessOwner = businessOwner; }
    public String getTechOwner() { return techOwner; }
    public void setTechOwner(String techOwner) { this.techOwner = techOwner; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public String getCategoryCode() { return categoryCode; }
    public void setCategoryCode(String categoryCode) { this.categoryCode = categoryCode; }
    public String getLayerCode() { return layerCode; }
    public void setLayerCode(String layerCode) { this.layerCode = layerCode; }
    public String getDomainCode() { return domainCode; }
    public void setDomainCode(String domainCode) { this.domainCode = domainCode; }
    public Boolean getConventionParsed() { return conventionParsed; }
    public void setConventionParsed(Boolean conventionParsed) { this.conventionParsed = conventionParsed; }
    public String getRefreshCycle() { return refreshCycle; }
    public void setRefreshCycle(String refreshCycle) { this.refreshCycle = refreshCycle; }
    public String getAssetLevel() { return assetLevel; }
    public void setAssetLevel(String assetLevel) { this.assetLevel = assetLevel; }
    public Long getRowCount() { return rowCount; }
    public void setRowCount(Long rowCount) { this.rowCount = rowCount; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public Integer getHotDays() { return hotDays; }
    public void setHotDays(Integer hotDays) { this.hotDays = hotDays; }
    public Integer getColdDays() { return coldDays; }
    public void setColdDays(Integer coldDays) { this.coldDays = coldDays; }
    public Integer getKeepDays() { return keepDays; }
    public void setKeepDays(Integer keepDays) { this.keepDays = keepDays; }
    public String getStorageFormat() { return storageFormat; }
    public void setStorageFormat(String storageFormat) { this.storageFormat = storageFormat; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public List<String> getPartitionColumns() { return partitionColumns; }
    public void setPartitionColumns(List<String> partitionColumns) { this.partitionColumns = partitionColumns; }
    public String getLastModifyTime() { return lastModifyTime; }
    public void setLastModifyTime(String lastModifyTime) { this.lastModifyTime = lastModifyTime; }
    public String getSyncTime() { return syncTime; }
    public void setSyncTime(String syncTime) { this.syncTime = syncTime; }
    public Integer getBrowseCount30d() { return browseCount30d; }
    public void setBrowseCount30d(Integer browseCount30d) { this.browseCount30d = browseCount30d; }
    public Integer getReadCount30d() { return readCount30d; }
    public void setReadCount30d(Integer readCount30d) { this.readCount30d = readCount30d; }
    public Integer getBookmarkCount() { return bookmarkCount; }
    public void setBookmarkCount(Integer bookmarkCount) { this.bookmarkCount = bookmarkCount; }
    public String getLastAccessTime() { return lastAccessTime; }
    public void setLastAccessTime(String lastAccessTime) { this.lastAccessTime = lastAccessTime; }
    public Integer getHealthIssueCount() { return healthIssueCount; }
    public void setHealthIssueCount(Integer healthIssueCount) { this.healthIssueCount = healthIssueCount; }
    public Boolean getBookmarked() { return bookmarked; }
    public void setBookmarked(Boolean bookmarked) { this.bookmarked = bookmarked; }
    public List<AssetColumnVO> getColumns() { return columns; }
    public void setColumns(List<AssetColumnVO> columns) { this.columns = columns; }
    public List<AssetTagVO> getTags() { return tags; }
    public void setTags(List<AssetTagVO> tags) { this.tags = tags; }
}
