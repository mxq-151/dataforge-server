package com.bigdata.modules.knowledge.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.List;

public enum DocType {

    BUSINESS_TERM("business_term", "业务术语"),
    PROCESS_DOC("process_doc", "流程文档"),
    DEPLOY_DOC("deploy_doc", "部署文档"),
    LINEAGE_DOC("lineage_doc", "血缘文档"),
    OTHER("other", "其他");

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
