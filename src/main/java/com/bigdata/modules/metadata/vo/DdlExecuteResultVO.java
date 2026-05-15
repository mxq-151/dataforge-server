package com.bigdata.modules.metadata.vo;

import java.io.Serializable;

public class DdlExecuteResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private boolean success;
    private String status;
    private String sqlType;
    private String objectType;
    private String objectName;
    private int executionTimeMs;
    private String errorMessage;
    private Integer metadataUpdated;

    public DdlExecuteResultVO() {
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSqlType() {
        return sqlType;
    }

    public void setSqlType(String sqlType) {
        this.sqlType = sqlType;
    }

    public String getObjectType() {
        return objectType;
    }

    public void setObjectType(String objectType) {
        this.objectType = objectType;
    }

    public String getObjectName() {
        return objectName;
    }

    public void setObjectName(String objectName) {
        this.objectName = objectName;
    }

    public int getExecutionTimeMs() {
        return executionTimeMs;
    }

    public void setExecutionTimeMs(int executionTimeMs) {
        this.executionTimeMs = executionTimeMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Integer getMetadataUpdated() {
        return metadataUpdated;
    }

    public void setMetadataUpdated(Integer metadataUpdated) {
        this.metadataUpdated = metadataUpdated;
    }
}
