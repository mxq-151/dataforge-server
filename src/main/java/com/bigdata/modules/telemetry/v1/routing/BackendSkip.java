package com.bigdata.modules.telemetry.v1.routing;

import com.bigdata.modules.telemetry.model.CapabilityCode;

/** 选择阶段就判定不可用的候选：没有发起调用，原因必须回传。 */
public record BackendSkip(String backendId, String backendType, CapabilityCode code, String reason) {
}
