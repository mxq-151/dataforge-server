package com.bigdata.modules.metadata.vo;

/**
 * 「未标注字段」条目：字段注释为空的字段（风险项 R1 的可见化清单）。
 */
public class AnnotationGapColumnVO {

    private String columnName;
    private String columnType;
    private Integer ordinalPosition;
    private Integer isPartition;

    public String getColumnName() {
        return columnName;
    }

    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }

    public String getColumnType() {
        return columnType;
    }

    public void setColumnType(String columnType) {
        this.columnType = columnType;
    }

    public Integer getOrdinalPosition() {
        return ordinalPosition;
    }

    public void setOrdinalPosition(Integer ordinalPosition) {
        this.ordinalPosition = ordinalPosition;
    }

    public Integer getIsPartition() {
        return isPartition;
    }

    public void setIsPartition(Integer isPartition) {
        this.isPartition = isPartition;
    }
}
