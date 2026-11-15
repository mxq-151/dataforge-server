package com.bigdata.modules.telemetry.v1.runtime.model;

import java.util.List;
import java.util.Map;

/** 作业/集群配置参数；所有 values 已脱敏。 */
public record ConfigData(boolean available,
                         String reason,
                         String jobId,
                         long capturedAt,
                         Map<String, Object> sections,
                         List<RuntimeComponent> components,
                         List<String> warnings) {
}
