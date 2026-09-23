package com.bigdata.modules.quality.sql;

import com.bigdata.modules.quality.enums.QualityCheckStatus;
import com.bigdata.modules.quality.enums.QualityCompareMode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 规则判定器 —— 把「校验 SQL 取回的数」折算成「实际值」，再按阈值判定通过 / 橙色 / 红色。
 *
 * <p>与 {@link DqSqlBuilder} 一样是纯函数、无 Spring 依赖，因此「规则类型 × 阈值组合」
 * 的判定矩阵可以被单测穷举。</p>
 *
 * <h3>判定顺序（为什么是这个顺序）</h3>
 * <ol>
 *   <li>先看 SQL 是否跑通 —— 跑不通就是 {@link QualityCheckStatus#FAILED}（校验失败），
 *       与「数据有问题」严格区分：前者要修配置或排障，后者要找业务方；</li>
 *   <li>再算实际值。行数类规则需要历史基线，基线取不到时同样判 FAILED
 *       而<b>不是</b>判通过 —— 否则「刚配好规则、还没积累基线」会被当成「数据没问题」，
 *       这是最危险的一种假绿；</li>
 *   <li>最后比阈值：红线失败即 ERROR，否则橙线失败即 WARN，都没有时才比期望值（硬阈值），
 *       全不失败则 PASS。</li>
 * </ol>
 *
 * <h3>阈值写法的小宽容</h3>
 * <p>界面上写涨跌幅很自然会带百分号（{@code 5%}），这里统一剥掉再比；
 * 但如果剥完仍不是数值，则按配置错误判 FAILED —— 不能因为阈值写法不对就静默放过。
 * </p>
 */
public final class DqRuleEvaluator {

    private DqRuleEvaluator() {
    }

    /** 基线缺失或为 0 时，「从无到有」的涨跌幅封顶值（%） */
    private static final BigDecimal FULL_DEVIATION = new BigDecimal("100");

    /** 实际值保留小数位 */
    private static final int SCALE = 2;

    /**
     * 判定结果。
     *
     * @param status     校验状态
     * @param severity   异常等级（仅 WARN / ERROR 有值）
     * @param actualValue 实际值展示串（带单位）
     * @param threshold  阈值展示串
     * @param errorMsg   校验失败原因（仅 FAILED 有值）
     * @param abnormalCount 实际值对应的异常条数；非计数类规则为 {@code null}
     * @param rawValues  校验 SQL 取回的原始值，便于落库排查
     */
    public record Evaluation(
            QualityCheckStatus status,
            String severity,
            String actualValue,
            String threshold,
            String errorMsg,
            BigDecimal abnormalCount,
            List<String> rawValues) {

        public boolean abnormal() {
            return status != null && status.isAbnormal();
        }
    }

    /**
     * 判定一次规则执行结果。
     *
     * @param metricKind    实际值语义
     * @param compareMode   比较方式（可空，默认「小于等于」）
     * @param expectedValue 期望值（硬阈值，可空）
     * @param warnThreshold 橙色阈值（可空）
     * @param errorThreshold 红色阈值（可空）
     * @param values        校验 SQL 取回的值（按列序）；空列表表示没有取到行
     * @param baseline      历史基线（行数类规则用；其余规则忽略）
     */
    public static Evaluation evaluate(DqSqlBuilder.MetricKind metricKind,
                                     QualityCompareMode compareMode,
                                     String expectedValue,
                                     String warnThreshold,
                                     String errorThreshold,
                                     List<String> values,
                                     BigDecimal baseline) {
        if (values == null || values.isEmpty()) {
            return failed("校验 SQL 未返回任何结果行", values);
        }
        QualityCompareMode mode = compareMode == null ? QualityCompareMode.LTE : compareMode;

        BigDecimal actual;
        String actualDisplay;
        BigDecimal abnormalCount = null;
        try {
            switch (metricKind) {
                case ABNORMAL_ROW_COUNT -> {
                    actual = number(values.get(0), "异常条数");
                    actualDisplay = actual.stripTrailingZeros().toPlainString();
                    abnormalCount = actual;
                }
                case DEVIATION_PERCENT -> {
                    if (baseline == null) {
                        return failed("尚无历史基线，无法计算涨跌幅：请先让该规则成功跑满至少一次，"
                                + "或手工确认首期行数是否正常（首期不判通过，避免假绿）", values);
                    }
                    BigDecimal current = number(values.get(0), "当期行数");
                    actual = deviation(current, baseline);
                    actualDisplay = actual.toPlainString() + "%";
                    if (current.compareTo(BigDecimal.ZERO) > 0) {
                        abnormalCount = current;
                    }
                }
                case DIFF_PERCENT -> {
                    if (values.size() < 2) {
                        return failed("跨表比对需要返回两列（主表值、对照表值），实际返回 " + values.size() + " 列", values);
                    }
                    BigDecimal main = number(values.get(0), "主表口径值");
                    BigDecimal ref = number(values.get(1), "对照表口径值");
                    actual = deviation(main, ref);
                    actualDisplay = actual.toPlainString() + "%（主表 " + plain(main) + " / 对照 " + plain(ref) + "）";
                }
                default -> {
                    return failed("未知的实际值语义: " + metricKind, values);
                }
            }
        } catch (IllegalArgumentException e) {
            return failed(e.getMessage(), values);
        }

        String thresholdDisplay = thresholdDisplay(mode, expectedValue, warnThreshold, errorThreshold);
        String actualText = actual.toPlainString();

        // 红线优先：命中即红色异常
        Halt red = halt(mode, actualText, errorThreshold);
        if (red == Halt.NOT_NUMERIC) {
            return failed("红色阈值不是数值: " + errorThreshold, values);
        }
        if (red == Halt.FAILED) {
            return new Evaluation(QualityCheckStatus.ERROR, QualityCheckStatus.ERROR.getCode(),
                    actualDisplay, thresholdDisplay, null, abnormalCount, values);
        }

        Halt orange = halt(mode, actualText, warnThreshold);
        if (orange == Halt.NOT_NUMERIC) {
            return failed("橙色阈值不是数值: " + warnThreshold, values);
        }
        if (orange == Halt.FAILED) {
            return new Evaluation(QualityCheckStatus.WARN, QualityCheckStatus.WARN.getCode(),
                    actualDisplay, thresholdDisplay, null, abnormalCount, values);
        }

        Halt hard = halt(mode, actualText, expectedValue);
        if (hard == Halt.NOT_NUMERIC) {
            return failed("期望值不是数值: " + expectedValue, values);
        }
        if (hard == Halt.FAILED) {
            return new Evaluation(QualityCheckStatus.ERROR, QualityCheckStatus.ERROR.getCode(),
                    actualDisplay, thresholdDisplay, null, abnormalCount, values);
        }

        return new Evaluation(QualityCheckStatus.PASS, null, actualDisplay, thresholdDisplay, null, abnormalCount, values);
    }

    /** 相对差异绝对值（%）：{@code |(current - base) / base| * 100} */
    static BigDecimal deviation(BigDecimal current, BigDecimal base) {
        if (base.compareTo(BigDecimal.ZERO) == 0) {
            // 基线为 0：数学上是无穷大，这里封顶为 100% 并在实际值里保留原始数，
            // 避免除零直接抛错导致整条规则判成「校验失败」（配置没问题，是数据形态特殊）
            return current.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO.setScale(SCALE)
                    : FULL_DEVIATION.setScale(SCALE);
        }
        return current.subtract(base)
                .divide(base, SCALE + 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .abs()
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    // ==================================================================
    // 阈值
    // ==================================================================

    private enum Halt {
        /** 满足阈值，继续往下比 */
        OK,
        /** 不满足阈值，按该级异常处置 */
        FAILED,
        /** 阈值写法不是数值：配置错误 */
        NOT_NUMERIC
    }

    /** 阈值为空表示不设该级门槛，直接放行 */
    private static Halt halt(QualityCompareMode mode, String actual, String threshold) {
        if (DqSqlBuilder.isBlank(threshold)) {
            return Halt.OK;
        }
        Boolean passed = mode.test(actual, stripPercent(threshold));
        if (passed == null) {
            return Halt.NOT_NUMERIC;
        }
        return passed ? Halt.OK : Halt.FAILED;
    }

    /** 阈值展示串：只列出真正配置了的门槛，并标出红/橙来源 */
    static String thresholdDisplay(QualityCompareMode mode, String expectedValue,
                                   String warnThreshold, String errorThreshold) {
        String symbol = symbol(mode);
        StringBuilder sb = new StringBuilder();
        if (!DqSqlBuilder.isBlank(errorThreshold)) {
            sb.append(symbol).append(' ').append(errorThreshold.trim()).append("（红）");
        }
        if (!DqSqlBuilder.isBlank(warnThreshold)) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(symbol).append(' ').append(warnThreshold.trim()).append("（橙）");
        }
        if (sb.length() == 0 && !DqSqlBuilder.isBlank(expectedValue)) {
            sb.append(symbol).append(' ').append(expectedValue.trim());
        }
        return sb.length() == 0 ? "未设置阈值" : sb.toString();
    }

    static String symbol(QualityCompareMode mode) {
        return switch (mode == null ? QualityCompareMode.LTE : mode) {
            case EQ -> "=";
            case NE -> "≠";
            case GT -> ">";
            case GTE -> "≥";
            case LT -> "<";
            case LTE -> "≤";
        };
    }

    /** 容忍「5%」「5 %」这类写法 */
    static String stripPercent(String threshold) {
        if (threshold == null) {
            return null;
        }
        return threshold.trim().replace("%", "").replace("％", "").trim();
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private static BigDecimal number(String raw, String label) {
        if (DqSqlBuilder.isBlank(raw)) {
            throw new IllegalArgumentException(label + "为空，无法比较");
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "不是数值: " + raw);
        }
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static Evaluation failed(String message, List<String> values) {
        return new Evaluation(QualityCheckStatus.FAILED, QualityCheckStatus.FAILED.getCode(),
                null, null, message, null, values == null ? List.of() : values);
    }

    /** 供服务层把「参数 + 规则配置」的默认值补齐后调用，避免各处重复拼默认值 */
    public static Map<String, String> defaultsFor(String ruleTypeCode) {
        DqBuiltinTemplates.Definition definition = DqBuiltinTemplates.of(ruleTypeCode);
        return definition == null ? Map.of() : definition.defaultParams();
    }
}
