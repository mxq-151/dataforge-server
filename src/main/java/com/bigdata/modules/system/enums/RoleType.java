package com.bigdata.modules.system.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.List;

public enum RoleType {

    SUPER_ADMIN("SUPER_ADMIN", "超级管理员"),
    OPS_ENGINEER("OPS_ENGINEER", "运维工程师"),
    DATA_DEV_ENGINEER("DATA_DEV_ENGINEER", "数据开发工程师"),
    BUSINESS_REQUESTER("BUSINESS_REQUESTER", "业务需求方"),
    DATA_ANALYST("DATA_ANALYST", "数据分析师");

    @EnumValue
    @JsonValue
    private final String code;

    private final String description;

    RoleType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static RoleType fromCode(String code) {
        return Arrays.stream(values())
                .filter(t -> t.code.equalsIgnoreCase(code))
                .findFirst()
                .orElse(null);
    }

    public static List<RoleType> allTypes() {
        return Arrays.asList(values());
    }
}
