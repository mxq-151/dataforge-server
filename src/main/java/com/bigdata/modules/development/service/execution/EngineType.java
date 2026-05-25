package com.bigdata.modules.development.service.execution;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.List;

public enum EngineType {

    SPARK("spark", "Apache Spark", Arrays.asList(CodeType.JAVA,CodeType.SCALA, CodeType.PYTHON, CodeType.SQL)),
    KYUUBI("kyuubi", "Apache Kyuubi", Arrays.asList(CodeType.SQL)),
    FLINK("flink", "Apache Flink", Arrays.asList(CodeType.JAVA, CodeType.SCALA, CodeType.PYTHON, CodeType.SQL)),
    DORIS("doris", "Apache Doris", Arrays.asList(CodeType.SQL)),
    HBASE("hbase", "Apache HBase", Arrays.asList(CodeType.SQL));

    @EnumValue
    @JsonValue
    private final String code;

    private final String description;

    private final List<CodeType> supportedCodeTypes;

    EngineType(String code, String description, List<CodeType> supportedCodeTypes) {
        this.code = code;
        this.description = description;
        this.supportedCodeTypes = supportedCodeTypes;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public List<CodeType> getSupportedCodeTypes() {
        return supportedCodeTypes;
    }

    public boolean supportsCodeType(CodeType codeType) {
        return supportedCodeTypes.contains(codeType);
    }

    public static EngineType fromCode(String code) {
        return Arrays.stream(values())
                .filter(t -> t.code.equalsIgnoreCase(code))
                .findFirst()
                .orElse(null);
    }
}
