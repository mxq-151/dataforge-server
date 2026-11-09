package com.bigdata.modules.integration.vo;

import java.util.List;

/**
 * 需要同步的一张表的展示对象。
 */
public class IntegrationTableVO {

    private Long id;
    private String sourceTable;
    private List<String> excludeColumns;
    /** 由目标端命名模板推导出的目标标识（topic / 索引 / 路径 / 表名） */
    private String targetIdentifier;
    private Integer sortNo;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSourceTable() {
        return sourceTable;
    }

    public void setSourceTable(String sourceTable) {
        this.sourceTable = sourceTable;
    }

    public List<String> getExcludeColumns() {
        return excludeColumns;
    }

    public void setExcludeColumns(List<String> excludeColumns) {
        this.excludeColumns = excludeColumns;
    }

    public String getTargetIdentifier() {
        return targetIdentifier;
    }

    public void setTargetIdentifier(String targetIdentifier) {
        this.targetIdentifier = targetIdentifier;
    }

    public Integer getSortNo() {
        return sortNo;
    }

    public void setSortNo(Integer sortNo) {
        this.sortNo = sortNo;
    }
}
