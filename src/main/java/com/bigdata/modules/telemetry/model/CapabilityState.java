package com.bigdata.modules.telemetry.model;

/** 能力状态。UNAVAILABLE / NOT_IMPLEMENTED 都不可重试；FAILED 类错误才可重试。 */
public enum CapabilityState {
    AVAILABLE,
    UNAVAILABLE,
    NOT_IMPLEMENTED,
    DEGRADED,
    UNKNOWN
}
