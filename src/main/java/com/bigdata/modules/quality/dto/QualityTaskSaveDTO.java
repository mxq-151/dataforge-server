package com.bigdata.modules.quality.dto;

import java.util.List;

/**
 * QualityTaskSaveDTO
 *
 * <p>字段名与前端 src/api/quality.ts 的入参类型逐字对应。</p>
 *
 * @author dataforge
 */
public class QualityTaskSaveDTO {

    /** 为空表示新建 */
    private Long id;

    /** 任务名称 */
    private String taskName;

    /** 任务负责人 */
    private String ownerName;

    /** 任务说明 */
    private String description;

    /** 数据源 ID */
    private Long datasourceId;

    /** 数据源名称 */
    private String datasourceName;

    /** 库名 */
    private String databaseName;

    /** 表名 */
    private String tableName;

    /** SCHEDULE / MANUAL，不传按 MANUAL */
    private String triggerType;

    /** 调度项目编码 */
    private String projectCode;

    /** 调度工作流编码 */
    private Long workflowCode;

    /** 调度工作流名称 */
    private String workflowName;

    /** 调度节点名 */
    private String nodeName;

    /** 任务级处置策略，不传按 ALERT */
    private String disposePolicy;

    /** 是否启用 */
    private Boolean enabled;

    /** 任务包含的规则 ID 列表（整体覆盖式保存） */
    private List<Long> ruleIds;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Long getDatasourceId() { return datasourceId; }
    public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }
    public String getDatasourceName() { return datasourceName; }
    public void setDatasourceName(String datasourceName) { this.datasourceName = datasourceName; }
    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public String getProjectCode() { return projectCode; }
    public void setProjectCode(String projectCode) { this.projectCode = projectCode; }
    public Long getWorkflowCode() { return workflowCode; }
    public void setWorkflowCode(Long workflowCode) { this.workflowCode = workflowCode; }
    public String getWorkflowName() { return workflowName; }
    public void setWorkflowName(String workflowName) { this.workflowName = workflowName; }
    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }
    public String getDisposePolicy() { return disposePolicy; }
    public void setDisposePolicy(String disposePolicy) { this.disposePolicy = disposePolicy; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public List<Long> getRuleIds() { return ruleIds; }
    public void setRuleIds(List<Long> ruleIds) { this.ruleIds = ruleIds; }
}
