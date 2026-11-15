package com.bigdata.modules.telemetry.vo;

import java.util.List;

/** 能力树的服务层。 */
public record CapabilityServiceView(String serviceType, List<CapabilityComponentView> components) {
}
