package com.bigdata.modules.logs.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("单次上限裁剪（行数 / 字节）")
class BudgetLimiterTest {

    @Test
    @DisplayName("超过行数上限时按行截断，且不是行内截断")
    void limitByLines() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            lines.add("line-" + i);
        }

        BudgetLimiter.Result result = BudgetLimiter.limit(lines, 2000, 256 * 1024);

        assertEquals(2000, result.getLines().size());
        assertTrue(result.isTruncated());
        assertFalse(result.isLastLineTruncated());
        assertTrue(result.getByteSize() <= 256 * 1024);
    }

    @Test
    @DisplayName("单行超过字节上限时标记行内截断")
    void limitByBytesWithinLine() {
        String longLine = "a".repeat(300_000);

        BudgetLimiter.Result result = BudgetLimiter.limitText(longLine, 2000, 1024);

        assertEquals(1, result.getLines().size());
        assertTrue(result.isTruncated());
        assertTrue(result.isLastLineTruncated());
        assertTrue(result.getByteSize() <= 1024);
    }

    @Test
    @DisplayName("多字节字符截断不会产生半个字符")
    void truncateUtf8KeepsValidCharacters() {
        String value = "中".repeat(100);

        String truncated = BudgetLimiter.truncateUtf8(value, 7);

        assertEquals(2, truncated.length());
        assertEquals("中中", truncated);
    }
}
