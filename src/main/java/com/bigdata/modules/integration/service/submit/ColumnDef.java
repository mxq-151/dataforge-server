package com.bigdata.modules.integration.service.submit;

import java.util.Locale;

/**
 * 一张源表的列定义：列名 + Flink SQL 类型。
 *
 * <p>列清单一律来自源库元数据（{@link SourceColumnResolver}），
 * 不再从连接配置的字段列表读取。</p>
 */
public final class ColumnDef {

    private final String name;
    private final String flinkType;

    public ColumnDef(String name, String flinkType) {
        this.name = name == null ? "" : name.trim();
        this.flinkType = flinkType == null || flinkType.trim().isEmpty()
                ? "STRING" : flinkType.trim().toUpperCase(Locale.ROOT);
    }

    public String getName() {
        return name;
    }

    public String getFlinkType() {
        return flinkType;
    }
}
