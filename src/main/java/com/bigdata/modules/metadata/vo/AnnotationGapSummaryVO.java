package com.bigdata.modules.metadata.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * 「未标注表 / 字段」清单（风险项 R1）。
 * <p>
 * 用于让「表与字段的注释缺失」这件事变得可见：列表只包含存在缺失的表，
 * 表注释缺失与字段注释缺失分别计数，便于按严重程度排优先级补齐。
 * </p>
 */
public class AnnotationGapSummaryVO {

    /** 本次实际扫描的表数量 */
    private Integer tableScanned;
    /** 表注释缺失的表数量 */
    private Integer unannotatedTableCount;
    /** 字段注释缺失的字段数量 */
    private Integer unannotatedColumnCount;
    /** 是否因 limit 截断（true 表示还有未统计的表） */
    private Boolean truncated;
    /** 存在缺失的表清单 */
    private List<AnnotationGapTableVO> tables = new ArrayList<>();

    public Integer getTableScanned() {
        return tableScanned;
    }

    public void setTableScanned(Integer tableScanned) {
        this.tableScanned = tableScanned;
    }

    public Integer getUnannotatedTableCount() {
        return unannotatedTableCount;
    }

    public void setUnannotatedTableCount(Integer unannotatedTableCount) {
        this.unannotatedTableCount = unannotatedTableCount;
    }

    public Integer getUnannotatedColumnCount() {
        return unannotatedColumnCount;
    }

    public void setUnannotatedColumnCount(Integer unannotatedColumnCount) {
        this.unannotatedColumnCount = unannotatedColumnCount;
    }

    public Boolean getTruncated() {
        return truncated;
    }

    public void setTruncated(Boolean truncated) {
        this.truncated = truncated;
    }

    public List<AnnotationGapTableVO> getTables() {
        return tables;
    }

    public void setTables(List<AnnotationGapTableVO> tables) {
        this.tables = tables;
    }
}
