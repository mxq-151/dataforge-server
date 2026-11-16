package com.bigdata.modules.telemetry.service.routing.model;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;

/** 选择阶段就判定不可用的候选：没有发起调用，原因必须回传。 */
public record BackendSkip(String backendId, String backendType, CapabilityCode code, String reason) {
}
