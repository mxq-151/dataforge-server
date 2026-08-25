package com.bigdata.modules.devops.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.List;

public enum DatasourceType {

    MYSQL("mysql", "MySQL"),
    POSTGRESQL("postgresql", "PostgreSQL"),
    KYUUBI("kyuubi", "Kyuubi"),
    DORIS("doris", "Doris"),
    CLICKHOUSE("clickhouse", "ClickHouse"),
    STARROCKS("starrocks", "StarRocks"),
    ES("es", "Elasticsearch"),
    HIVE("hive", "Hive");

    @EnumValue
    @JsonValue
    private final String code;

    private final String description;

    DatasourceType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static DatasourceType fromCode(String code) {
        return Arrays.stream(values())
                .filter(t -> t.code.equalsIgnoreCase(code))
                .findFirst()
                .orElse(null);
    }

    public static List<DatasourceType> allTypes() {
        return Arrays.asList(values());
    }
}
