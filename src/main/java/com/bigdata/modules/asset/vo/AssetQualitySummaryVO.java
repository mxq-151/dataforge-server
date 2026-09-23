package com.bigdata.modules.asset.vo;

import java.io.Serializable;

/**
 * 该表的质量规则摘要（跨模块读数据质量）。
 *
 * <p>字段名与前端 src/api/assetCatalog.ts 的契约逐字对应，改动需两端同步。</p>
 *
 * @author dataforge
 */
public class AssetQualitySummaryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 规则总数 */
    private Integer ruleCount;

    /** 启用规则数 */
    private Integer enabledRuleCount;

    /** 最近一次运行状态 */
    private String lastRunStatus;

    /** 最近一次运行时间 */
    private String lastRunTime;

    /** 红色异常数 */
    private Integer errorCount;

    /** 未通过数 */
    private Integer failCount;

    /** 未通过规则条数，用于在资产详情上提示质量问题 */
    private Integer problemRuleCount;

    public Integer getRuleCount() { return ruleCount; }
    public void setRuleCount(Integer ruleCount) { this.ruleCount = ruleCount; }
    public Integer getEnabledRuleCount() { return enabledRuleCount; }
    public void setEnabledRuleCount(Integer enabledRuleCount) { this.enabledRuleCount = enabledRuleCount; }
    public String getLastRunStatus() { return lastRunStatus; }
    public void setLastRunStatus(String lastRunStatus) { this.lastRunStatus = lastRunStatus; }
    public String getLastRunTime() { return lastRunTime; }
    public void setLastRunTime(String lastRunTime) { this.lastRunTime = lastRunTime; }
    public Integer getErrorCount() { return errorCount; }
    public void setErrorCount(Integer errorCount) { this.errorCount = errorCount; }
    public Integer getFailCount() { return failCount; }
    public void setFailCount(Integer failCount) { this.failCount = failCount; }
    public Integer getProblemRuleCount() { return problemRuleCount; }
    public void setProblemRuleCount(Integer problemRuleCount) { this.problemRuleCount = problemRuleCount; }
}
