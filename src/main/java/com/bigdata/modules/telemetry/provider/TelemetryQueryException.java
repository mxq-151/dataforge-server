package com.bigdata.modules.telemetry.provider;

import com.bigdata.modules.telemetry.model.CapabilityCode;

/** 取数阶段的失败；由查询服务转成 FAILED + retryable，而不是冒泡成 500。 */
public class TelemetryQueryException extends RuntimeException {

    private final CapabilityCode code;

    public TelemetryQueryException(CapabilityCode code, String message) {
        super(message);
        this.code = code;
    }

    public TelemetryQueryException(CapabilityCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public CapabilityCode getCode() {
        return code;
    }
}
