package com.bigdata.modules.quality.enums;

import java.util.Arrays;

/**
 * 规则模板类别。
 *
 * <p>内置模板即 7 类规则类型（template_code 与规则类型编码同名）；
 * 自定义模板仍需归属 7 类之一 —— 差异在 SQL 模板，不在类型，
 * 否则覆盖率统计会失去口径。</p>
 *
 * <p>与前端 {@code QualityTemplateKind} 取值一致。</p>
 */
public enum QualityTemplateKind {

    /** 平台预置，只读 */
    BUILTIN("builtin"),

    /** 用户维护的 SQL 模板 */
    CUSTOM("custom");

    private final String code;

    QualityTemplateKind(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static QualityTemplateKind fromCode(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        return Arrays.stream(values())
                .filter(k -> k.code.equalsIgnoreCase(trimmed))
                .findFirst()
                .orElse(null);
    }
}
