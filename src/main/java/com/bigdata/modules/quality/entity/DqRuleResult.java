package com.bigdata.modules.quality.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;

/**
 * dq_rule_result
 *
 * <p>规则级校验结果<b>留痕</b>。每条结果自包含（规则名、批次、阈值、实际值、计数、执行 SQL），
 * 因此不依赖任何外部实例即可定位与追溯 —— 这也是运行实例表被移除后它仍保留的原因：
 * 「行数波动 / 行数环比」的试跑基线、资产目录的质量摘要都以它为唯一数据源。</p>
 *
 * @author dataforge
 */
@TableName("dq_rule_result")
public class DqRuleResult extends BaseEntity {

    /** 业务批次（如 20260917），与回传入参一致 */
    private String batchNo;

    /** 规则 ID */
    private Long ruleId;

    /** 规则名（结果自包含，规则被删后仍可追溯） */
    private String ruleName;

    /** 规则类型编码 */
    private String ruleType;

    /** 校验对象（表.字段 或 上下游两表） */
    private String checkObject;

    /** 阈值 */
    private String threshold;

    /** 实际值 */
    private String actualValue;

    /**
     * 原始度量值（SQL 原样取回的数值）。
     *
     * <p>行数波动 / 行数环比的实际值是「涨跌幅」，但下一期算基线要用的是<b>当期行数</b>；
     * 跨表比对同理，只留相对差异(%)无法看出是哪一侧变了。因此单列一栏留痕。</p>
     *
     * <p>该字段不对外暴露（前端 QualityRuleResultVO 无此字段），属服务端内部留痕。</p>
     */
    private String metricValue;

    /** 校验状态 PASS/WARN/ERROR/FAILED */
    private String status;

    /** 异常等级，仅 WARN/ERROR 时有值 */
    private String severity;

    /** 检查记录数（回传侧口径，程序自报） */
    private Long checkRecordCount;

    /** 未通过记录数（回传侧口径，程序自报） */
    private Long failRecordCount;

    /** 本次实际执行的校验 SQL */
    private String execSql;

    /** 校验失败原因 / 未通过明细 */
    private String errorMsg;

    /** 耗时（毫秒） */
    private Long costMs;

    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
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
    public String getMetricValue() { return metricValue; }
    public void setMetricValue(String metricValue) { this.metricValue = metricValue; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public Long getCheckRecordCount() { return checkRecordCount; }
    public void setCheckRecordCount(Long checkRecordCount) { this.checkRecordCount = checkRecordCount; }
    public Long getFailRecordCount() { return failRecordCount; }
    public void setFailRecordCount(Long failRecordCount) { this.failRecordCount = failRecordCount; }
    public String getExecSql() { return execSql; }
    public void setExecSql(String execSql) { this.execSql = execSql; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    public Long getCostMs() { return costMs; }
    public void setCostMs(Long costMs) { this.costMs = costMs; }
}
