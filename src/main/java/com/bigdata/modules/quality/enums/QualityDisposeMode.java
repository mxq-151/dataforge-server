package com.bigdata.modules.quality.enums;

import java.util.Arrays;

/**
 * 处置方式 —— 与智能体提示词中 S2 的「处置方式」同义，取值即提示词里的「阻断下游 / 告警」。
 *
 * <p>落库为 dq_rule.dispose_mode / dq_task.dispose_policy。
 * 阻断下游由质检任务作为 DAG 节点实现：校验不通过即非零退出，下游自然不执行，
 * 平台不做「反向改写调度状态」这种动作。</p>
 *
 * <p>与前端 {@code QualityDisposeMode} 取值一致。</p>
 */
public enum QualityDisposeMode {

    /** 阻断下游：校验不通过时让任务本身失败 */
    BLOCK("阻断下游"),

    /** 仅告警 */
    ALERT("告警");

    private final String label;

    QualityDisposeMode(String label) {
        this.label = label;
    }

    public String getCode() {
        return name();
    }

    public String getLabel() {
        return label;
    }

    public static QualityDisposeMode fromCode(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        return Arrays.stream(values())
                .filter(m -> m.name().equalsIgnoreCase(trimmed))
                .findFirst()
                .orElse(null);
    }
}
