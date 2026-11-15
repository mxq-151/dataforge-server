package com.bigdata.modules.telemetry.v1.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Optional;

/**
 * 存储后端类型。类型决定该后端能承载哪一类数据，不决定具体实例。
 *
 * <p>实例 id 由配置给出（如 {@code prometheus-prod}），同一类型可以有多个实例。</p>
 */
public enum BackendType {

    PROMETHEUS("prometheus", Category.METRIC),
    VICTORIAMETRICS("victoriametrics", Category.METRIC),
    FLINK_REST("flink-rest", Category.METRIC),
    SPARK_REST("spark-rest", Category.METRIC),
    LOCAL_DISK("local-disk", Category.LOG),
    ELASTICSEARCH("elasticsearch", Category.LOG),
    /** YARN 聚合日志：只承载任务日志（log.task），不用于服务自身日志。 */
    YARN("yarn", Category.LOG);

    /** 数据类别：状态数据 / 日志数据。 */
    public enum Category {
        METRIC,
        LOG
    }

    private final String wire;
    private final Category category;

    BackendType(String wire, Category category) {
        this.wire = wire;
        this.category = category;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    public Category category() {
        return category;
    }

    @JsonCreator
    public static BackendType fromWire(String value) {
        return find(value).orElseThrow(() -> new IllegalArgumentException("未知后端类型: " + value));
    }

    public static Optional<BackendType> find(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(type -> type.wire.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
