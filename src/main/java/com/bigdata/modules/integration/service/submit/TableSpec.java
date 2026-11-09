package com.bigdata.modules.integration.service.submit;

import com.bigdata.common.exception.BusinessException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 提交时使用的一张「需要同步的表」及其排除字段（与数据库实体解耦，便于单测直接构造）。
 *
 * <p>表名只来自表清单本身；字段清单由源库元数据读取后挂在这里（{@link #withColumns}），
 * 生成作业时通过 {@link #projection()} 得到「全部字段 - 排除字段」。</p>
 */
public class TableSpec {

    private final String sourceTable;
    private final List<String> excludeColumns;
    private final int sortNo;
    private final List<ColumnDef> columns;

    public TableSpec(String sourceTable, List<String> excludeColumns, int sortNo) {
        this(sourceTable, excludeColumns, sortNo, List.of());
    }

    public TableSpec(String sourceTable, List<String> excludeColumns, int sortNo, List<ColumnDef> columns) {
        this.sourceTable = sourceTable == null ? "" : sourceTable.trim();
        this.excludeColumns = excludeColumns == null ? List.of() : excludeColumns.stream()
                .filter(item -> item != null && !item.trim().isEmpty())
                .map(String::trim)
                .collect(Collectors.toList());
        this.sortNo = sortNo;
        this.columns = columns == null ? List.of() : List.copyOf(columns);
    }

    /** 直接按逗号分隔的排除字段构造 */
    public TableSpec(String sourceTable, String excludeColumns) {
        this(sourceTable, split(excludeColumns), 0);
    }

    /** 排除字段以列表传入，排序号默认 0 */
    public TableSpec(String sourceTable, List<String> excludeColumns) {
        this(sourceTable, excludeColumns, 0);
    }

    public String getSourceTable() {
        return sourceTable;
    }

    public List<String> getExcludeColumns() {
        return excludeColumns;
    }

    public int getSortNo() {
        return sortNo;
    }

    public List<ColumnDef> getColumns() {
        return columns;
    }

    /** 挂上从源库元数据读取到的字段清单 */
    public TableSpec withColumns(List<ColumnDef> resolved) {
        return new TableSpec(sourceTable, excludeColumns, sortNo, resolved);
    }

    /** 实际同步的列：全部字段去掉排除字段；结果为空说明配置有误，直接报错 */
    public List<ColumnDef> projection() {
        if (columns.isEmpty()) {
            throw new BusinessException(400, "表「" + sourceTable
                    + "」没有可用的字段清单，请确认源端连接、库名.表名是否正确");
        }
        List<ColumnDef> result = columns.stream()
                .filter(column -> !isExcluded(column.getName()))
                .collect(Collectors.toList());
        if (result.isEmpty()) {
            throw new BusinessException(400, "表「" + sourceTable
                    + "」排除字段后没有可同步的列，请检查排除字段");
        }
        return result;
    }

    /** 库名部分；表名不含库时为整串 */
    public String database() {
        int dot = sourceTable.lastIndexOf('.');
        return dot > 0 ? sourceTable.substring(0, dot) : "";
    }

    /** 表名部分（去掉库名前缀） */
    public String table() {
        int dot = sourceTable.lastIndexOf('.');
        return dot >= 0 ? sourceTable.substring(dot + 1) : sourceTable;
    }

    public boolean isExcluded(String column) {
        for (String item : excludeColumns) {
            if (item.equalsIgnoreCase(column)) {
                return true;
            }
        }
        return false;
    }

    public String describe() {
        return excludeColumns.isEmpty() ? sourceTable
                : sourceTable + "（排除: " + String.join(",", excludeColumns) + "）";
    }

    private static List<String> split(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toList());
    }
}
