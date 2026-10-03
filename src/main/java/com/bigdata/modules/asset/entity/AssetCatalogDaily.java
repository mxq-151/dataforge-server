package com.bigdata.modules.asset.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;

import java.time.LocalDate;
import java.util.Date;

/**
 * asset_catalog_daily —— 资产目录日快照（一行 = 一张表 × 一个统计日）。
 *
 * <h3>为什么要有这张表</h3>
 * <p>目录树、检索、盘点、治理项原先都在查询时临时拼接：全量扫描 table_metadata、
 * 在内存里按表名解析分层与业务域、逐表重算治理项与关联计数。同一份结果被反复算，
 * 且接口耗时随资产规模线性上升。本表把「结构 + 治理属性 + 关联计数 + 使用热度」
 * 在 T+1 固化下来，查询侧只读快照。</p>
 *
 * <h3>T+1 的含义</h3>
 * <p>当天新建 / 变更 / 删除的表，次日才会体现在目录里。这是与
 * {@link com.bigdata.modules.asset.service.TableNameConventionParser} 「实时解析」
 * 之间的一次明确取舍：用一天的新鲜度换掉每次查询的全表解析。</p>
 *
 * <h3>为什么不存权限可见范围</h3>
 * <p>权限是安全边界，必须按请求实时判定，不进快照 —— 否则授权变更会被滞后一天。</p>
 *
 * @author dataforge
 */
@TableName("asset_catalog_daily")
public class AssetCatalogDaily extends BaseEntity {

    /** 统计日 */
    private LocalDate statDate;

    /** 表元数据 ID */
    private Long tableMetadataId;

    private Long datasourceId;
    private String datasourceName;
    private String storageEngine;
    private String databaseName;
    private String tableName;
    private String tableComment;
    private String tableType;

    // ── 命名规范解析结果（快照固化，避免查询期重复解析）────────────────────
    /** 分层编码；未解析出来为 null */
    private String layerCode;
    /** 业务域编码；未解析出来为 null */
    private String domainCode;
    /** 是否符合命名规范（四要素齐备） */
    private Integer conventionParsed;
    private String refreshCycle;

    // ── 治理属性 ──────────────────────────────────────────────────────────
    private String categoryCode;
    private String businessOwner;
    private String techOwner;
    private String topic;
    private String visibility;
    private String assetLevel;

    // ── 结构规模 ──────────────────────────────────────────────────────────
    private Long rowCount;
    private Long sizeBytes;
    private Integer columnCount;

    // ── 关联计数 ──────────────────────────────────────────────────────────
    private Integer tagCount;
    private Integer indicatorCount;
    private Integer qualityRuleCount;
    private Integer hasDoc;

    // ── 治理项 ────────────────────────────────────────────────────────────
    private Integer healthIssueCount;
    /** 治理项编码集合，逗号分隔（如 NO_TAG,NO_DOC） */
    private String healthIssueCodes;

    // ── 使用热度（T+1 固化）────────────────────────────────────────────────
    /** 驼峰推导会得到 browseCount30d，与库中 browse_count_30d 不符，须显式指定 */
    @TableField("browse_count_30d")
    private Integer browseCount30d;
    /** 同上，显式指定 read_count_30d */
    @TableField("read_count_30d")
    private Integer readCount30d;
    private Integer bookmarkCount;
    private Date lastAccessTime;

    public LocalDate getStatDate() { return statDate; }
    public void setStatDate(LocalDate statDate) { this.statDate = statDate; }
    public Long getTableMetadataId() { return tableMetadataId; }
    public void setTableMetadataId(Long tableMetadataId) { this.tableMetadataId = tableMetadataId; }
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
    public String getTableComment() { return tableComment; }
    public void setTableComment(String tableComment) { this.tableComment = tableComment; }
    public String getTableType() { return tableType; }
    public void setTableType(String tableType) { this.tableType = tableType; }
    public String getLayerCode() { return layerCode; }
    public void setLayerCode(String layerCode) { this.layerCode = layerCode; }
    public String getDomainCode() { return domainCode; }
    public void setDomainCode(String domainCode) { this.domainCode = domainCode; }
    public Integer getConventionParsed() { return conventionParsed; }
    public void setConventionParsed(Integer conventionParsed) { this.conventionParsed = conventionParsed; }
    public String getRefreshCycle() { return refreshCycle; }
    public void setRefreshCycle(String refreshCycle) { this.refreshCycle = refreshCycle; }
    public String getCategoryCode() { return categoryCode; }
    public void setCategoryCode(String categoryCode) { this.categoryCode = categoryCode; }
    public String getBusinessOwner() { return businessOwner; }
    public void setBusinessOwner(String businessOwner) { this.businessOwner = businessOwner; }
    public String getTechOwner() { return techOwner; }
    public void setTechOwner(String techOwner) { this.techOwner = techOwner; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public String getAssetLevel() { return assetLevel; }
    public void setAssetLevel(String assetLevel) { this.assetLevel = assetLevel; }
    public Long getRowCount() { return rowCount; }
    public void setRowCount(Long rowCount) { this.rowCount = rowCount; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public Integer getColumnCount() { return columnCount; }
    public void setColumnCount(Integer columnCount) { this.columnCount = columnCount; }
    public Integer getTagCount() { return tagCount; }
    public void setTagCount(Integer tagCount) { this.tagCount = tagCount; }
    public Integer getIndicatorCount() { return indicatorCount; }
    public void setIndicatorCount(Integer indicatorCount) { this.indicatorCount = indicatorCount; }
    public Integer getQualityRuleCount() { return qualityRuleCount; }
    public void setQualityRuleCount(Integer qualityRuleCount) { this.qualityRuleCount = qualityRuleCount; }
    public Integer getHasDoc() { return hasDoc; }
    public void setHasDoc(Integer hasDoc) { this.hasDoc = hasDoc; }
    public Integer getHealthIssueCount() { return healthIssueCount; }
    public void setHealthIssueCount(Integer healthIssueCount) { this.healthIssueCount = healthIssueCount; }
    public String getHealthIssueCodes() { return healthIssueCodes; }
    public void setHealthIssueCodes(String healthIssueCodes) { this.healthIssueCodes = healthIssueCodes; }
    public Integer getBrowseCount30d() { return browseCount30d; }
    public void setBrowseCount30d(Integer browseCount30d) { this.browseCount30d = browseCount30d; }
    public Integer getReadCount30d() { return readCount30d; }
    public void setReadCount30d(Integer readCount30d) { this.readCount30d = readCount30d; }
    public Integer getBookmarkCount() { return bookmarkCount; }
    public void setBookmarkCount(Integer bookmarkCount) { this.bookmarkCount = bookmarkCount; }
    public Date getLastAccessTime() { return lastAccessTime; }
    public void setLastAccessTime(Date lastAccessTime) { this.lastAccessTime = lastAccessTime; }
}
