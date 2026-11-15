package com.bigdata.modules.telemetry.v1.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 查询模式：范围查询 / 单点查询。
 *
 * <p>范围查询是硬约束：只有真正保存历史采样的后端才允许声明支持 {@code range}，
 * 引擎 REST 的当前快照不能被重复填充成时间序列。</p>
 */
public enum QueryMode {

    RANGE("range"),
    INSTANT("instant");

    private final String wire;

    QueryMode(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static QueryMode fromWire(String value) {
        if (value == null) {
            throw new IllegalArgumentException("缺少查询模式");
        }
        for (QueryMode mode : values()) {
            if (mode.wire.equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        throw new IllegalArgumentException("未知查询模式: " + value);
    }
}
