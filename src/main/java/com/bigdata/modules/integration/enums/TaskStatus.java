package com.bigdata.modules.integration.enums;

/**
 * 数据集成任务生命周期状态。
 *
 * <p>DRAFT(草稿) → PUBLISHED(已上线) → RUNNING(运行中) / FAILED(失败) → OFFLINE(已下线)。
 * 已下线任务可再次上线（带最新参数重新提交）。</p>
 */
public enum TaskStatus {

    DRAFT("DRAFT", "草稿"),
    PUBLISHED("PUBLISHED", "已上线"),
    OFFLINE("OFFLINE", "已下线"),
    RUNNING("RUNNING", "运行中"),
    FAILED("FAILED", "失败");

    private final String code;
    private final String label;

    TaskStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static TaskStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (TaskStatus status : values()) {
            if (status.code.equalsIgnoreCase(code)) {
                return status;
            }
        }
        return null;
    }
}
