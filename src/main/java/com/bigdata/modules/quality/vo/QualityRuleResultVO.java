package com.bigdata.modules.quality.vo;


/**
 * QualityRuleResultVO
 *
 * <p>字段名与前端 src/types/quality.ts 的接口逐字对应。</p>
 *
 * @author dataforge
 */
public class QualityRuleResultVO {

    /** 结果 ID */
    private Long id;

    /** 运行实例 ID */
    private Long runId;

    /** 规则 ID */
    private Long ruleId;

    /** 规则名 */
    private String ruleName;

    /** 规则类型编码 */
    private String ruleType;

    /** 校验对象 */
    private String checkObject;

    /** 阈值 */
    private String threshold;

    /** 实际值 */
    private String actualValue;

    /** 校验状态 */
    private String status;

    /** 异常等级 */
    private String severity;

    /** 实际执行的校验 SQL */
    private String execSql;

    /** 失败原因 */
    private String errorMsg;

    /** 耗时（毫秒） */
    private Long costMs;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }
    public String getRuleType() { return ruleType; }
    public void setRuleType(String ruleType) { this.ruleType = ruleType; }
    public String getCheckObject() { return checkObject; }
    public void setCheckObject(String checkObject) { this.checkObject = checkObject; }
    public String getThreshold() { return threshold; }
    public void setThreshold(String threshold) { this.threshold = threshold; }
    public String getActualValue() { return actualValue; }
    public void setActualValue(String actualValue) { this.actualValue = actualValue; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getExecSql() { return execSql; }
    public void setExecSql(String execSql) { this.execSql = execSql; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    public Long getCostMs() { return costMs; }
    public void setCostMs(Long costMs) { this.costMs = costMs; }
}
