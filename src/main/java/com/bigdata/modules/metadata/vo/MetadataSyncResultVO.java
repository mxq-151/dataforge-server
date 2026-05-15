package com.bigdata.modules.metadata.vo;

import java.io.Serializable;
import java.util.List;

public class MetadataSyncResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private boolean success;
    private String message;
    private int databaseCount;
    private int tableCount;
    private int columnCount;

    public MetadataSyncResultVO() {
    }

    public MetadataSyncResultVO(boolean success, String message, int databaseCount, int tableCount, int columnCount) {
        this.success = success;
        this.message = message;
        this.databaseCount = databaseCount;
        this.tableCount = tableCount;
        this.columnCount = columnCount;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getDatabaseCount() {
        return databaseCount;
    }

    public void setDatabaseCount(int databaseCount) {
        this.databaseCount = databaseCount;
    }

    public int getTableCount() {
        return tableCount;
    }

    public void setTableCount(int tableCount) {
        this.tableCount = tableCount;
    }

    public int getColumnCount() {
        return columnCount;
    }

    public void setColumnCount(int columnCount) {
        this.columnCount = columnCount;
    }
}
