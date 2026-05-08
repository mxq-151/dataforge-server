package com.bigdata.modules.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;

@TableName("ddl_execution_log")
public class DdlExecutionLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long datasourceId;

    private String databaseName;

    private String sqlContent;

    private String sqlType;

    private String objectType;

    private String objectName;

    private String status;

    private Integer affectedRows;

    private String errorMessage;

    private Integer executionTimeMs;

    private Integer metadataUpdated;

    private Long executeBy;

    private String executeByName;

    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDatasourceId() { return datasourceId; }
    public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }
    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
    public String getSqlContent() { return sqlContent; }
    public void setSqlContent(String sqlContent) { this.sqlContent = sqlContent; }
    public String getSqlType() { return sqlType; }
    public void setSqlType(String sqlType) { this.sqlType = sqlType; }
    public String getObjectType() { return objectType; }
    public void setObjectType(String objectType) { this.objectType = objectType; }
    public String getObjectName() { return objectName; }
    public void setObjectName(String objectName) { this.objectName = objectName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getAffectedRows() { return affectedRows; }
    public void setAffectedRows(Integer affectedRows) { this.affectedRows = affectedRows; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Integer getExecutionTimeMs() { return executionTimeMs; }
    public void setExecutionTimeMs(Integer executionTimeMs) { this.executionTimeMs = executionTimeMs; }
    public Integer getMetadataUpdated() { return metadataUpdated; }
    public void setMetadataUpdated(Integer metadataUpdated) { this.metadataUpdated = metadataUpdated; }
    public Long getExecuteBy() { return executeBy; }
    public void setExecuteBy(Long executeBy) { this.executeBy = executeBy; }
    public String getExecuteByName() { return executeByName; }
    public void setExecuteByName(String executeByName) { this.executeByName = executeByName; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
