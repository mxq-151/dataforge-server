package com.bigdata.modules.telemetry.v1.spi;

import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.Requirement;

import java.util.List;

/**
 * 后端取数失败。
 *
 * <p>{@code code.retryable()} 决定是否值得重试；回退判定只认可用性类 code
 * （超时 / 上游 5xx / 429 / IO），权限、参数、映射、空结果都不回退。</p>
 */
public class BackendException extends RuntimeException {

    private final CapabilityCode code;
    private final List<Requirement> requirements;
    private final Long retryAfterMs;

    public BackendException(CapabilityCode code, String message) {
        this(code, message, List.of(), null);
    }

    public BackendException(CapabilityCode code, String message, List<Requirement> requirements) {
        this(code, message, requirements, null);
    }

    public BackendException(CapabilityCode code, String message, List<Requirement> requirements,
                            Long retryAfterMs) {
        super(message);
        this.code = code;
        this.requirements = requirements == null ? List.of() : List.copyOf(requirements);
        this.retryAfterMs = retryAfterMs;
    }

    public CapabilityCode getCode() {
        return code;
    }

    public List<Requirement> getRequirements() {
        return requirements;
    }

    public Long getRetryAfterMs() {
        return retryAfterMs;
    }

    public boolean retryable() {
        return code.retryable();
    }

    /** 只有可用性类故障允许回退到同等价组的备选后端。 */
    public boolean availabilityFailure() {
        return switch (code) {
            case UPSTREAM_TIMEOUT, UPSTREAM_ERROR, IO_ERROR, RATE_LIMITED, BACKEND_UNAVAILABLE -> true;
            default -> false;
        };
    }
}
