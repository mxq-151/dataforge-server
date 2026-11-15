package com.bigdata.modules.telemetry.v1.support;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** step / timeout 解析：支持 ISO-8601（PT15S）与简化写法（15s、1m、1h）。 */
public final class DurationParser {

    private static final Pattern SIMPLE = Pattern.compile("^(\\d+)([smh])$");

    private DurationParser() {
    }

    public static Duration parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("时长不能为空");
        }
        String text = value.trim();
        try {
            return Duration.parse(text);
        } catch (Exception ignored) {
            // 继续尝试简化写法
        }
        Matcher matcher = SIMPLE.matcher(text);
        if (matcher.matches()) {
            long amount = Long.parseLong(matcher.group(1));
            return switch (matcher.group(2)) {
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                default -> Duration.ofHours(amount);
            };
        }
        throw new IllegalArgumentException("非法时长: " + value + "（示例：15s / PT15S）");
    }
}
