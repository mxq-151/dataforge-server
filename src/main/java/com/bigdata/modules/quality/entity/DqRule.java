package com.bigdata.modules.quality.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;

/**
 * dq_rule
 *
 * @author dataforge
 */
@TableName("dq_rule")
public class DqRule extends BaseEntity {

    /** 规则名 */
    private String ruleName;

    /** 规则类型编码（7 类之一） */
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

    /** 字段级规则的字段名；表级/跨表规则为空 */
    private String columnName;

    /** 分区表达式，如 dt=${yyyyMMdd} */
    private String partitionExpr;

    /** 跨表比对时的对照数据源 ID */
    private Long refDatasourceId;

    /** 对照库名 */
    private String refDatabaseName;

    /** 对照表名 */
    private String refTableName;

    /** 对照字段名 */
    private String refColumnName;

    /** 比较方式 EQ/NE/GT/GTE/LT/LTE */
    private String compareMode;

    /** 期望值 */
    private String expectedValue;

    /** 橙色（警告）阈值，空表示不设 */
    private String warnThreshold;

    /** 红色（错误）阈值，空表示不设 */
    private String errorThreshold;

    /** 处置方式 BLOCK=阻断下游 ALERT=告警 */
    private String disposeMode;

    /** 规则负责人 */
    private String ownerName;

    /** 是否启用 */
    private Integer enabled;

    /**
     * 自定义模板/可选口径的参数取值（JSON 对象文本）。
     *
     * <p>承载两类东西：内置规则的<b>可选</b>口径（如行数波动的基线窗口 baselineDays、
     * 对账的聚合口径 aggMode），以及自定义模板里 {@code ${params.xxx}} 的取值。
     * 未配置时留空，由 {@code DqBuiltinTemplates} 的默认值兜底。</p>
     */
    private String params;

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
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
    public String getParams() { return params; }
    public void setParams(String params) { this.params = params; }
}
