package com.bigdata.modules.quality.enums;

import java.math.BigDecimal;
import java.util.Arrays;

/**
 * 比较方式，决定「实际值」如何与阈值比较。
 *
 * <p>与前端 {@code QualityCompareMode} 取值一致（EQ/NE/GT/GTE/LT/LTE）。</p>
 *
 * <p>注意：比较在本平台内做（阈值与实际值都已从校验 SQL 取回），
 * 所以这里必须能处理非数值的实际值 —— 无法解析为数值时一律判定为「不通过」，
 * 不能静默当作通过（fail-close）。</p>
 */
public enum QualityCompareMode {

    EQ("等于"),
    NE("不等于"),
    GT("大于"),
    GTE("大于等于"),
    LT("小于"),
    LTE("小于等于");

    private final String label;

    QualityCompareMode(String label) {
        this.label = label;
    }

    public String getCode() {
        return name();
    }

    public String getLabel() {
        return label;
    }

    public static QualityCompareMode fromCode(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        return Arrays.stream(values())
                .filter(m -> m.name().equalsIgnoreCase(trimmed))
                .findFirst()
                .orElse(null);
    }

    /**
     * 用本比较方式判断 actual 是否满足 threshold。
     *
     * @return 比较结果；任一侧无法解析为数值时返回 null，调用方须按「不通过」处理
     */
    public Boolean test(String actual, String threshold) {
        if (actual == null || threshold == null) {
            return null;
        }
        BigDecimal a;
        BigDecimal b;
        try {
            a = new BigDecimal(actual.trim());
            b = new BigDecimal(threshold.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        int cmp = a.compareTo(b);
        return switch (this) {
            case EQ -> cmp == 0;
            case NE -> cmp != 0;
            case GT -> cmp > 0;
            case GTE -> cmp >= 0;
            case LT -> cmp < 0;
            case LTE -> cmp <= 0;
        };
    }
}
