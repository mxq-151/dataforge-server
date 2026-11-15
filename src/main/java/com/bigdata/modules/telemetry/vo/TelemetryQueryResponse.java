package com.bigdata.modules.telemetry.vo;

import com.bigdata.modules.telemetry.model.TelemetryIdentity;
import com.bigdata.modules.telemetry.model.TelemetryOutcome;

import java.util.Map;

/**
 * 统一取数响应：{@code outcome} 区分 OK / UNAVAILABLE / NOT_IMPLEMENTED / FAILED，
 * {@code retryable} 明确是否值得重试；不可用与未实现时 {@code data} 恒为 null。
 */
public record TelemetryQueryResponse(TelemetryIdentity identity,
                                     TelemetryOutcome outcome,
                                     boolean retryable,
                                     String providerId,
                                     String collectedAt,
                                     Map<String, Object> data,
                                     QueryError error,
                                     QueryMeta meta) {
}
