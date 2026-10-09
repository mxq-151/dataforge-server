package com.bigdata.modules.quality.vo;

/**
 * 规则登记回执。
 *
 * <p>「登记」是幂等的：按「规则名 + 库 + 表」判定同一条规则。已存在则原样返回既有记录
 * （<b>不覆盖</b> —— 平台里的阈值可能已被人手工调过），不存在才创建。
 * 调用方拿 {@code ruleId} 去实现校验逻辑与回传结果，关联不再依赖规则名字符串。</p>
 *
 * @author dataforge
 */
public class QualityRuleRegisterVO {

    /** 平台规则 ID —— 回传结果时带上它，改名不会断开链路 */
    private Long ruleId;

    /** 规则名（逐字回显，便于调用方核对匹配到的就是自己要的那条） */
    private String ruleName;

    /** 规则类型编码（7 类之一） */
    private String ruleType;

    /** 自动绑定到的模板编码；无内置模板时退化为规则类型编码 */
    private String templateCode;

    /** true=本次新建；false=已存在，直接复用 */
    private Boolean created;

    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }
    public String getRuleType() { return ruleType; }
    public void setRuleType(String ruleType) { this.ruleType = ruleType; }
    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }
    public Boolean getCreated() { return created; }
    public void setCreated(Boolean created) { this.created = created; }
}
