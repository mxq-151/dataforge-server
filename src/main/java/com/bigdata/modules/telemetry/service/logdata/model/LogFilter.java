package com.bigdata.modules.telemetry.service.logdata.model;

import java.util.List;

/**
 * 日志关键词过滤语义，本地磁盘与 Elasticsearch 必须使用同一套规则。
 *
 * <p>默认：字面量子串、大小写不敏感、多关键词 AND、排除词优先。</p>
 */
public record LogFilter(List<String> keywords,
                        List<String> excludeKeywords,
                        MatchMode matchMode,
                        boolean regex,
                        boolean caseSensitive) {

    public enum MatchMode {
        ALL,
        ANY
    }

    public static LogFilter empty() {
        return new LogFilter(List.of(), List.of(), MatchMode.ALL, false, false);
    }

    public LogFilter {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        excludeKeywords = excludeKeywords == null ? List.of() : List.copyOf(excludeKeywords);
        matchMode = matchMode == null ? MatchMode.ALL : matchMode;
    }

    public boolean isEmpty() {
        return keywords.isEmpty() && excludeKeywords.isEmpty();
    }
}
