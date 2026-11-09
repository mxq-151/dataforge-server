package com.bigdata.modules.integration.service.submit;

import java.util.Locale;

/**
 * 一张源表的列定义：列名 + Flink SQL 类型 + 是否主键。
 *
 * <p>列清单一律来自源库元数据（{@link SourceColumnResolver}），
 * 不再从连接配置的字段列表读取。主键用于 CDC 源与 JDBC Sink 的 upsert 语义：
 * JDBC Sink 收到 -U/+D 记录时必须声明主键，否则 Flink 直接拒绝提交。</p>
 */
public final class ColumnDef {

    private final String name;
    private final String flinkType;
    private final boolean primaryKey;

    public ColumnDef(String name, String flinkType) {
        this(name, flinkType, false);
    }

    public ColumnDef(String name, String flinkType, boolean primaryKey) {
        this.name = name == null ? "" : name.trim();
        this.flinkType = flinkType == null || flinkType.trim().isEmpty()
                ? "STRING" : flinkType.trim().toUpperCase(Locale.ROOT);
        this.primaryKey = primaryKey;
    }

    public String getName() {
        return name;
    }

    public String getFlinkType() {
        return flinkType;
    }

    public boolean isPrimaryKey() {
        return primaryKey;
    }
}
