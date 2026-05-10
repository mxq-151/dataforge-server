package com.bigdata.modules.knowledge.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.List;

public enum DocType {

    BUSINESS_KNOWLEDGE("business_knowledge", "业务知识"),
    CLUSTER_OPS("cluster_ops", "集群运维"),
    DEV_STANDARD("dev_standard", "开发规范");

    @EnumValue
    @JsonValue
    private final String code;

    private final String description;

    DocType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static DocType fromCode(String code) {
        return Arrays.stream(values())
                .filter(t -> t.code.equalsIgnoreCase(code))
                .findFirst()
                .orElse(null);
    }

    public static List<DocType> allTypes() {
        return Arrays.asList(values());
    }
}
