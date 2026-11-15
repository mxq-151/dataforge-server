package com.bigdata.modules.telemetry.vo;

import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.Requirement;

import java.util.List;

/** 取数错误：类型化 code + 可读原因 + 补齐项。 */
public record QueryError(CapabilityCode code, String reason, List<Requirement> requirements) {
}
