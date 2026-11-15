package com.bigdata.modules.telemetry.model;

import java.util.Locale;

/**
 * 取数身份：服务类型 → 服务组件 → 组件所在 host → 数据类型。
 *
 * <p>业务引用（applicationId/jobId/path 等）不参与身份判定，作为查询限定符单独传递。</p>
 */
public record TelemetryIdentity(String serviceType,
                                String componentId,
                                String hostId,
                                String dataTypeId) {

    public TelemetryIdentity {
        serviceType = normalize(serviceType);
        componentId = normalize(componentId);
        hostId = normalize(hostId);
        dataTypeId = normalize(dataTypeId);
    }

    /** 与 agentkit 对齐的四段标识。 */
    public String key() {
        return serviceType + "|" + componentId + "|" + hostId + "|" + dataTypeId;
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
