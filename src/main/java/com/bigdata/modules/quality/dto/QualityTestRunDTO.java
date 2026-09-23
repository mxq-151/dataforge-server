package com.bigdata.modules.quality.dto;

import com.bigdata.modules.quality.dto.QualityRuleSaveDTO;

/**
 * QualityTestRunDTO
 *
 * <p>字段名与前端 src/api/quality.ts 的入参类型逐字对应。</p>
 *
 * @author dataforge
 */
public class QualityTestRunDTO {

    /** 已有规则可直接按 id 试跑 */
    private Long ruleId;

    /** 未保存的规则草稿（与 ruleId 二选一） */
    private QualityRuleSaveDTO rule;

    /** 批次号，空则由服务端按当日生成 */
    private String batchNo;

    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public QualityRuleSaveDTO getRule() { return rule; }
    public void setRule(QualityRuleSaveDTO rule) { this.rule = rule; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
}
