package com.bigdata.modules.integration.vo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 数据集成任务展示对象。
 */
public class IntegrationTaskVO {

    private Long id;
    private String taskName;
    private String taskCode;
    private String description;

    /** 所属项目 */
    private Long projectId;
    private String projectCode;
    private String projectName;

    private String sourceType;
    private String sourceTypeLabel;
    private Map<String, Object> sourceConfig;

    /** 目标端类型与连接参数（一份目标连接配置） */
    private String targetType;
    private String targetTypeLabel;
    private Map<String, Object> targetConfig;

    /** 需要同步的表清单，含排除字段与推导出的目标标识 */
    private List<IntegrationTableVO> tables;
    private Integer tableCount;
    /** 表清单摘要，如「ods.orders, ods.users 等 5 张表」 */
    private String tableSummary;

    private String clusterType;
    private Integer readParallelism;
    private Integer writeParallelism;
    private String jobMode;
    private String triggerType;
    private String triggerConfig;

    private Map<String, Object> extraParams;

    private String status;
    private String statusLabel;
    private String jobId;
    private LocalDateTime lastSubmitTime;
    private LocalDateTime lastOfflineTime;
    private String lastError;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceTypeLabel() {
        return sourceTypeLabel;
    }

    public void setSourceTypeLabel(String sourceTypeLabel) {
        this.sourceTypeLabel = sourceTypeLabel;
    }

    public Map<String, Object> getSourceConfig() {
        return sourceConfig;
    }

    public void setSourceConfig(Map<String, Object> sourceConfig) {
        this.sourceConfig = sourceConfig;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetTypeLabel() {
        return targetTypeLabel;
    }

    public void setTargetTypeLabel(String targetTypeLabel) {
        this.targetTypeLabel = targetTypeLabel;
    }

    public Map<String, Object> getTargetConfig() {
        return targetConfig;
    }

    public void setTargetConfig(Map<String, Object> targetConfig) {
        this.targetConfig = targetConfig;
    }

    public List<IntegrationTableVO> getTables() {
        return tables;
    }

    public void setTables(List<IntegrationTableVO> tables) {
        this.tables = tables;
    }

    public Integer getTableCount() {
        return tableCount;
    }

    public void setTableCount(Integer tableCount) {
        this.tableCount = tableCount;
    }

    public String getTableSummary() {
        return tableSummary;
    }

    public void setTableSummary(String tableSummary) {
        this.tableSummary = tableSummary;
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

    public Map<String, Object> getExtraParams() {
        return extraParams;
    }

    public void setExtraParams(Map<String, Object> extraParams) {
        this.extraParams = extraParams;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStatusLabel() {
        return statusLabel;
    }

    public void setStatusLabel(String statusLabel) {
        this.statusLabel = statusLabel;
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

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
