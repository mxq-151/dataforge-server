package com.bigdata.modules.metadata.vo;

import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.TableMetadata;
import java.util.List;

public class TableDetailVO {

    private TableMetadata tableInfo;
    private List<ColumnMetadata> columns;
    private Long columnCount;

    public TableDetailVO() {}

    public TableDetailVO(TableMetadata tableInfo, List<ColumnMetadata> columns) {
        this.tableInfo = tableInfo;
        this.columns = columns;
        this.columnCount = columns != null ? (long) columns.size() : 0L;
    }

    public TableMetadata getTableInfo() {
        return tableInfo;
    }

    public void setTableInfo(TableMetadata tableInfo) {
        this.tableInfo = tableInfo;
    }

    public List<ColumnMetadata> getColumns() {
        return columns;
    }

    public void setColumns(List<ColumnMetadata> columns) {
        this.columns = columns;
        if (this.columnCount == null) {
            this.columnCount = columns != null ? (long) columns.size() : 0L;
        }
    }

    public Long getColumnCount() {
        return columnCount;
    }

    public void setColumnCount(Long columnCount) {
        this.columnCount = columnCount;
    }
}
