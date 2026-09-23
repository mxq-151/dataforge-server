package com.bigdata.common.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlScriptSplitter} 的行为验证：拆语句时不能把字符串字面量、注释里的分号当分隔符，
 * 且必须保留每条语句在原文中的位置（供权限改写回拼）。
 */
class SqlScriptSplitterTest {

    @Test
    void splitsTwoStatements() {
        String script = "SELECT 1; DELETE FROM t";
        List<SqlScriptSplitter.Span> spans = SqlScriptSplitter.split(script);
        assertEquals(2, spans.size());
        assertEquals("SELECT 1", spans.get(0).text(script));
        assertEquals("DELETE FROM t", spans.get(1).text(script));
        // 偏移量可直接用于回拼，两个 span 之间的「;」被完整保留
        assertEquals("; ", script.substring(spans.get(0).getEnd(), spans.get(1).getStart()));
    }

    @Test
    void semicolonInsideStringLiteralIsNotSeparator() {
        String script = "SELECT ';' AS c FROM t; SELECT 2";
        List<SqlScriptSplitter.Span> spans = SqlScriptSplitter.split(script);
        assertEquals(2, spans.size());
        assertEquals("SELECT ';' AS c FROM t", spans.get(0).text(script));
        assertEquals("SELECT 2", spans.get(1).text(script));
    }

    @Test
    void semicolonInsideCommentIsNotSeparator() {
        String script = "SELECT 1 -- 注释里的 ; 不是分隔符\n; SELECT 2";
        List<SqlScriptSplitter.Span> spans = SqlScriptSplitter.split(script);
        assertEquals(2, spans.size());
        assertEquals("SELECT 2", spans.get(1).text(script));
    }

    @Test
    void leadingCommentsAreExcludedFromSpan() {
        String script = "-- 前置注释\n/* 块注释 */ SELECT 1";
        List<SqlScriptSplitter.Span> spans = SqlScriptSplitter.split(script);
        assertEquals(1, spans.size());
        assertEquals("SELECT 1", spans.get(0).text(script));
        // 前置注释留在 span 之前，回拼时可原样保留
        assertEquals("-- 前置注释\n/* 块注释 */ ", script.substring(0, spans.get(0).getStart()));
    }

    @Test
    void commentOnlyScriptProducesNoSpan() {
        assertTrue(SqlScriptSplitter.split("-- 只有注释\n/* x */").isEmpty());
        assertTrue(SqlScriptSplitter.split("   \n  ").isEmpty());
        assertTrue(SqlScriptSplitter.split(null).isEmpty());
    }

    @Test
    void detectsCommentOrBlank() {
        assertTrue(SqlScriptSplitter.isCommentOrBlank("-- x"));
        assertTrue(SqlScriptSplitter.isCommentOrBlank("  \n "));
        assertFalse(SqlScriptSplitter.isCommentOrBlank("SELECT 1"));
    }

    @Test
    void firstKeywordSkipsCommentsAndNormalisesCase() {
        assertEquals("SELECT", SqlScriptSplitter.firstKeyword("  select * from t"));
        assertEquals("CREATE", SqlScriptSplitter.firstKeyword("/* c */\n-- c\ncreate table t(a int)"));
        assertEquals("SET", SqlScriptSplitter.firstKeyword("SET hive.exec.dynamic.partition=true"));
        assertEquals("", SqlScriptSplitter.firstKeyword("-- 只有注释"));
    }
}
