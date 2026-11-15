package com.bigdata.modules.telemetry.vo;

import java.util.List;

/** 能力树的 host 层：同一组件在某个 host 上可用的数据类型。 */
public record CapabilityHostView(String hostId,
                                 List<String> transports,
                                 List<CapabilityItem> dataTypes) {
}
