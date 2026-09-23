package com.bigdata.modules.quality.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;

/**
 * dq_rule_template
 *
 * @author dataforge
 */
@TableName("dq_rule_template")
public class DqRuleTemplate extends BaseEntity {

    /** 模板编码，内置模板与 7 类规则类型编码同名 */
    private String templateCode;

    /** 模板名称 */
    private String templateName;

    /** 归属的规则类型编码，取自 QualityRuleType */
    private String ruleType;

    /** 模板类别 builtin / custom */
    private String templateKind;

    /** SQL 模板，含 ${占位符}；内置模板由平台维护 */
    private String sqlTemplate;

    /** 适用字段类型，逗号分隔；空表示不限 */
    private String fieldTypes;

    /** 占位符参数定义（JSON 字符串），供配置界面动态渲染表单 */
    private String paramSchema;

    /** 备注 */
    private String remark;

    /** 是否启用（内置模板恒为 1） */
    private Integer enabled;

    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }
    public String getTemplateName() { return templateName; }
    public void setTemplateName(String templateName) { this.templateName = templateName; }
    public String getRuleType() { return ruleType; }
    public void setRuleType(String ruleType) { this.ruleType = ruleType; }
    public String getTemplateKind() { return templateKind; }
    public void setTemplateKind(String templateKind) { this.templateKind = templateKind; }
    public String getSqlTemplate() { return sqlTemplate; }
    public void setSqlTemplate(String sqlTemplate) { this.sqlTemplate = sqlTemplate; }
    public String getFieldTypes() { return fieldTypes; }
    public void setFieldTypes(String fieldTypes) { this.fieldTypes = fieldTypes; }
    public String getParamSchema() { return paramSchema; }
    public void setParamSchema(String paramSchema) { this.paramSchema = paramSchema; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
}
