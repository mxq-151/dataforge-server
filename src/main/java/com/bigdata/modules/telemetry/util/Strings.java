package com.bigdata.modules.telemetry.util;

/**
 * 字符串小工具：只做与业务无关的判断与截断，避免每个后端各写一份同名 private 方法。
 */
public final class Strings {

    private Strings() {
    }

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 超长截断，null 原样返回；长度按字符计，不切代理对之外的语义。 */
    public static String truncate(String value, int maxLength) {
        if (value == null || maxLength < 0 || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /** 异常消息兜底：空值用 fallback，超长截到 300 字符。 */
    public static String safeMessage(String message, String fallback) {
        if (isBlank(message)) {
            return fallback;
        }
        return truncate(message, 300);
    }
}
