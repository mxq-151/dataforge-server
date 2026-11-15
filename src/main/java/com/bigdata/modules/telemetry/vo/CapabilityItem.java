package com.bigdata.modules.telemetry.vo;

import com.bigdata.modules.telemetry.model.CapabilityState;
import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.Requirement;

import java.util.List;

/** 能力树叶子：单个数据类型的结构化可用性。 */
public record CapabilityItem(String dataTypeId,
                             CapabilityState state,
                             CapabilityCode code,
                             String reason,
                             boolean retryable,
                             String providerId,
                             List<String> transports,
                             List<Requirement> requirements) {
}
