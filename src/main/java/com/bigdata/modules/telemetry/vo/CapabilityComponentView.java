package com.bigdata.modules.telemetry.vo;

import java.util.List;

/** 能力树的组件层。 */
public record CapabilityComponentView(String componentId, List<CapabilityHostView> hosts) {
}
