package com.bigdata.modules.asset.vo;

import java.io.Serializable;

/**
 * 产出任务（代理查询调度系统，不在服务端落副本）。
 *
 * <p>字段名与前端 src/api/assetCatalog.ts 的契约逐字对应，改动需两端同步。</p>
 *
 * @author dataforge
 */
public class AssetProducerVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 调度项目编码 */
    private String projectCode;

    /** 工作流编码 */
    private Long workflowCode;

    /** 工作流名称 */
    private String workflowName;

    /** 节点名 */
    private String nodeName;

    /** 负责人 */
    private String ownerName;

    /** 调度周期 */
    private String scheduleCycle;

    /** 最近运行状态 */
    private String lastRunState;

    /** 最近运行时间 */
    private String lastRunTime;

    public String getProjectCode() { return projectCode; }
    public void setProjectCode(String projectCode) { this.projectCode = projectCode; }
    public Long getWorkflowCode() { return workflowCode; }
    public void setWorkflowCode(Long workflowCode) { this.workflowCode = workflowCode; }
    public String getWorkflowName() { return workflowName; }
    public void setWorkflowName(String workflowName) { this.workflowName = workflowName; }
    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public String getScheduleCycle() { return scheduleCycle; }
    public void setScheduleCycle(String scheduleCycle) { this.scheduleCycle = scheduleCycle; }
    public String getLastRunState() { return lastRunState; }
    public void setLastRunState(String lastRunState) { this.lastRunState = lastRunState; }
    public String getLastRunTime() { return lastRunTime; }
    public void setLastRunTime(String lastRunTime) { this.lastRunTime = lastRunTime; }
}
