package com.bigdata.modules.telemetry.v1.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 采样形态：{@code step} 等步长序列，{@code raw} 离散事件时间点。
 *
 * <p>checkpoint 等事件序列是 {@code raw}，不能做等步长插值。</p>
 */
public enum Sampling {

    STEP("step"),
    RAW("raw");

    private final String wire;

    Sampling(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
