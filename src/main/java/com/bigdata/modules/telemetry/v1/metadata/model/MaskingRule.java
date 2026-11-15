package com.bigdata.modules.telemetry.v1.metadata.model;

/**
 * provider 声明的脱敏规则，与核心默认规则叠加。
 *
 * <p>只能让字段更敏感，不能关掉核心默认规则；{@code sectionPattern} / {@code keyPattern}
 * 均为正则，启动时编译，非法正则直接启动失败。</p>
 */
public record MaskingRule(String sectionPattern,
                          String keyPattern,
                          MaskingMode mode,
                          String replacement) {

    public MaskingRule {
        mode = mode == null ? MaskingMode.WHOLE_VALUE : mode;
        replacement = replacement == null || replacement.isBlank()
                ? ConfigEntryView.REDACTED : replacement;
    }
}
