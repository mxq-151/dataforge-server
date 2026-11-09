package com.bigdata.modules.integration.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 数据集成任务创建 / 编辑入参。
 *
 * <p>源端一份连接配置，{@code targets} 是「源表 → 目标」映射列表：
 * 每条映射自带源表与目标端定义，一张源表只对应一个目标。</p>
 */
public class IntegrationTaskDTO {

    private Long id;

    @NotBlank(message = "任务名称不能为空")
    @Size(max = 200, message = "任务名称不能超过200字符")
    private String taskName;

    @Size(max = 100, message = "任务编码不能超过100字符")
    private String taskCode;

    @Size(max = 500, message = "描述不能超过500字符")
    private String description;

    @NotBlank(message = "源端类型不能为空")
    private String sourceType;

    private Map<String, Object> sourceConfig;

    /** 目标端类型（一份目标连接配置） */
    private String targetType;

    /** 目标端连接参数（一份目标连接配置） */
    private Map<String, Object> targetConfig;

    /** 需要同步的表清单，每张表可配置排除字段 */
    @Valid
    private List<IntegrationTableDTO> tables;

    @NotBlank(message = "运行集群不能为空")
    private String clusterType;

    private Integer readParallelism;

    private Integer writeParallelism;

    /** BATCH / STREAM，缺省 BATCH */
    private String jobMode;

    /** MANUAL / CRON，缺省 MANUAL */
    private String triggerType;

    private String triggerConfig;

    private Map<String, Object> extraParams;

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

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
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

    public Map<String, Object> getTargetConfig() {
        return targetConfig;
    }

    public void setTargetConfig(Map<String, Object> targetConfig) {
        this.targetConfig = targetConfig;
    }

    public List<IntegrationTableDTO> getTables() {
        return tables;
    }

    public void setTables(List<IntegrationTableDTO> tables) {
        this.tables = tables;
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
}
