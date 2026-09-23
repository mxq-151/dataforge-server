package com.bigdata.modules.quality.enums;

import java.util.Arrays;

/**
 * 规则级校验结果状态 —— 与前端 {@code QualityCheckStatus} 一致。
 *
 * <p>刻意把「校验不通过」与「校验失败」区分开：前者是数据有问题，
 * 后者是 SQL 没跑通（语法/权限/连接），两者的处置动作完全不同。</p>
 */
public enum QualityCheckStatus {

    /** 通过 */
    PASS("通过"),

    /** 橙色异常：超过警告阈值 */
    WARN("橙色异常"),

    /** 红色异常：超过错误阈值 */
    ERROR("红色异常"),

    /** 校验失败：SQL 未跑通 */
    FAILED("校验失败");

    private final String label;

    QualityCheckStatus(String label) {
        this.label = label;
    }

    public String getCode() {
        return name();
    }

    public String getLabel() {
        return label;
    }

    /** 是否属于「异常」（需要进 TOP 问题表统计） */
    public boolean isAbnormal() {
        return this == WARN || this == ERROR;
    }

    /** 是否计入「未通过」条数（异常 + 校验失败） */
    public boolean isNotPassed() {
        return this != PASS;
    }

    public static QualityCheckStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        return Arrays.stream(values())
                .filter(s -> s.name().equalsIgnoreCase(trimmed))
                .findFirst()
                .orElse(null);
    }
}
