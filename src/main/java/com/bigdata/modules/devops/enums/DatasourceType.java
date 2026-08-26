package com.bigdata.modules.devops.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.bigdata.modules.development.service.execution.CodeType;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 统一的数据源类型枚举。
 * <p>
 * 合并了原 {@code EngineType}（计算引擎）与 {@code StorageEngineType}（存储引擎）两个枚举，
 * 通过 {@code exec} / {@code store} 标记区分其是否具备计算能力与存储能力，
 * 并通过 {@code language} 声明其支持的代码类型（{@link CodeType}）。
 * </p>
 */
public enum DatasourceType {

    SPARK("spark", "Apache Spark", true, false, Arrays.asList(CodeType.JAVA, CodeType.SCALA, CodeType.PYTHON, CodeType.SQL)),
    FLINK("flink", "Apache Flink", true, false, Arrays.asList(CodeType.JAVA, CodeType.SCALA, CodeType.PYTHON, CodeType.SQL)),
    KYUUBI("kyuubi", "Apache Kyuubi", true, false, Arrays.asList(CodeType.SQL)),
    DORIS("doris", "Apache Doris", true, true, Arrays.asList(CodeType.SQL)),
    HBASE("hbase", "Apache HBase", true, true, Arrays.asList(CodeType.SQL)),
    MYSQL("mysql", "MySQL", true, true, Arrays.asList(CodeType.SQL)),
    POSTGRESQL("postgresql", "PostgreSQL", true, true, Arrays.asList(CodeType.SQL)),
    CLICKHOUSE("clickhouse", "ClickHouse", true, true, Arrays.asList(CodeType.SQL)),
    HIVE("hive", "Hive", false, true, Arrays.asList(CodeType.SQL)),
    STARROCKS("starrocks", "StarRocks", false, true, Arrays.asList(CodeType.SQL)),
    ES("es", "Elasticsearch", false, true, Arrays.asList()),
    HUDI("hudi", "Apache Hudi", false, true, Arrays.asList(CodeType.SQL)),
    ICEBERG("iceberg", "Apache Iceberg", false, true, Arrays.asList(CodeType.SQL));

    @EnumValue
    @JsonValue
    private final String code;

    private final String description;

    /** 是否具备计算（执行）能力 */
    private final boolean exec;

    /** 是否具备存储能力 */
    private final boolean store;

    /** 支持的代码类型 */
    private final List<CodeType> language;

    DatasourceType(String code, String description, boolean exec, boolean store, List<CodeType> language) {
        this.code = code;
        this.description = description;
        this.exec = exec;
        this.store = store;
        this.language = language;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public boolean isExec() {
        return exec;
    }

    public boolean isStore() {
        return store;
    }

    public List<CodeType> getLanguage() {
        return language;
    }

    /**
     * 兼容原 EngineType#getSupportedCodeTypes() 的语义别名。
     */
    public List<CodeType> getSupportedCodeTypes() {
        return language;
    }

    public boolean supportsLanguage(CodeType codeType) {
        return language != null && language.contains(codeType);
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

    /** 所有具备计算能力的类型（exec=true） */
    public static List<DatasourceType> execTypes() {
        return Arrays.stream(values()).filter(DatasourceType::isExec).collect(Collectors.toList());
    }

    /** 所有具备存储能力的类型（store=true） */
    public static List<DatasourceType> storeTypes() {
        return Arrays.stream(values()).filter(DatasourceType::isStore).collect(Collectors.toList());
    }
}
