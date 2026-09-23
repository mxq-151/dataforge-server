package com.bigdata.modules.quality.enums;

import java.util.Arrays;

/**
 * 质检任务运行实例状态 —— 与前端 {@code QualityRunStatus} 一致。
 *
 * <p>语义：RUNNING 执行中；SUCCESS 全部规则跑通且无异常；
 * FAILED 存在「校验失败（SQL 未跑通）」或存在 ERROR 且处置方式为阻断下游。
 * 仅橙色 WARN 不改变任务状态（任务仍算成功），由异常计数体现。</p>
 */
public enum QualityRunStatus {

    RUNNING("运行中"),
    SUCCESS("成功"),
    FAILED("失败");

    private final String label;

    QualityRunStatus(String label) {
        this.label = label;
    }

    public String getCode() {
        return name();
    }

    public String getLabel() {
        return label;
    }

    public static QualityRunStatus fromCode(String code) {
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
