package com.bigdata.modules.telemetry.util;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;

/**
 * 基于 RE2/J 的正则校验：本地磁盘与 Elasticsearch {@code regexp} 共用线性时间语法子集。
 *
 * <p>RE2 不支持回溯引用、lookaround、命名分组与内联标志，因此这些构造在编译期就被拒绝；
 * 嵌套量词（如 {@code (a+)+$}）虽然允许，但 RE2 保证线性时间，不会出现灾难性回溯。</p>
 */
public final class RegexGuard {

    private static final int MAX_LENGTH = 1000;

    private RegexGuard() {
    }

    public static void validate(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("正则关键词不能为空");
        }
        if (pattern.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("正则关键词超过 " + MAX_LENGTH + " 字符");
        }
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("正则语法非法（RE2 子集）: " + pattern, e);
        }
    }

    public static Pattern compile(String pattern, boolean caseSensitive) {
        validate(pattern);
        return Pattern.compile(pattern, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
    }
}
