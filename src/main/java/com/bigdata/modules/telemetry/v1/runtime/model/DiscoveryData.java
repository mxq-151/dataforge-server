package com.bigdata.modules.telemetry.v1.runtime.model;

import java.util.List;
import java.util.Map;

/** discovery 三类作用域的统一返回；未请求的集合为空数组。 */
public record DiscoveryData(String scope,
                            long capturedAt,
                            List<SourceView> sources,
                            List<JobView> jobs,
                            List<RuntimeComponent> components,
                            List<String> warnings) {

    /** 已配置的运行时源（不是后端实例的健康探测）。 */
    public record SourceView(String backendId,
                             String backendType,
                             String serviceType,
                             String mode,
                             boolean baseUrlConfigured,
                             List<String> supportedFamilies,
                             Map<String, String> unavailableFamilies) {
    }

    public record JobView(String jobId,
                          String name,
                          String state,
                          Long startTime,
                          Long endTime,
                          Long durationMs,
                          String trackingUrl,
                          String note) {
    }
}
