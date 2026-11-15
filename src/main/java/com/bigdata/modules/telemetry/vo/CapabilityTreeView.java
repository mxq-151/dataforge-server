package com.bigdata.modules.telemetry.vo;

import java.time.Instant;
import java.util.List;

/** 能力判定响应：服务 → 组件 → host → 数据类型。 */
public record CapabilityTreeView(Instant generatedAt, List<CapabilityServiceView> services) {
}
