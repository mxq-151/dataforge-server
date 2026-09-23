package com.bigdata.modules.quality.vo;

import java.time.LocalDateTime;

/**
 * QualityRunInstanceVO
 *
 * <p>字段名与前端 src/types/quality.ts 的接口逐字对应。</p>
 *
 * @author dataforge
 */
public class QualityRunInstanceVO {

    /** 运行实例 ID */
    private Long id;

    /** 质检任务 ID */
    private Long taskId;

    /** 任务名 */
    private String taskName;

    /** 业务批次 */
    private String batchNo;

    /** 触发方式 */
    private String triggerType;

    /** 调度实例号 */
    private Long scheduleInstanceId;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 运行状态 */
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

    /** SQL 未跑通的规则数 */
    private Integer failedCount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
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
