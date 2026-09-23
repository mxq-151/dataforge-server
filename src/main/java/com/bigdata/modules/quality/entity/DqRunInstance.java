package com.bigdata.modules.quality.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;
import java.time.LocalDateTime;

/**
 * dq_run_instance
 *
 * @author dataforge
 */
@TableName("dq_run_instance")
public class DqRunInstance extends BaseEntity {

    /** 质检任务 ID */
    private Long taskId;

    /** 任务名（结果自包含） */
    private String taskName;

    /** 本次校验的业务批次，如 20260917 */
    private String batchNo;

    /** 触发方式 SCHEDULE / MANUAL */
    private String triggerType;

    /** 调度实例号，用于跳转调度实例详情 */
    private Long scheduleInstanceId;

    /** 监控对象数据源 ID */
    private Long targetDatasourceId;

    /** 监控对象库名 */
    private String targetDatabaseName;

    /** 监控对象表名 */
    private String targetTableName;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 运行状态 RUNNING/SUCCESS/FAILED */
    private String status;

    /** 检查记录总数 */
    private Long checkRecordCount;

    /** 未通过记录总数 */
    private Long failRecordCount;

    /** 通过规则数 */
    private Integer passCount;

    /** 橙色异常规则数 */
    private Integer warnCount;

    /** 红色异常规则数 */
    private Integer errorCount;

    /** SQL 未跑通的规则数，与「校验不通过」区分 */
    private Integer failedCount;

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public Long getScheduleInstanceId() { return scheduleInstanceId; }
    public void setScheduleInstanceId(Long scheduleInstanceId) { this.scheduleInstanceId = scheduleInstanceId; }
    public Long getTargetDatasourceId() { return targetDatasourceId; }
    public void setTargetDatasourceId(Long targetDatasourceId) { this.targetDatasourceId = targetDatasourceId; }
    public String getTargetDatabaseName() { return targetDatabaseName; }
    public void setTargetDatabaseName(String targetDatabaseName) { this.targetDatabaseName = targetDatabaseName; }
    public String getTargetTableName() { return targetTableName; }
    public void setTargetTableName(String targetTableName) { this.targetTableName = targetTableName; }
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCheckRecordCount() { return checkRecordCount; }
    public void setCheckRecordCount(Long checkRecordCount) { this.checkRecordCount = checkRecordCount; }
    public Long getFailRecordCount() { return failRecordCount; }
    public void setFailRecordCount(Long failRecordCount) { this.failRecordCount = failRecordCount; }
    public Integer getPassCount() { return passCount; }
    public void setPassCount(Integer passCount) { this.passCount = passCount; }
    public Integer getWarnCount() { return warnCount; }
    public void setWarnCount(Integer warnCount) { this.warnCount = warnCount; }
    public Integer getErrorCount() { return errorCount; }
    public void setErrorCount(Integer errorCount) { this.errorCount = errorCount; }
    public Integer getFailedCount() { return failedCount; }
    public void setFailedCount(Integer failedCount) { this.failedCount = failedCount; }
}
