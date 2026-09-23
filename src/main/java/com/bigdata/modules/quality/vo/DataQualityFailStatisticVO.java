package com.bigdata.modules.quality.vo;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 检查失败记录统计 VO（按 表 + 日期 + 检查项 聚合）
 */
public class DataQualityFailStatisticVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long datasourceId;
    private String datasourceName;
    private String databaseName;
    private String tableName;
    private String checkItem;
    private LocalDate checkDate;
    private Long checkRecordCount;
    private Long failRecordCount;

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

    public LocalDate getCheckDate() {
        return checkDate;
    }

    public void setCheckDate(LocalDate checkDate) {
        this.checkDate = checkDate;
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
}
