package com.bigdata.modules.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.modules.devops.enums.DatasourceType;
import java.util.Date;

@TableName("table_metadata")
public class TableMetadata {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long datasourceId;
    private DatasourceType storageEngine;
    private String catalogName;
    private String databaseName;
    private String tableName;
    private String tableType;
    private String tableComment;
    private Date tableCreateTime;
    private Date lastModifyTime;
    private String partitionColumns;
    private String storageFormat;
    private String compression;
    private String location;
    private Long rowCount;
    private Long sizeBytes;
    private Integer hotDays = 90;
    private Integer coldDays = 180;
    private Integer keepDays = 720;
    private Date syncTime;
    private String tableFingerprint;
    private String columnsFingerprint;
    private Long createBy;
    private Date createTime;
    private Date updateTime;
    private Integer deleted;

    // ── 资产目录（asset_catalog）治理属性 ──────────────────────────────────
    // owner/topic/visibility 的字段名与语义对齐既有 indicators 表
    // （business_owner/tech_owner/topic/visibility），避免同平台两套 owner 语义。
    // 宽度说明：indicators 的负责人是 varchar(1024) 且未建索引；本表的负责人是
    // 资产目录的筛选维度，必须可建索引，而 MySQL/InnoDB 索引键上限 3072 字节，
    // utf8mb4 下 varchar(1024) 建索引会直接失败，故取 varchar(255)。

    /** 业务负责人（为空是治理项之一） */
    private String businessOwner;
    /** 技术负责人 */
    private String techOwner;
    /** 所属主题域 */
    private String topic;
    /** 可见性：public / internal / restricted */
    private String visibility;
    /** 人工覆盖的分类编码；为空表示采用表名规范解析结果 */
    private String categoryCode;
    /** 资产等级 L1~L5 */
    private String assetLevel;

    // ── 使用热度（T+1 由 asset_access_log 汇总回写）────────────────────────

    /** 近 30 日浏览数。驼峰推导会得到 browse_cnt30d，与库中 browse_cnt_30d 不符，须显式指定 */
    @TableField("browse_cnt_30d")
    private Integer browseCnt30d;
    /** 近 30 日读取数。同上，显式指定 read_cnt_30d */
    @TableField("read_cnt_30d")
    private Integer readCnt30d;
    /** 收藏数 */
    private Integer bookmarkCnt;
    /** 最近访问时间（热度排序用） */
    private Date lastAccessTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDatasourceId() { return datasourceId; }
    public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }
    public DatasourceType getStorageEngine() { return storageEngine; }
    public void setStorageEngine(DatasourceType storageEngine) { this.storageEngine = storageEngine; }
    public String getCatalogName() { return catalogName; }
    public void setCatalogName(String catalogName) { this.catalogName = catalogName; }
    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public String getTableType() { return tableType; }
    public void setTableType(String tableType) { this.tableType = tableType; }
    public String getTableComment() { return tableComment; }
    public void setTableComment(String tableComment) { this.tableComment = tableComment; }
    public Date getTableCreateTime() { return tableCreateTime; }
    public void setTableCreateTime(Date tableCreateTime) { this.tableCreateTime = tableCreateTime; }
    public Date getLastModifyTime() { return lastModifyTime; }
    public void setLastModifyTime(Date lastModifyTime) { this.lastModifyTime = lastModifyTime; }
    public String getPartitionColumns() { return partitionColumns; }
    public void setPartitionColumns(String partitionColumns) { this.partitionColumns = partitionColumns; }
    public String getStorageFormat() { return storageFormat; }
    public void setStorageFormat(String storageFormat) { this.storageFormat = storageFormat; }
    public String getCompression() { return compression; }
    public void setCompression(String compression) { this.compression = compression; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
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
    public Date getSyncTime() { return syncTime; }
    public void setSyncTime(Date syncTime) { this.syncTime = syncTime; }
    public String getTableFingerprint() { return tableFingerprint; }
    public void setTableFingerprint(String tableFingerprint) { this.tableFingerprint = tableFingerprint; }
    public String getColumnsFingerprint() { return columnsFingerprint; }
    public void setColumnsFingerprint(String columnsFingerprint) { this.columnsFingerprint = columnsFingerprint; }
    public Long getCreateBy() { return createBy; }
    public void setCreateBy(Long createBy) { this.createBy = createBy; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
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
    public String getAssetLevel() { return assetLevel; }
    public void setAssetLevel(String assetLevel) { this.assetLevel = assetLevel; }
    public Integer getBrowseCnt30d() { return browseCnt30d; }
    public void setBrowseCnt30d(Integer browseCnt30d) { this.browseCnt30d = browseCnt30d; }
    public Integer getReadCnt30d() { return readCnt30d; }
    public void setReadCnt30d(Integer readCnt30d) { this.readCnt30d = readCnt30d; }
    public Integer getBookmarkCnt() { return bookmarkCnt; }
    public void setBookmarkCnt(Integer bookmarkCnt) { this.bookmarkCnt = bookmarkCnt; }
    public Date getLastAccessTime() { return lastAccessTime; }
    public void setLastAccessTime(Date lastAccessTime) { this.lastAccessTime = lastAccessTime; }
}
