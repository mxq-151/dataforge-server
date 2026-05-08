package com.bigdata.modules.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;

@TableName("metadata_sync_log")
public class MetadataSyncLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long datasourceId;

    private String syncType;

    private String status;

    private Integer databaseCount;

    private Integer tableCount;

    private Integer columnCount;

    private Date startTime;

    private Date endTime;

    private Integer durationMs;

    private String errorMessage;

    private Long createBy;

    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDatasourceId() { return datasourceId; }
    public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }
    public String getSyncType() { return syncType; }
    public void setSyncType(String syncType) { this.syncType = syncType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getDatabaseCount() { return databaseCount; }
    public void setDatabaseCount(Integer databaseCount) { this.databaseCount = databaseCount; }
    public Integer getTableCount() { return tableCount; }
    public void setTableCount(Integer tableCount) { this.tableCount = tableCount; }
    public Integer getColumnCount() { return columnCount; }
    public void setColumnCount(Integer columnCount) { this.columnCount = columnCount; }
    public Date getStartTime() { return startTime; }
    public void setStartTime(Date startTime) { this.startTime = startTime; }
    public Date getEndTime() { return endTime; }
    public void setEndTime(Date endTime) { this.endTime = endTime; }
    public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(Integer durationMs) { this.durationMs = durationMs; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Long getCreateBy() { return createBy; }
    public void setCreateBy(Long createBy) { this.createBy = createBy; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
