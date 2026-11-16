package com.bigdata.modules.telemetry.service.metadata.support;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置值里的内嵌凭据清洗：{@code sasl.jaas.config}、{@code password="..."} 这类赋值
 * 一律替换为 {@code ***}。
 *
 * <p>键名级别的脱敏由 {@link MetadataMasker} 负责（默认键规则 + provider 规则），
 * 这里只处理「值内部夹带凭据」的情况，两者叠加生效。</p>
 */
@Component
public class SensitiveDataMasker {

    private static final String MASK = "***";

    private static final Pattern INLINE_CREDENTIAL = Pattern.compile(
            "(?i)(user_[\\w.-]+|password|passwd|pwd|secret|token|access[._-]?key|credential"
                    + "|sasl[._-]?jaas[._-]?config)"
                    + "\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^;\\s,]+)");

    /** 清洗字符串里内嵌的凭据赋值，例如 {@code username="u" password="p"}。 */
    public String maskText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = INLINE_CREDENTIAL.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result,
                    Matcher.quoteReplacement(matcher.group(1) + "=" + MASK));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
