package com.bigdata.modules.metadata.vo;

import com.bigdata.modules.devops.enums.DatasourceType;

import java.util.ArrayList;
import java.util.List;

/**
 * 「未标注表」条目：表注释为空、和/或存在字段注释为空的表（风险项 R1 的可见化清单）。
 */
public class AnnotationGapTableVO {

    private Long id;
    private Long datasourceId;
    private String datasourceName;
    private DatasourceType storageEngine;
    private String databaseName;
    private String tableName;
    private String tableComment;
    /** 表注释缺失 */
    private Boolean tableCommentMissing;
    /** 字段总数 */
    private Integer columnCount;
    /** 字段注释缺失数量 */
    private Integer unannotatedColumnCount;
    /** 字段注释缺失明细 */
    private List<AnnotationGapColumnVO> unannotatedColumns = new ArrayList<>();

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

    public DatasourceType getStorageEngine() {
        return storageEngine;
    }

    public void setStorageEngine(DatasourceType storageEngine) {
        this.storageEngine = storageEngine;
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

    public String getTableComment() {
        return tableComment;
    }

    public void setTableComment(String tableComment) {
        this.tableComment = tableComment;
    }

    public Boolean getTableCommentMissing() {
        return tableCommentMissing;
    }

    public void setTableCommentMissing(Boolean tableCommentMissing) {
        this.tableCommentMissing = tableCommentMissing;
    }

    public Integer getColumnCount() {
        return columnCount;
    }

    public void setColumnCount(Integer columnCount) {
        this.columnCount = columnCount;
    }

    public Integer getUnannotatedColumnCount() {
        return unannotatedColumnCount;
    }

    public void setUnannotatedColumnCount(Integer unannotatedColumnCount) {
        this.unannotatedColumnCount = unannotatedColumnCount;
    }

    public List<AnnotationGapColumnVO> getUnannotatedColumns() {
        return unannotatedColumns;
    }

    public void setUnannotatedColumns(List<AnnotationGapColumnVO> unannotatedColumns) {
        this.unannotatedColumns = unannotatedColumns;
    }
}
