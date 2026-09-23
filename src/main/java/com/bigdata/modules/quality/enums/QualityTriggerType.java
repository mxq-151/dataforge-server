package com.bigdata.modules.quality.enums;

import java.util.Arrays;

/**
 * 质检任务触发方式 —— 与前端 {@code QualityTriggerType} 一致。
 */
public enum QualityTriggerType {

    /** 调度触发：质检任务作为 DolphinScheduler DAG 节点执行，带调度实例号 */
    SCHEDULE("调度触发"),

    /** 手动触发：界面点「执行」或调用 /quality/task/{id}/run */
    MANUAL("手动触发");

    private final String label;

    QualityTriggerType(String label) {
        this.label = label;
    }

    public String getCode() {
        return name();
    }

    public String getLabel() {
        return label;
    }

    public static QualityTriggerType fromCode(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        return Arrays.stream(values())
                .filter(t -> t.name().equalsIgnoreCase(trimmed))
                .findFirst()
                .orElse(null);
    }
}
