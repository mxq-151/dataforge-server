package com.bigdata.modules.quality.dto;


/**
 * QualityRunReportDTO
 *
 * <p>字段名与前端 src/api/quality.ts 的入参类型逐字对应。</p>
 *
 * @author dataforge
 */
public class QualityRunReportDTO {

    /** 数据源 ID */
    private Long datasourceId;

    /** 数据源名称 */
    private String datasourceName;

    /** 库名 */
    private String databaseName;

    /** 表名 */
    private String tableName;

    /** 业务批次 */
    private String batchNo;

    /** 规则名 */
    private String ruleName;

    /**
     * 平台规则 ID（可选，但**强烈建议回传**）。
     *
     * <p>回传是「程序自己算、结果发回平台」，规则名是字符串，一旦改名（设计文档改了、
     * 平台里没跟着改）就断链。带上 ruleId 后关联不再依赖名字。
     * 未传时服务端按「规则名 + 库 + 表」反查，查不到才落空。</p>
     */
    private Long ruleId;

    /** 规则类型编码 */
    private String ruleType;

    /** 校验对象 */
    private String checkObject;

    /** 阈值 */
    private String threshold;

    /** 实际值 */
    private String actualValue;

    /** 是否通过 */
    private Boolean passed;

    /** 检查记录数 */
    private Long checkRecordCount;

    /** 未通过记录数 */
    private Long failRecordCount;

    /** 未通过明细 */
    private String failDetail;

    /**
     * 实际执行的校验 SQL（可选，建议回传）。
     *
     * <p>模板是可变的 —— 生成代码时拉的模板，之后可能被改。把当时真正跑的 SQL 留在结果行里，
     * 「规则名叫什么」与「当时到底跑了什么」才可事后核对，不一致能被发现而不是隐形。</p>
     */
    private String execSql;

    public Long getDatasourceId() { return datasourceId; }
    public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }
    public String getDatasourceName() { return datasourceName; }
    public void setDatasourceName(String datasourceName) { this.datasourceName = datasourceName; }
    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }
    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public String getRuleType() { return ruleType; }
    public void setRuleType(String ruleType) { this.ruleType = ruleType; }
    public String getCheckObject() { return checkObject; }
    public void setCheckObject(String checkObject) { this.checkObject = checkObject; }
    public String getThreshold() { return threshold; }
    public void setThreshold(String threshold) { this.threshold = threshold; }
    public String getActualValue() { return actualValue; }
    public void setActualValue(String actualValue) { this.actualValue = actualValue; }
    public Boolean getPassed() { return passed; }
    public void setPassed(Boolean passed) { this.passed = passed; }
    public Long getCheckRecordCount() { return checkRecordCount; }
    public void setCheckRecordCount(Long checkRecordCount) { this.checkRecordCount = checkRecordCount; }
    public Long getFailRecordCount() { return failRecordCount; }
    public void setFailRecordCount(Long failRecordCount) { this.failRecordCount = failRecordCount; }
    public String getFailDetail() { return failDetail; }
    public void setFailDetail(String failDetail) { this.failDetail = failDetail; }
    public String getExecSql() { return execSql; }
    public void setExecSql(String execSql) { this.execSql = execSql; }
}
