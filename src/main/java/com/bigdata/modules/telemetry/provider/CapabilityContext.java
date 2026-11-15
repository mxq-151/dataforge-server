package com.bigdata.modules.telemetry.provider;

/** 能力判定入参：一个四元组。 */
public record CapabilityContext(String serviceType,
                                String componentId,
                                String hostId,
                                String dataTypeId) {
}
