package com.bigdata.modules.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.modules.devops.enums.DatasourceType;
import java.util.Date;

@TableName("column_metadata")
public class ColumnMetadata {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tableMetadataId;
    private DatasourceType storageEngine;
    private String databaseName;
    private String tableName;
    private String columnName;
    private String columnType;
    private String columnComment;
    private Integer isPartition;
    private Integer isPrimaryKey;
    private Integer ordinalPosition;
    private Integer nullable;
    private String defaultValue;
    private Date syncTime;
    private Date createTime;
    private Date updateTime;

    /** 业务描述（与 columnComment 的技术注释区分，供资产目录展示） */
    private String businessDesc;
    /** 字段安全等级 */
    private String securityLevel;
    /** 字段热度星级 0~5（由使用记录日汇总回写） */
    private Integer hotScore;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTableMetadataId() { return tableMetadataId; }
    public void setTableMetadataId(Long tableMetadataId) { this.tableMetadataId = tableMetadataId; }
    public DatasourceType getStorageEngine() { return storageEngine; }
    public void setStorageEngine(DatasourceType storageEngine) { this.storageEngine = storageEngine; }
    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public String getColumnName() { return columnName; }
    public void setColumnName(String columnName) { this.columnName = columnName; }
    public String getColumnType() { return columnType; }
    public void setColumnType(String columnType) { this.columnType = columnType; }
    public String getColumnComment() { return columnComment; }
    public void setColumnComment(String columnComment) { this.columnComment = columnComment; }
    public Integer getIsPartition() { return isPartition; }
    public void setIsPartition(Integer isPartition) { this.isPartition = isPartition; }
    public Integer getIsPrimaryKey() { return isPrimaryKey; }
    public void setIsPrimaryKey(Integer isPrimaryKey) { this.isPrimaryKey = isPrimaryKey; }
    public Integer getOrdinalPosition() { return ordinalPosition; }
    public void setOrdinalPosition(Integer ordinalPosition) { this.ordinalPosition = ordinalPosition; }
    public Integer getNullable() { return nullable; }
    public void setNullable(Integer nullable) { this.nullable = nullable; }
    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }
    public Date getSyncTime() { return syncTime; }
    public void setSyncTime(Date syncTime) { this.syncTime = syncTime; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
    public String getBusinessDesc() { return businessDesc; }
    public void setBusinessDesc(String businessDesc) { this.businessDesc = businessDesc; }
    public String getSecurityLevel() { return securityLevel; }
    public void setSecurityLevel(String securityLevel) { this.securityLevel = securityLevel; }
    public Integer getHotScore() { return hotScore; }
    public void setHotScore(Integer hotScore) { this.hotScore = hotScore; }
}
