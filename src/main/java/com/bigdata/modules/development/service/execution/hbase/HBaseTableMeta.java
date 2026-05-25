package com.bigdata.modules.development.service.execution.hbase;

import java.util.ArrayList;
import java.util.List;

public class HBaseTableMeta {

    private String tableName;
    private List<String> columnFamilies;
    private List<String> columnNames;
    private List<String> columnTypes;

    public HBaseTableMeta() {
        this.columnFamilies = new ArrayList<>();
        this.columnNames = new ArrayList<>();
        this.columnTypes = new ArrayList<>();
    }

    public static HBaseTableMeta create(String tableName, String... columnFamilies) {
        HBaseTableMeta meta = new HBaseTableMeta();
        meta.tableName = tableName;
        for (String cf : columnFamilies) {
            meta.columnFamilies.add(cf);
        }
        return meta;
    }

    public void addColumn(String columnName, String columnType) {
        this.columnNames.add(columnName);
        this.columnTypes.add(columnType != null ? columnType : "VARCHAR");
    }

    public void addColumnFamily(String family) {
        if (!columnFamilies.contains(family)) {
            columnFamilies.add(family);
        }
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public List<String> getColumnFamilies() {
        return columnFamilies;
    }

    public void setColumnFamilies(List<String> columnFamilies) {
        this.columnFamilies = columnFamilies;
    }

    public List<String> getColumnNames() {
        return columnNames;
    }

    public void setColumnNames(List<String> columnNames) {
        this.columnNames = columnNames;
    }

    public List<String> getColumnTypes() {
        return columnTypes;
    }

    public void setColumnTypes(List<String> columnTypes) {
        this.columnTypes = columnTypes;
    }
}