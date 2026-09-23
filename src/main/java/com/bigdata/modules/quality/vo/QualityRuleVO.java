package com.bigdata.modules.quality.vo;

import java.time.LocalDateTime;

/**
 * QualityRuleVO
 *
 * <p>字段名与前端 src/types/quality.ts 的接口逐字对应。</p>
 *
 * @author dataforge
 */
public class QualityRuleVO {

    /** 规则 ID */
    private Long id;

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

    /** 处置方式 BLOCK / ALERT */
    private String disposeMode;

    /** 规则负责人 */
    private String ownerName;

    /** 是否启用 */
    private Boolean enabled;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

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
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
