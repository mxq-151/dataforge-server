package com.bigdata.modules.telemetry.model;

import java.util.List;

/**
 * 某个四元组 + 数据类型的可用性判定结果。
 */
public record Capability(String dataTypeId,
                         CapabilityState state,
                         CapabilityCode code,
                         String reason,
                         boolean retryable,
                         String providerId,
                         List<String> transports,
                         List<Requirement> requirements) {

    public static Capability available(String dataTypeId, String providerId, List<String> transports) {
        return new Capability(dataTypeId, CapabilityState.AVAILABLE, CapabilityCode.OK,
                "可用", CapabilityCode.OK.retryable(), providerId, transports, List.of());
    }

    public static Capability unavailable(String dataTypeId,
                                         CapabilityCode code,
                                         String reason,
                                         String providerId,
                                         List<String> transports,
                                         List<Requirement> requirements) {
        return new Capability(dataTypeId, CapabilityState.UNAVAILABLE, code, reason,
                code.retryable(), providerId, transports, requirements);
    }

    public static Capability notImplemented(String dataTypeId,
                                            String reason,
                                            List<Requirement> requirements) {
        return new Capability(dataTypeId, CapabilityState.NOT_IMPLEMENTED,
                CapabilityCode.DATA_TYPE_NOT_IMPLEMENTED, reason, false, null, List.of(), requirements);
    }
}
