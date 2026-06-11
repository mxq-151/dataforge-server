package com.bigdata.modules.devops.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.List;

/**
 * 数据源连接方式枚举
 * <p>
 * 将连接协议从 DatasourceType 中解耦，支持同一数据源类型使用不同连接方式。
 * 例如：Kyuubi 既可以通过 JDBC (jdbc:kyuubi://) 连接，也可以通过 REST HTTP API 连接。
 */
public enum ConnectionType {

    JDBC("jdbc", "JDBC"),
    HTTP("http", "HTTP"),
    ARROW("arrow", "Arrow Flight");

    @EnumValue
    @JsonValue
    private final String code;

    private final String description;

    ConnectionType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static ConnectionType fromCode(String code) {
        if (code == null || code.isEmpty()) {
            return JDBC; // 默认JDBC
        }
        return Arrays.stream(values())
                .filter(t -> t.code.equalsIgnoreCase(code))
                .findFirst()
                .orElse(JDBC);
    }

    public static List<ConnectionType> allTypes() {
        return Arrays.asList(values());
    }

    /**
     * 判断是否为JDBC类型的连接（需要HikariCP连接池管理）
     */
    public boolean isJdbcBased() {
        return this == JDBC;
    }

    /**
     * 判断是否为HTTP类型的连接（REST API调用）
     */
    public boolean isHttpBased() {
        return this == HTTP;
    }
}
