package com.bigdata.modules.integration.enums;

/**
 * 数据集成任务可提交的目标集群类型。
 *
 * <p>任务统一生成 Flink CDC 作业，只有 Flink Standalone / 远端 Flink 集群一种运行环境。
 * 历史数据里的 {@code seatunnel} 由迁移脚本 {@code V5__integration_flink_cdc_*} 转换为
 * {@code flink-standalone}，枚举不再保留该值。</p>
 */
public enum ClusterType {

    FLINK_STANDALONE("flink-standalone", "Flink Standalone");

    private final String code;
    private final String label;

    ClusterType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static ClusterType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ClusterType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return null;
    }
}
