package com.bigdata.modules.metadata.vo;

import java.io.Serializable;
import java.util.List;

public class AgentTableDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String databaseName;
    private String tableName;
    private String tableComment;
    private String tableType;
    private String storageFormat;
    private Long rowCount;
    private Boolean canRead;
    private Boolean canWrite;
    private List<ColumnMetadataVO> columns;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getTableComment() {
        return tableComment;
    }

    public void setTableComment(String tableComment) {
        this.tableComment = tableComment;
    }

    public String getTableType() {
        return tableType;
    }

    public void setTableType(String tableType) {
        this.tableType = tableType;
    }

    public String getStorageFormat() {
        return storageFormat;
    }

    public void setStorageFormat(String storageFormat) {
        this.storageFormat = storageFormat;
    }

    public Long getRowCount() {
        return rowCount;
    }

    public void setRowCount(Long rowCount) {
        this.rowCount = rowCount;
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

    public List<ColumnMetadataVO> getColumns() {
        return columns;
    }

    public void setColumns(List<ColumnMetadataVO> columns) {
        this.columns = columns;
    }
}
