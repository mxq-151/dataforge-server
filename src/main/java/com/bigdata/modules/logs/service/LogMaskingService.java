package com.bigdata.modules.logs.service;

import com.bigdata.modules.logs.config.LogAccessProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * server 出口脱敏。
 *
 * <p>只在组装对外响应时执行，适配器始终拿到原始内容 —— 换出口（HTTP / SSE / 智能体工具）
 * 时不会出现「某个出口忘了脱敏」的情况。</p>
 */
@Service
public class LogMaskingService {

    private static final Logger log = LoggerFactory.getLogger(LogMaskingService.class);
    private static final String MASK = "******";

    private final LogAccessProperties properties;
    private final List<Rule> rules = new ArrayList<>();

    public LogMaskingService(LogAccessProperties properties) {
        this.properties = properties;
        // Bearer 必须先于「authorization 键值对」规则，否则 "Authorization: Bearer xxx"
        // 会先被键值规则命中，只把 Bearer 这个词替换掉，留下后面的 token 明文
        rules.add(new Rule("(?i)(bearer\\s+)([A-Za-z0-9._~+/=-]{8,})", "$1" + MASK));
        rules.add(new Rule("(?i)((?:password|passwd|pwd|secret|token|access[_-]?key(?:[_-]?secret)?|authorization)\\s*[=:]\\s*)(?!bearer\\b)([^\\s,;&\"']+)", "$1" + MASK));
        rules.add(new Rule("(?i)(jdbc:[a-z0-9]+://[^:/\\s]+:)([^@\\s]+)(@)", "$1" + MASK + "$3"));
        rules.add(new Rule("(?i)([?&](?:password|passwd|pwd|token|secret|access_key|accesskey)=)([^&\\s]+)", "$1" + MASK));
        if (properties.getMasking().getExtraPatterns() != null) {
            for (String raw : properties.getMasking().getExtraPatterns()) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                int idx = raw.indexOf("=>");
                if (idx <= 0) {
                    log.warn("忽略非法的脱敏规则（缺少 => 分隔）: {}", raw);
                    continue;
                }
                try {
                    rules.add(new Rule(raw.substring(0, idx), raw.substring(idx + 2)));
                } catch (Exception e) {
                    log.warn("忽略非法的脱敏正则: {}", raw);
                }
            }
        }
    }

    public String mask(String text) {
        if (text == null || text.isEmpty() || !properties.getMasking().isEnabled()) {
            return text;
        }
        String result = text;
        for (Rule rule : rules) {
            result = rule.apply(result);
        }
        return result;
    }

    public List<String> mask(List<String> lines) {
        if (lines == null || lines.isEmpty() || !properties.getMasking().isEnabled()) {
            return lines;
        }
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            result.add(mask(line));
        }
        return result;
    }

    private static final class Rule {
        private final Pattern pattern;
        private final String replacement;

        private Rule(String regex, String replacement) {
            this.pattern = Pattern.compile(regex);
            this.replacement = replacement;
        }

        private String apply(String input) {
            Matcher matcher = pattern.matcher(input);
            return matcher.replaceAll(replacement);
        }
    }
}
