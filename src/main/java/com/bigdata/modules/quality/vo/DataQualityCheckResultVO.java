package com.bigdata.modules.quality.vo;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 数据质量校验结果 VO
 */
public class DataQualityCheckResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long datasourceId;
    private String datasourceName;
    private String databaseName;
    private String tableName;
    private String checkItem;
    private LocalDateTime checkTime;
    private Long checkRecordCount;
    private Long failRecordCount;
    private String failDetail;
    /** 规则类型（7 类之一的编码）；历史数据为空表示未分类 */
    private String ruleType;
    /** 规则名 */
    private String ruleName;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

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

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
