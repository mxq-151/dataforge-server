package com.bigdata.modules.quality.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;
import java.time.LocalDateTime;

/**
 * 数据质量校验结果
 *
 * @author dataforge
 */
@TableName(value = "data_quality_check_result")
public class DataQualityCheckResult extends BaseEntity {

    /** 数据源ID */
    private Long datasourceId;

    /** 数据源名称 */
    private String datasourceName;

    /** 数据库名 */
    private String databaseName;

    /** 表名 */
    private String tableName;

    /** 检查项(如:空值率/唯一性/数据范围/主键缺失等) */
    private String checkItem;

    /** 检查时间 */
    private LocalDateTime checkTime;

    /** 检查记录数 */
    private Long checkRecordCount;

    /** 未通过记录数 */
    private Long failRecordCount;

    /** 未通过明细(JSON) */
    private String failDetail;

    /**
     * 规则类型（7 类之一的编码）。
     *
     * <p>历史数据为空，表示这条结果产生于「规则类型收敛」之前 —— 那时 check_item
     * 是自由文本。保留为空而不是回填成某个类型，因为无法判断它究竟属于哪一类，
     * 硬猜会让报告里的分类统计出现假数据。</p>
     */
    private String ruleType;

    /** 规则名（冗余，规则被删后仍可追溯是哪条规则产生的这条结果） */
    private String ruleName;

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
