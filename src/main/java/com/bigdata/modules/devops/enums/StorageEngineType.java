package com.bigdata.modules.devops.enums;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public enum StorageEngineType {
    HIVE("Hive", "分布式数据仓库"),
    DORIS("Doris", "实时分析数据库"),
    STARROCKS("StarRocks", "实时分析数据库"),
    HBASE("HBase", "分布式列式存储"),
    MYSQL("MySQL", "关系型数据库"),
    ES("Elasticsearch", "搜索引擎"),
    CLICKHOUSE("ClickHouse", "列式存储数据库"),
    HUDI("Apache Hudi", "数据湖格式"),
    ICEBERG("Apache Iceberg", "数据湖格式");

    private final String name;
    private final String description;

    StorageEngineType(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public static List<StorageEngineType> allTypes() {
        return Arrays.asList(StorageEngineType.values());
    }

    public static List<String> allTypeNames() {
        return Arrays.stream(StorageEngineType.values())
                .map(StorageEngineType::getName)
                .collect(Collectors.toList());
    }
}
