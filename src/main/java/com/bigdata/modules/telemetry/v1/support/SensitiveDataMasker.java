package com.bigdata.modules.telemetry.v1.support;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v1 内部脱敏：配置参数里的凭据任何情况下不回显明文。
 *
 * <p>两层处理：键名命中敏感词的值整体替换为 {@code ***}；非敏感键的字符串值再做一次
 * 内嵌凭据清洗（如 {@code sasl.jaas.config} 里的 {@code password="..."}）。
 * 与日志模块的 {@code logs.*} 配置解耦，不依赖 runtime 的脱敏模型。</p>
 *
 * <p>运行时 config 与 metadata（服务版本与配置）共用这一份实现，避免两套规则漂移。</p>
 */
@Component
public class SensitiveDataMasker {

    private static final String MASK = "***";

    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(password|passwd|pwd|secret|token|access[._-]?key|credential|authorization"
                    + "|sasl[._-]?jaas|jaas[._-]?config|private[._-]?key).*");

    private static final Pattern INLINE_CREDENTIAL = Pattern.compile(
            "(?i)(user_[\\w.-]+|password|passwd|pwd|secret|token|access[._-]?key|credential"
                    + "|sasl[._-]?jaas[._-]?config)"
                    + "\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^;\\s,]+)");

    public boolean isSensitiveKey(String key) {
        return key != null && SENSITIVE_KEY.matcher(key).matches();
    }

    public String maskValue(String key, String value) {
        if (value == null) {
            return null;
        }
        if (isSensitiveKey(key)) {
            return MASK;
        }
        return maskText(value);
    }

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

    public Map<String, String> mask(Map<String, String> values) {
        Map<String, String> result = new LinkedHashMap<>();
        if (values == null) {
            return result;
        }
        values.forEach((key, value) -> result.put(key, maskValue(key, value)));
        return result;
    }

    /** 递归清洗 sections：Map / List / String 都处理，其他类型原样返回。 */
    @SuppressWarnings("unchecked")
    public Object maskObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                result.put(name, isSensitiveKey(name) ? MASK : maskObject(item));
            });
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                result.add(maskObject(item));
            }
            return result;
        }
        if (value instanceof String text) {
            return maskText(text);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> maskSections(Map<String, Object> sections) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (sections == null) {
            return result;
        }
        sections.forEach((key, value) ->
                result.put(key, isSensitiveKey(key) ? MASK : maskObject(value)));
        return result;
    }
}
