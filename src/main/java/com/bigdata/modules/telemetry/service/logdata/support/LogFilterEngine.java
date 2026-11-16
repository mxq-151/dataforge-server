package com.bigdata.modules.telemetry.service.logdata.support;

import com.bigdata.modules.telemetry.service.logdata.model.LogFilter;
import com.google.re2j.Pattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.bigdata.modules.telemetry.util.RegexGuard;

/**
 * 共享日志过滤引擎：本地磁盘与 Elasticsearch 使用同一套关键词语义。
 *
 * <p>默认字面量子串、大小写不敏感、多关键词 AND；排除词优先于包含词。正则走受限子集，
 * 与 ES 的 {@code regexp} 对齐。</p>
 */
public final class LogFilterEngine {

    private final List<String> includeLiterals;
    private final List<String> excludeLiterals;
    private final List<Pattern> includePatterns;
    private final List<Pattern> excludePatterns;
    private final LogFilter.MatchMode matchMode;
    private final boolean caseSensitive;

    private LogFilterEngine(List<String> includeLiterals, List<String> excludeLiterals,
                            List<Pattern> includePatterns, List<Pattern> excludePatterns,
                            LogFilter.MatchMode matchMode, boolean caseSensitive) {
        this.includeLiterals = includeLiterals;
        this.excludeLiterals = excludeLiterals;
        this.includePatterns = includePatterns;
        this.excludePatterns = excludePatterns;
        this.matchMode = matchMode;
        this.caseSensitive = caseSensitive;
    }

    public static LogFilterEngine compile(LogFilter filter) {
        LogFilter effective = filter == null ? LogFilter.empty() : filter;
        List<String> includes = new ArrayList<>();
        List<String> excludes = new ArrayList<>();
        List<Pattern> includePatterns = new ArrayList<>();
        List<Pattern> excludePatterns = new ArrayList<>();
        for (String keyword : effective.keywords()) {
            String term = normalizeKeyword(keyword);
            if (effective.regex()) {
                includePatterns.add(RegexGuard.compile(term, effective.caseSensitive()));
            } else {
                includes.add(effective.caseSensitive() ? term : term.toLowerCase(Locale.ROOT));
            }
        }
        for (String keyword : effective.excludeKeywords()) {
            String term = normalizeKeyword(keyword);
            if (effective.regex()) {
                excludePatterns.add(RegexGuard.compile(term, effective.caseSensitive()));
            } else {
                excludes.add(effective.caseSensitive() ? term : term.toLowerCase(Locale.ROOT));
            }
        }
        return new LogFilterEngine(includes, excludes, includePatterns, excludePatterns,
                effective.matchMode(), effective.caseSensitive());
    }

    public boolean matches(String line) {
        if (line == null) {
            return false;
        }
        String haystack = caseSensitive ? line : line.toLowerCase(Locale.ROOT);
        for (String term : excludeLiterals) {
            if (haystack.contains(term)) {
                return false;
            }
        }
        for (Pattern pattern : excludePatterns) {
            if (pattern.matcher(line).find()) {
                return false;
            }
        }
        if (includeLiterals.isEmpty() && includePatterns.isEmpty()) {
            return true;
        }
        if (matchMode == LogFilter.MatchMode.ANY) {
            for (String term : includeLiterals) {
                if (haystack.contains(term)) {
                    return true;
                }
            }
            for (Pattern pattern : includePatterns) {
                if (pattern.matcher(line).find()) {
                    return true;
                }
            }
            return false;
        }
        for (String term : includeLiterals) {
            if (!haystack.contains(term)) {
                return false;
            }
        }
        for (Pattern pattern : includePatterns) {
            if (!pattern.matcher(line).find()) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("关键词不能为空");
        }
        String term = keyword.trim();
        if (term.length() > 500) {
            throw new IllegalArgumentException("关键词超过 500 字符");
        }
        return term;
    }
}
