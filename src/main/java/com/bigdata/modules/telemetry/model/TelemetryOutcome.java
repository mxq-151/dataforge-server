package com.bigdata.modules.telemetry.model;

/** 取数结果语义：不可用与调用失败必须能被调用方区分。 */
public enum TelemetryOutcome {
    OK,
    UNAVAILABLE,
    NOT_IMPLEMENTED,
    FAILED
}
