package com.bigdata.modules.integration.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;

import java.time.LocalDateTime;

/**
 * 数据集成任务。
 *
 * <p>源端 / 目标端连接参数与额外透传参数均以 JSON 文本存储，
 * 便于在不改表结构的前提下扩展新的数据源类型。</p>
 */
@TableName("integration_task")
public class IntegrationTask extends BaseEntity {

    private String taskName;
    private String taskCode;
    private String description;

    /** 所属项目 ID（dev_project.id）；历史任务为空 */
    private Long projectId;
    /** 所属项目编码快照（来自 dev_project.project_code），上线时作为数据血缘 namespace */
    private String projectCode;

    private String sourceType;
    private String sourceConfig;

    private String targetType;
    private String targetConfig;

    /** flink-standalone（历史 seatunnel 值已由迁移脚本转换） */
    private String clusterType;
    private Integer readParallelism;
    private Integer writeParallelism;
    /** BATCH / STREAM */
    private String jobMode;
    /** MANUAL / CRON */
    private String triggerType;
    private String triggerConfig;

    /** 额外透传参数（JSON），提交时合并进作业配置 */
    private String extraParams;

    /** DRAFT / PUBLISHED / RUNNING / FAILED / OFFLINE */
    private String status;
    private String jobId;
    private LocalDateTime lastSubmitTime;
    private LocalDateTime lastOfflineTime;
    private String lastError;

    private Long createBy;

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public String getTaskCode() {
        return taskCode;
    }

    public void setTaskCode(String taskCode) {
        this.taskCode = taskCode;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getProjectCode() {
        return projectCode;
    }

    public void setProjectCode(String projectCode) {
        this.projectCode = projectCode;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceConfig() {
        return sourceConfig;
    }

    public void setSourceConfig(String sourceConfig) {
        this.sourceConfig = sourceConfig;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetConfig() {
        return targetConfig;
    }

    public void setTargetConfig(String targetConfig) {
        this.targetConfig = targetConfig;
    }

    public String getClusterType() {
        return clusterType;
    }

    public void setClusterType(String clusterType) {
        this.clusterType = clusterType;
    }

    public Integer getReadParallelism() {
        return readParallelism;
    }

    public void setReadParallelism(Integer readParallelism) {
        this.readParallelism = readParallelism;
    }

    public Integer getWriteParallelism() {
        return writeParallelism;
    }

    public void setWriteParallelism(Integer writeParallelism) {
        this.writeParallelism = writeParallelism;
    }

    public String getJobMode() {
        return jobMode;
    }

    public void setJobMode(String jobMode) {
        this.jobMode = jobMode;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    public String getTriggerConfig() {
        return triggerConfig;
    }

    public void setTriggerConfig(String triggerConfig) {
        this.triggerConfig = triggerConfig;
    }

    public String getExtraParams() {
        return extraParams;
    }

    public void setExtraParams(String extraParams) {
        this.extraParams = extraParams;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public LocalDateTime getLastSubmitTime() {
        return lastSubmitTime;
    }

    public void setLastSubmitTime(LocalDateTime lastSubmitTime) {
        this.lastSubmitTime = lastSubmitTime;
    }

    public LocalDateTime getLastOfflineTime() {
        return lastOfflineTime;
    }

    public void setLastOfflineTime(LocalDateTime lastOfflineTime) {
        this.lastOfflineTime = lastOfflineTime;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Long getCreateBy() {
        return createBy;
    }

    public void setCreateBy(Long createBy) {
        this.createBy = createBy;
    }
}
