package com.bigdata.modules.quality.dto;


/**
 * QualityRuleSaveDTO
 *
 * <p>字段名与前端 src/api/quality.ts 的入参类型逐字对应。</p>
 *
 * @author dataforge
 */
public class QualityRuleSaveDTO {

    /** 为空表示新建 */
    private Long id;

    /** 规则名 */
    private String ruleName;

    /** 7 类规则类型编码之一 */
    private String ruleType;

    /** 所用模板 ID */
    private Long templateId;

    /** 所用模板编码 */
    private String templateCode;

    /** 数据源 ID */
    private Long datasourceId;

    /** 数据源名称 */
    private String datasourceName;

    /** 库名 */
    private String databaseName;

    /** 表名 */
    private String tableName;

    /** 字段级规则的字段名 */
    private String columnName;

    /** 分区表达式 */
    private String partitionExpr;

    /** 对照数据源 ID */
    private Long refDatasourceId;

    /** 对照库名 */
    private String refDatabaseName;

    /** 对照表名 */
    private String refTableName;

    /** 对照字段名 */
    private String refColumnName;

    /** 比较方式，不传按 EQ */
    private String compareMode;

    /** 期望值 */
    private String expectedValue;

    /** 橙色阈值 */
    private String warnThreshold;

    /** 红色阈值 */
    private String errorThreshold;

    /** 处置方式，不传按 ALERT */
    private String disposeMode;

    /** 规则负责人 */
    private String ownerName;

    /** 是否启用 */
    private Boolean enabled;

    /**
     * 可选口径参数（JSON 对象文本）。
     *
     * <p>契约里的 QualityRuleVO 未暴露该字段，此处属<b>服务端超集</b>扩展：
     * 前端不传时为 null，规则按 {@code DqBuiltinTemplates} 的默认口径执行，
     * 因此不会破坏既有前端；传了则覆盖默认（如行数波动的基线窗口 baselineDays、
     * 对账的聚合口径 aggMode），也可承载自定义模板的 {@code ${params.xxx}} 占位符取值。</p>
     */
    private String params;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }
    public String getRuleType() { return ruleType; }
    public void setRuleType(String ruleType) { this.ruleType = ruleType; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }
    public Long getDatasourceId() { return datasourceId; }
    public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }
    public String getDatasourceName() { return datasourceName; }
    public void setDatasourceName(String datasourceName) { this.datasourceName = datasourceName; }
    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public String getColumnName() { return columnName; }
    public void setColumnName(String columnName) { this.columnName = columnName; }
    public String getPartitionExpr() { return partitionExpr; }
    public void setPartitionExpr(String partitionExpr) { this.partitionExpr = partitionExpr; }
    public Long getRefDatasourceId() { return refDatasourceId; }
    public void setRefDatasourceId(Long refDatasourceId) { this.refDatasourceId = refDatasourceId; }
    public String getRefDatabaseName() { return refDatabaseName; }
    public void setRefDatabaseName(String refDatabaseName) { this.refDatabaseName = refDatabaseName; }
    public String getRefTableName() { return refTableName; }
    public void setRefTableName(String refTableName) { this.refTableName = refTableName; }
    public String getRefColumnName() { return refColumnName; }
    public void setRefColumnName(String refColumnName) { this.refColumnName = refColumnName; }
    public String getCompareMode() { return compareMode; }
    public void setCompareMode(String compareMode) { this.compareMode = compareMode; }
    public String getExpectedValue() { return expectedValue; }
    public void setExpectedValue(String expectedValue) { this.expectedValue = expectedValue; }
    public String getWarnThreshold() { return warnThreshold; }
    public void setWarnThreshold(String warnThreshold) { this.warnThreshold = warnThreshold; }
    public String getErrorThreshold() { return errorThreshold; }
    public void setErrorThreshold(String errorThreshold) { this.errorThreshold = errorThreshold; }
    public String getDisposeMode() { return disposeMode; }
    public void setDisposeMode(String disposeMode) { this.disposeMode = disposeMode; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getParams() { return params; }
    public void setParams(String params) { this.params = params; }
}
