package com.bigdata.modules.asset.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 天分区表判定测试。
 *
 * <p>{@code partition_columns} 来自各引擎系统表，形态不统一（纯列名 / 多列 / JSON 数组 /
 * 带日期函数的表达式），判定必须都能识别出日期类分区，且不把普通列误判为分区。</p>
 */
@DisplayName("DailyPartitionDetector - 天分区表判定")
class DailyPartitionDetectorTest {

    @Test
    @DisplayName("常见日期类分区列名命中")
    void matchesCommonDateColumns() {
        assertEquals("dt", DailyPartitionDetector.datePartitionColumn("dt"));
        assertEquals("ds", DailyPartitionDetector.datePartitionColumn("ds"));
        assertEquals("stat_date", DailyPartitionDetector.datePartitionColumn("stat_date"));
        assertTrue(DailyPartitionDetector.isDailyPartitioned("day"));
        assertTrue(DailyPartitionDetector.isDailyPartitioned("pt"));
    }

    @Test
    @DisplayName("后缀暗示日期的分区列命中")
    void matchesDateSuffix() {
        assertEquals("order_date", DailyPartitionDetector.datePartitionColumn("order_date"));
        assertEquals("event_dt", DailyPartitionDetector.datePartitionColumn("event_dt"));
        assertEquals("stat_day", DailyPartitionDetector.datePartitionColumn("stat_day"));
    }

    @Test
    @DisplayName("多列只取日期类那一列")
    void picksDateColumnFromMultiple() {
        assertEquals("dt", DailyPartitionDetector.datePartitionColumn("dt,hour"));
        assertEquals("dt", DailyPartitionDetector.datePartitionColumn("region,dt,hour"));
    }

    @Test
    @DisplayName("JSON 数组形态可解析")
    void parsesJsonArray() {
        assertEquals("dt", DailyPartitionDetector.datePartitionColumn("[\"dt\"]"));
        assertFalse(DailyPartitionDetector.isDailyPartitioned("[\"hour\",\"region\"]"));
    }

    @Test
    @DisplayName("表达式取其中的列名判定")
    void handlesExpression() {
        assertEquals("dt", DailyPartitionDetector.datePartitionColumn("toYYYYMM(dt)"));
        assertEquals("dt", DailyPartitionDetector.datePartitionColumn("to_days(`dt`)"));
        // 表达式内是普通列，不视为天分区
        assertNull(DailyPartitionDetector.datePartitionColumn("to_days(created_at)"));
    }

    @Test
    @DisplayName("普通非日期列不误判")
    void rejectsNonDateColumn() {
        assertFalse(DailyPartitionDetector.isDailyPartitioned("created_at"));
        assertFalse(DailyPartitionDetector.isDailyPartitioned("hour"));
        assertFalse(DailyPartitionDetector.isDailyPartitioned("country"));
    }

    @Test
    @DisplayName("空值/空白不误判")
    void handlesBlank() {
        assertNull(DailyPartitionDetector.datePartitionColumn(null));
        assertNull(DailyPartitionDetector.datePartitionColumn(""));
        assertNull(DailyPartitionDetector.datePartitionColumn("   "));
    }
}
