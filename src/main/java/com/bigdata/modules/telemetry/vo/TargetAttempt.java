package com.bigdata.modules.telemetry.vo;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.dto.MetadataTarget;

/**
 * 一次 provider 尝试的追溯记录：{@code target + providerId + outcome + code}。
 *
 * <p>{@code reason} 只能是失败原因的简短描述，禁止写入原始配置值、密码、token 等任何明文，
 * 避免从旁路泄露已经脱敏的配置。</p>
 */
public record TargetAttempt(MetadataTarget target,
                            String providerId,
                            String outcome,
                            CapabilityCode code,
                            long elapsedMs,
                            boolean retryable,
                            String reason) {

    public static final String OK = "ok";
    public static final String UNAVAILABLE = "unavailable";
    public static final String FAILED = "failed";
    public static final String SKIPPED = "skipped";

    public static TargetAttempt success(MetadataTarget target, String providerId, long elapsedMs) {
        return new TargetAttempt(target, providerId, OK, CapabilityCode.OK, elapsedMs, false, null);
    }

    public static TargetAttempt skipped(MetadataTarget target, String providerId, String reason) {
        return new TargetAttempt(target, providerId, SKIPPED, CapabilityCode.NOT_CONFIGURED,
                0, false, reason);
    }
}
