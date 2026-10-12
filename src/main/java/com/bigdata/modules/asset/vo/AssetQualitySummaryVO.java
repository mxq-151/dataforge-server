package com.bigdata.modules.asset.vo;

import java.io.Serializable;
import java.util.List;

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

    /** 已启用的质检规则清单，供资产详情「数据质量」页签列表展示 */
    private List<EnabledRuleVO> enabledRules;

    /** 最近一次校验中未通过的记录样例，供资产详情「数据质量」页签列表展示 */
    private List<FailedSampleVO> failedSamples;

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
    public List<EnabledRuleVO> getEnabledRules() { return enabledRules; }
    public void setEnabledRules(List<EnabledRuleVO> enabledRules) { this.enabledRules = enabledRules; }
    public List<FailedSampleVO> getFailedSamples() { return failedSamples; }
    public void setFailedSamples(List<FailedSampleVO> failedSamples) { this.failedSamples = failedSamples; }

    /**
     * 已启用规则的精简视图：够列表展示即可，不带阈值 / 参数等配置细节。
     *
     * <p>字段名与前端 src/api/assetCatalog.ts 的 AssetQualityRuleVO 逐字对应。</p>
     */
    public static class EnabledRuleVO implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long id;

        /** 规则名 */
        private String ruleName;

        /** 规则类型编码（7 类之一），前端按 QUALITY_RULE_TYPES 映射中文名 */
        private String ruleType;

        /** 字段级规则的字段名；表级/跨表规则为空 */
        private String columnName;

        /** 处置方式 BLOCK=阻断下游 ALERT=告警 */
        private String disposeMode;

        /** 规则负责人 */
        private String ownerName;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getRuleName() { return ruleName; }
        public void setRuleName(String ruleName) { this.ruleName = ruleName; }
        public String getRuleType() { return ruleType; }
        public void setRuleType(String ruleType) { this.ruleType = ruleType; }
        public String getColumnName() { return columnName; }
        public void setColumnName(String columnName) { this.columnName = columnName; }
        public String getDisposeMode() { return disposeMode; }
        public void setDisposeMode(String disposeMode) { this.disposeMode = disposeMode; }
        public String getOwnerName() { return ownerName; }
        public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    }

    /**
     * 未通过校验记录的样例视图。
     *
     * <p>字段名与前端 src/api/assetCatalog.ts 的 AssetQualityFailedSampleVO 逐字对应。</p>
     */
    public static class FailedSampleVO implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 结果 ID */
        private Long id;

        /** 质检时间（结果留痕的创建时间） */
        private String checkTime;

        /** 质检规则名（结果自包含，规则被删后仍可追溯） */
        private String ruleName;

        /** 规则类型编码（7 类之一），前端按 QUALITY_RULE_TYPES 映射中文名 */
        private String ruleType;

        /** 校验状态 ERROR=红色异常 FAILED=校验失败 */
        private String status;

        /** 完整的数据样例：回传侧提交的未通过明细，原样保留不截断 */
        private String sample;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getCheckTime() { return checkTime; }
        public void setCheckTime(String checkTime) { this.checkTime = checkTime; }
        public String getRuleName() { return ruleName; }
        public void setRuleName(String ruleName) { this.ruleName = ruleName; }
        public String getRuleType() { return ruleType; }
        public void setRuleType(String ruleType) { this.ruleType = ruleType; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getSample() { return sample; }
        public void setSample(String sample) { this.sample = sample; }
    }
}
