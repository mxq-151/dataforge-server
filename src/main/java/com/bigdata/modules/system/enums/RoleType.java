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
    DATA_ANALYST("DATA_ANALYST", "数据分析师"),
    DATA_ARCHITECT("DATA_ARCHITECT", "数据架构师");

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

    /**
     * Spring Security 权限串：{@code ROLE_ + 角色码}。
     *
     * <p>数据库/初始化脚本里的 role_code 不带前缀（{@code SUPER_ADMIN}），
     * JWT 与 {@code hasRole}/{@code @PreAuthorize} 用的是带前缀的形式。两者不能混用，
     * 统一从这里生成，避免再出现 "admin"/"ops"/"ROLE_SUPER_ADMIN" 这类同义写法。</p>
     */
    public String getAuthority() {
        return "ROLE_" + code;
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
