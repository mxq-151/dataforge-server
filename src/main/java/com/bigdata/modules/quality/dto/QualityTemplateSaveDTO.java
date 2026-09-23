package com.bigdata.modules.quality.dto;


/**
 * QualityTemplateSaveDTO
 *
 * <p>字段名与前端 src/api/quality.ts 的入参类型逐字对应。</p>
 *
 * @author dataforge
 */
public class QualityTemplateSaveDTO {

    /** 为空表示新建 */
    private Long id;

    /** 模板编码（自定义模板必填，需全局唯一） */
    private String templateCode;

    /** 模板名称 */
    private String templateName;

    /** 必须归属 7 类规则类型之一 */
    private String ruleType;

    /** builtin / custom；不传按 custom 处理 */
    private String templateKind;

    /** SQL 模板，含 ${占位符} */
    private String sqlTemplate;

    /** 适用字段类型，逗号分隔 */
    private String fieldTypes;

    /** 占位符参数定义（JSON 字符串） */
    private String paramSchema;

    /** 备注 */
    private String remark;

    /** 是否启用 */
    private Boolean enabled;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
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
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
