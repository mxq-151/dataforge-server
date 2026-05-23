package com.bigdata.modules.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.modules.devops.enums.StorageEngineType;
import java.util.Date;

@TableName("table_metadata")
public class TableMetadata {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long datasourceId;
    private StorageEngineType storageEngine;
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
    private Long createBy;
    private Date createTime;
    private Date updateTime;
    private Integer deleted;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDatasourceId() { return datasourceId; }
    public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }
    public StorageEngineType getStorageEngine() { return storageEngine; }
    public void setStorageEngine(StorageEngineType storageEngine) { this.storageEngine = storageEngine; }
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
    public Long getCreateBy() { return createBy; }
    public void setCreateBy(Long createBy) { this.createBy = createBy; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
