package com.bigdata.modules.quality.sql;

import com.bigdata.modules.quality.enums.QualityCheckStatus;
import com.bigdata.modules.quality.enums.QualityCompareMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 质检判定矩阵：阈值优先级、fail-close 语义、百分比写法容忍。
 *
 * <p>判定器决定「这次校验到底算不算有问题」。最危险的错误是把「取不到基线 / 阈值写错」
 * 判成通过（假绿），因此这里重点覆盖 FAILED 的几条路径。</p>
 */
@DisplayName("Quality模块 - 规则判定矩阵")
class DqRuleEvaluatorTest {

    private static final DqSqlBuilder.MetricKind COUNT = DqSqlBuilder.MetricKind.ABNORMAL_ROW_COUNT;
    private static final DqSqlBuilder.MetricKind DEVIATION = DqSqlBuilder.MetricKind.DEVIATION_PERCENT;
    private static final DqSqlBuilder.MetricKind DIFF = DqSqlBuilder.MetricKind.DIFF_PERCENT;

    @Test
    @DisplayName("SQL 未返回任何行：判校验失败，而不是通过")
    void emptyValuesFailsClosed() {
        var result = DqRuleEvaluator.evaluate(COUNT, QualityCompareMode.LTE,
                "0", null, null, List.of(), null);

        assertEquals(QualityCheckStatus.FAILED, result.status());
        assertTrue(result.errorMsg().contains("未返回任何结果行"));
        assertNull(result.actualValue());
    }

    @Test
    @DisplayName("涨跌幅规则缺少历史基线：判校验失败（首期不判通过）")
    void missingBaselineFailsClosed() {
        var result = DqRuleEvaluator.evaluate(DEVIATION, QualityCompareMode.LTE,
                "5", null, null, List.of("100"), null);

        assertEquals(QualityCheckStatus.FAILED, result.status());
        assertTrue(result.errorMsg().contains("尚无历史基线"));
    }

    @Test
    @DisplayName("基线为 0、当期大于 0：涨跌幅封顶 100%")
    void zeroBaselineCapsAtHundredPercent() {
        var result = DqRuleEvaluator.evaluate(DEVIATION, QualityCompareMode.LTE,
                "50", null, null, List.of("20"), BigDecimal.ZERO);

        assertEquals("100.00%", result.actualValue());
        // 100% > 50% → 期望值不满足 → 红色
        assertEquals(QualityCheckStatus.ERROR, result.status());
    }

    @Test
    @DisplayName("阈值判断：异常条数 5 超过期望值 0（≤0 为通过条件）→ 红色异常")
    void abnormalCountAboveExpectedIsError() {
        var result = DqRuleEvaluator.evaluate(COUNT, QualityCompareMode.LTE,
                "0", null, null, List.of("5"), null);

        assertEquals(QualityCheckStatus.ERROR, result.status());
        assertEquals("5", result.actualValue());
        assertEquals(new BigDecimal("5"), result.abnormalCount());
    }

    @Test
    @DisplayName("阈值判断：异常条数 0 满足期望值 ≤0 → 通过")
    void abnormalCountAtExpectedPasses() {
        var result = DqRuleEvaluator.evaluate(COUNT, QualityCompareMode.LTE,
                "0", null, null, List.of("0"), null);

        assertEquals(QualityCheckStatus.PASS, result.status());
        assertNull(result.severity());
    }

    @Test
    @DisplayName("红橙同时命中：红线优先，判 ERROR")
    void errorThresholdWinsOverWarn() {
        var result = DqRuleEvaluator.evaluate(COUNT, QualityCompareMode.LTE,
                null, "2", "1", List.of("5"), null);

        assertEquals(QualityCheckStatus.ERROR, result.status());
    }

    @Test
    @DisplayName("仅橙线命中：判 WARN")
    void warnThresholdTriggersWarn() {
        var result = DqRuleEvaluator.evaluate(COUNT, QualityCompareMode.LTE,
                null, "2", "10", List.of("5"), null);

        assertEquals(QualityCheckStatus.WARN, result.status());
        assertEquals(QualityCheckStatus.WARN.getCode(), result.severity());
    }

    @Test
    @DisplayName("阈值带百分号（5%）可正常比较")
    void percentSuffixThresholdIsAccepted() {
        var result = DqRuleEvaluator.evaluate(COUNT, QualityCompareMode.LTE,
                null, null, "5%", List.of("7"), null);

        assertEquals(QualityCheckStatus.ERROR, result.status());
        assertTrue(result.threshold().contains("5%"));
    }

    @Test
    @DisplayName("阈值写法不是数值：判校验失败（配置错误不放过）")
    void nonNumericThresholdFailsClosed() {
        var result = DqRuleEvaluator.evaluate(COUNT, QualityCompareMode.LTE,
                null, null, "abc", List.of("7"), null);

        assertEquals(QualityCheckStatus.FAILED, result.status());
        assertTrue(result.errorMsg().contains("红色阈值不是数值"));
    }

    @Test
    @DisplayName("实际值不是数值：判校验失败")
    void nonNumericActualFailsClosed() {
        var result = DqRuleEvaluator.evaluate(COUNT, QualityCompareMode.GT,
                "0", null, null, List.of("N/A"), null);

        assertEquals(QualityCheckStatus.FAILED, result.status());
        assertTrue(result.errorMsg().contains("不是数值"));
    }

    @Test
    @DisplayName("跨表比对只返回一列：判校验失败")
    void diffPercentRequiresTwoColumns() {
        var result = DqRuleEvaluator.evaluate(DIFF, QualityCompareMode.LTE,
                "1", null, null, List.of("100"), null);

        assertEquals(QualityCheckStatus.FAILED, result.status());
        assertTrue(result.errorMsg().contains("两列"));
    }

    @Test
    @DisplayName("跨表比对：主表 105 / 对照 100 → 差异 5%")
    void diffPercentComputesRelativeDeviation() {
        var result = DqRuleEvaluator.evaluate(DIFF, QualityCompareMode.LTE,
                "5", null, null, List.of("105", "100"), null);

        assertTrue(result.actualValue().startsWith("5.00%"), result.actualValue());
        assertEquals(QualityCheckStatus.PASS, result.status(), "等于阈值应通过");
    }

    @Test
    @DisplayName("未设置任何阈值：直接通过，阈值展示为「未设置阈值」")
    void noThresholdPasses() {
        var result = DqRuleEvaluator.evaluate(COUNT, null, null, null, null, List.of("3"), null);

        assertEquals(QualityCheckStatus.PASS, result.status());
        assertEquals("未设置阈值", result.threshold());
    }

    @Test
    @DisplayName("比较方式为空时按「小于等于」处理")
    void nullCompareModeDefaultsToLte() {
        var result = DqRuleEvaluator.evaluate(COUNT, null, "0", null, null, List.of("0"), null);

        assertEquals(QualityCheckStatus.PASS, result.status());
        assertTrue(result.threshold().startsWith("≤"));
    }
}
