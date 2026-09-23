package com.bigdata.common.sql;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 脚本拆分工具：把整段脚本按顶层分号拆成独立语句，并保留每条语句在原文中的位置。
 * <p>
 * 拆分时跳过字符串字面量、双引号/反引号标识符、块注释与行注释中的分号，
 * 因此 {@code SELECT ';' FROM t; DELETE FROM t} 会正确拆成两条语句。
 * </p>
 * <p>
 * 与「按分号 split」的关键区别：本工具返回的 {@link Span} 带有原文偏移量，
 * 调用方可以在改写某条语句后，把其余部分（注释、空白、分隔符）原样拼回，
 * 避免丢失脚本中的注释与格式。
 * </p>
 */
public final class SqlScriptSplitter {

    private SqlScriptSplitter() {
    }

    /**
     * 一条语句在原文中的位置。
     * <p>
     * {@code start} 指向语句正文的第一个字符（跳过前置空白与注释），
     * {@code end} 指向正文后第一个空白字符（不含分号本身）。
     * </p>
     */
    public static final class Span {
        private final int start;
        private final int end;

        Span(int start, int end) {
            this.start = start;
            this.end = end;
        }

        public int getStart() {
            return start;
        }

        public int getEnd() {
            return end;
        }

        /** 取出该语句的正文（不含分号、不含首尾空白，含语句内部的注释）。 */
        public String text(String script) {
            return script.substring(start, end);
        }
    }

    /**
     * 将脚本拆分为语句位置列表，按出现顺序返回。
     * <p>纯注释/空白片段不会产生 Span。</p>
     */
    public static List<Span> split(String script) {
        List<Span> spans = new ArrayList<>();
        if (script == null || script.isEmpty()) {
            return spans;
        }
        int len = script.length();
        int stmtStart = -1;
        int i = 0;
        while (i < len) {
            char c = script.charAt(i);

            // 块注释 /* ... */
            if (c == '/' && i + 1 < len && script.charAt(i + 1) == '*') {
                i += 2;
                while (i < len) {
                    if (script.charAt(i) == '*' && i + 1 < len && script.charAt(i + 1) == '/') {
                        i += 2;
                        break;
                    }
                    i++;
                }
                continue;
            }
            // 行注释 -- 或 #
            if ((c == '-' && i + 1 < len && script.charAt(i + 1) == '-') || c == '#') {
                while (i < len && script.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            // 单引号字符串
            if (c == '\'') {
                i++;
                while (i < len) {
                    char cc = script.charAt(i);
                    if (cc == '\\' && i + 1 < len) {
                        i += 2;
                        continue;
                    }
                    if (cc == '\'') {
                        if (i + 1 < len && script.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                if (stmtStart < 0) {
                    stmtStart = i;
                }
                continue;
            }
            // 双引号 / 反引号 / 方括号 标识符
            if (c == '"' || c == '`' || c == '[') {
                char closer = c == '[' ? ']' : c;
                i++;
                while (i < len) {
                    char cc = script.charAt(i);
                    if (cc == closer) {
                        if (i + 1 < len && script.charAt(i + 1) == closer && closer != ']') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                if (stmtStart < 0) {
                    stmtStart = i;
                }
                continue;
            }
            // 空白
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            // 语句分隔符
            if (c == ';') {
                if (stmtStart >= 0) {
                    addSpan(spans, script, stmtStart, i);
                    stmtStart = -1;
                }
                i++;
                continue;
            }
            if (stmtStart < 0) {
                stmtStart = i;
            }
            i++;
        }
        if (stmtStart >= 0) {
            addSpan(spans, script, stmtStart, len);
        }
        return spans;
    }

    private static void addSpan(List<Span> spans, String script, int start, int endExclusive) {
        int end = endExclusive;
        while (end > start && Character.isWhitespace(script.charAt(end - 1))) {
            end--;
        }
        if (end > start) {
            spans.add(new Span(start, end));
        }
    }

    /**
     * 判断片段是否仅由注释与空白构成（没有可执行内容）。
     */
    public static boolean isCommentOrBlank(String sql) {
        if (sql == null) {
            return true;
        }
        String t = stripComments(sql);
        return t.trim().isEmpty();
    }

    /**
     * 去掉块注释与行注释（不处理字符串字面量内的注释符号，用于「是否为空」这类保守判断足够）。
     */
    public static String stripComments(String sql) {
        if (sql == null) {
            return null;
        }
        String t = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
        t = t.replaceAll("(?m)--.*$", " ");
        t = t.replaceAll("(?m)#.*$", " ");
        return t;
    }

    /**
     * 取语句的第一个关键字（大写），跳过前置空白与注释。
     * <p>例如 {@code "/* c *&#47; select 1"} → {@code "SELECT"}；
     * 无法识别时返回空字符串。</p>
     */
    public static String firstKeyword(String sql) {
        if (sql == null) {
            return "";
        }
        String t = stripComments(sql).trim();
        if (t.isEmpty()) {
            return "";
        }
        int i = 0;
        while (i < t.length() && (Character.isLetter(t.charAt(i)) || t.charAt(i) == '_')) {
            i++;
        }
        return t.substring(0, i).toUpperCase();
    }
}
