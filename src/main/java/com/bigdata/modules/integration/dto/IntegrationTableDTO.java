package com.bigdata.modules.integration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 需要同步的一张表。
 */
public class IntegrationTableDTO {

    /** 源表（库.表） */
    @NotBlank(message = "表名不能为空")
    @Size(max = 200, message = "表名不能超过200字符")
    private String sourceTable;

    /** 需要排除的字段，写入时不参与列清单 */
    private List<String> excludeColumns;

    private Integer sortNo;

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

    public Integer getSortNo() {
        return sortNo;
    }

    public void setSortNo(Integer sortNo) {
        this.sortNo = sortNo;
    }
}
