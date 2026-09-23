package com.bigdata.modules.quality.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 数据质量校验结果 新增/更新 DTO
 */
public class DataQualityCheckResultDTO {

    /** 主键ID(更新时必填) */
    private Long id;

    /** 数据源ID */
    private Long datasourceId;

    /** 数据源名称 */
    @NotBlank(message = "数据源名称不能为空")
    private String datasourceName;

    /** 数据库名 */
    private String databaseName;

    /** 表名 */
    @NotBlank(message = "表名不能为空")
    private String tableName;

    /** 检查项 */
    @NotBlank(message = "检查项不能为空")
    private String checkItem;

    /** 检查时间 */
    @NotNull(message = "检查时间不能为空")
    private LocalDateTime checkTime;

    /** 检查记录数 */
    @NotNull(message = "检查记录数不能为空")
    private Long checkRecordCount;

    /** 未通过记录数 */
    @NotNull(message = "未通过记录数不能为空")
    private Long failRecordCount;

    /** 未通过明细(JSON) */
    private String failDetail;

    /** 规则类型（7 类之一的编码，可选；不传表示未分类） */
    private String ruleType;

    /** 规则名（可选） */
    private String ruleName;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDatasourceId() {
        return datasourceId;
    }

    public void setDatasourceId(Long datasourceId) {
        this.datasourceId = datasourceId;
    }

    public String getDatasourceName() {
        return datasourceName;
    }

    public void setDatasourceName(String datasourceName) {
        this.datasourceName = datasourceName;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getCheckItem() {
        return checkItem;
    }

    public void setCheckItem(String checkItem) {
        this.checkItem = checkItem;
    }

    public LocalDateTime getCheckTime() {
        return checkTime;
    }

    public void setCheckTime(LocalDateTime checkTime) {
        this.checkTime = checkTime;
    }

    public Long getCheckRecordCount() {
        return checkRecordCount;
    }

    public void setCheckRecordCount(Long checkRecordCount) {
        this.checkRecordCount = checkRecordCount;
    }

    public Long getFailRecordCount() {
        return failRecordCount;
    }

    public void setFailRecordCount(Long failRecordCount) {
        this.failRecordCount = failRecordCount;
    }

    public String getFailDetail() {
        return failDetail;
    }

    public void setFailDetail(String failDetail) {
        this.failDetail = failDetail;
    }

    public String getRuleType() {
        return ruleType;
    }

    public void setRuleType(String ruleType) {
        this.ruleType = ruleType;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }
}
