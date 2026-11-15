package com.bigdata.modules.telemetry.v1.model;

/** 非致命告警：部分结果、分片失败、回退等，必须随响应返回而不是静默。 */
public record BackendWarning(String code, String message) {

    public static BackendWarning of(String code, String message) {
        return new BackendWarning(code, message);
    }
}
