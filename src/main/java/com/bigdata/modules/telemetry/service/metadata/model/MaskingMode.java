package com.bigdata.modules.telemetry.service.metadata.model;

/** provider 脱敏规则的处理方式。 */
public enum MaskingMode {

    /** 命中即整值替换为规则里的 replacement（通常为 {@code ***}）。 */
    WHOLE_VALUE,

    /** 只清洗值里内嵌的凭据赋值（如 {@code password="..."}），保留其余文本。 */
    INLINE_CREDENTIAL
}
