package com.bigdata.modules.development.service.execution.hbase;

import org.apache.hadoop.hbase.filter.CompareFilter;
import org.apache.hadoop.hbase.filter.SubstringComparator;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HBase rowkey 过滤条件提取。
 *
 * <p>这段逻辑决定「能不能把过滤下推到 rowkey」，推错了会多扫全表、
 * 推丢了会返回错误的数据。此前整类 0% 覆盖。</p>
 */
@DisplayName("Development模块 - HBase rowkey 过滤提取")
class HBaseRowkeyFilterExtractorTest {

    @Test
    @DisplayName("无 WHERE 条件：不提任何条件")
    void returnsEmptyWhenNoWhere() {
        assertTrue(HBaseRowkeyFilterExtractor.extract("SELECT * FROM t_hbase").isEmpty());
    }

    @Test
    @DisplayName("等值条件：EQ + 子串比较器")
    void extractsEquality() {
        List<HBaseRowkeyCondition> conditions = HBaseRowkeyFilterExtractor.extract(
                "SELECT * FROM t_hbase WHERE ROWKEY = 'rk001'");

        assertEquals(1, conditions.size());
        HBaseRowkeyCondition condition = conditions.get(0);
        assertEquals("EQ", condition.opType);
        assertEquals("rk001", condition.value);
        assertEquals(CompareFilter.CompareOp.EQUAL, condition.compareOp);
        assertTrue(condition.comparator instanceof SubstringComparator);
    }

    @Test
    @DisplayName("rowkey 写在右侧也能识别")
    void extractsWhenRowkeyOnRight() {
        List<HBaseRowkeyCondition> conditions = HBaseRowkeyFilterExtractor.extract(
                "SELECT * FROM t_hbase WHERE 'rk002' = ROWKEY");

        assertEquals(1, conditions.size());
        assertEquals("rk002", conditions.get(0).value);
    }

    @Test
    @DisplayName("大小比较：GT/GTE/LT/LTE 映射到对应 CompareOp")
    void mapsRangeComparisons() {
        assertEquals(CompareFilter.CompareOp.GREATER,
                HBaseRowkeyFilterExtractor.extract("SELECT * FROM t WHERE ROWKEY > 'a'").get(0).compareOp);
        assertEquals(CompareFilter.CompareOp.GREATER_OR_EQUAL,
                HBaseRowkeyFilterExtractor.extract("SELECT * FROM t WHERE ROWKEY >= 'a'").get(0).compareOp);
        assertEquals(CompareFilter.CompareOp.LESS,
                HBaseRowkeyFilterExtractor.extract("SELECT * FROM t WHERE ROWKEY < 'z'").get(0).compareOp);
        assertEquals(CompareFilter.CompareOp.LESS_OR_EQUAL,
                HBaseRowkeyFilterExtractor.extract("SELECT * FROM t WHERE ROWKEY <= 'z'").get(0).compareOp);
    }

    @Test
    @DisplayName("LIKE 'abc%'：按前缀处理（PREFIX）")
    void extractsPrefixLike() {
        List<HBaseRowkeyCondition> conditions = HBaseRowkeyFilterExtractor.extract(
                "SELECT * FROM t WHERE ROWKEY LIKE 'abc%'");

        assertEquals("PREFIX", conditions.get(0).opType);
        assertEquals("abc", conditions.get(0).value);
        assertNull(conditions.get(0).compareOp);
    }

    @Test
    @DisplayName("LIKE '%abc%'：退化为正则比较器")
    void extractsContainsLikeAsRegex() {
        List<HBaseRowkeyCondition> conditions = HBaseRowkeyFilterExtractor.extract(
                "SELECT * FROM t WHERE ROWKEY LIKE '%abc%'");

        assertEquals("LIKE_REGEX", conditions.get(0).opType);
        assertEquals("%abc%", conditions.get(0).value);
    }

    @Test
    @DisplayName("LIKE 中的正则元字符被转义")
    void escapesRegexMetacharactersInLike() {
        List<HBaseRowkeyCondition> conditions = HBaseRowkeyFilterExtractor.extract(
                "SELECT * FROM t WHERE ROWKEY LIKE 'a.b%c'");

        assertEquals("LIKE_REGEX", conditions.get(0).opType);
        String regex = new String(conditions.get(0).comparator.getValue());
        // 点号必须转义，否则会被当成「任意字符」
        assertTrue(regex.contains("\\."), regex);
    }

    @Test
    @DisplayName("BETWEEN：保留 start/stop 字节")
    void extractsBetween() {
        HBaseRowkeyCondition condition = HBaseRowkeyFilterExtractor.extract(
                "SELECT * FROM t WHERE ROWKEY BETWEEN 'a' AND 'c'").get(0);

        assertEquals("BETWEEN", condition.opType);
        assertArrayEquals(Bytes.toBytes("a"), condition.startRow);
        assertArrayEquals(Bytes.toBytes("c"), condition.stopRow);
        assertEquals("a..c", condition.value);
    }

    @Test
    @DisplayName("IN：列出所有候选 rowkey")
    void extractsInList() {
        HBaseRowkeyCondition condition = HBaseRowkeyFilterExtractor.extract(
                "SELECT * FROM t WHERE ROWKEY IN ('a','b')").get(0);

        assertEquals("IN", condition.opType);
        assertEquals("a,b", condition.value);
        assertEquals(2, condition.inValues.length);
    }

    @Test
    @DisplayName("AND/OR 组合：递归提取全部 rowkey 条件")
    void extractsAcrossLogicalOperators() {
        List<HBaseRowkeyCondition> conditions = HBaseRowkeyFilterExtractor.extract(
                "SELECT * FROM t WHERE ROWKEY > 'a' AND ROWKEY < 'z' OR ROWKEY = 'm'");

        assertEquals(3, conditions.size());
    }

    @Test
    @DisplayName("带括号的 AND/OR 组合同样必须完整提取")
    void extractsConditionsInsideParenthesis() {
        List<HBaseRowkeyCondition> conditions = HBaseRowkeyFilterExtractor.extract(
                "SELECT * FROM t WHERE ROWKEY > 'a' AND (ROWKEY < 'z' OR ROWKEY = 'm')");

        assertEquals(3, conditions.size());
    }

    @Test
    @DisplayName("非 rowkey 列的条件被忽略")
    void ignoresNonRowkeyColumns() {
        assertTrue(HBaseRowkeyFilterExtractor.extract(
                "SELECT * FROM t WHERE user_id = '123' AND name LIKE 'x%'").isEmpty());
    }

    @Test
    @DisplayName("非 SELECT 语句：不提条件")
    void ignoresNonSelectStatements() {
        assertTrue(HBaseRowkeyFilterExtractor.extract("DELETE FROM t WHERE ROWKEY = 'a'").isEmpty());
    }

    @Test
    @DisplayName("非法 SQL：吞掉异常并返回空列表，不影响主流程")
    void swallowsInvalidSql() {
        assertTrue(HBaseRowkeyFilterExtractor.extract("SELECT FROM WHERE ROWKEY ???").isEmpty());
    }
}
