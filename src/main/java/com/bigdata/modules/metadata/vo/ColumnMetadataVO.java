package com.bigdata.modules.metadata.vo;

import com.bigdata.modules.devops.enums.DatasourceType;

import java.io.Serializable;
import java.util.Date;

public class ColumnMetadataVO implements Serializable {

    private static final long serialVersionUID = 1L;

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
    private String maskingType;
    private Boolean canRead;
    private Boolean canWrite;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTableMetadataId() {
        return tableMetadataId;
    }

    public void setTableMetadataId(Long tableMetadataId) {
        this.tableMetadataId = tableMetadataId;
    }

    public DatasourceType getStorageEngine() {
        return storageEngine;
    }

    public void setStorageEngine(DatasourceType storageEngine) {
        this.storageEngine = storageEngine;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getColumnName() {
        return columnName;
    }

    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }

    public String getColumnType() {
        return columnType;
    }

    public void setColumnType(String columnType) {
        this.columnType = columnType;
    }

    public String getColumnComment() {
        return columnComment;
    }

    public void setColumnComment(String columnComment) {
        this.columnComment = columnComment;
    }

    public Integer getIsPartition() {
        return isPartition;
    }

    public void setIsPartition(Integer isPartition) {
        this.isPartition = isPartition;
    }

    public Integer getIsPrimaryKey() {
        return isPrimaryKey;
    }

    public void setIsPrimaryKey(Integer isPrimaryKey) {
        this.isPrimaryKey = isPrimaryKey;
    }

    public Integer getOrdinalPosition() {
        return ordinalPosition;
    }

    public void setOrdinalPosition(Integer ordinalPosition) {
        this.ordinalPosition = ordinalPosition;
    }

    public Integer getNullable() {
        return nullable;
    }

    public void setNullable(Integer nullable) {
        this.nullable = nullable;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public Date getSyncTime() {
        return syncTime;
    }

    public void setSyncTime(Date syncTime) {
        this.syncTime = syncTime;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }

    public String getMaskingType() {
        return maskingType;
    }

    public void setMaskingType(String maskingType) {
        this.maskingType = maskingType;
    }

    public Boolean getCanRead() {
        return canRead;
    }

    public void setCanRead(Boolean canRead) {
        this.canRead = canRead;
    }

    public Boolean getCanWrite() {
        return canWrite;
    }

    public void setCanWrite(Boolean canWrite) {
        this.canWrite = canWrite;
    }
}
