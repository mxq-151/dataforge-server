package com.bigdata.modules.telemetry.service.metricdata.server;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.fasterxml.jackson.databind.JsonNode;

/** Prometheus HTTP 状态与错误体 → 统一 CapabilityCode。 */
public final class PromqlErrorMapper {

    private PromqlErrorMapper() {
    }

    public static BackendException fromStatus(int status, String body, Long retryAfterMs) {
        CapabilityCode code = switch (status) {
            case 400 -> CapabilityCode.INVALID_ARGUMENT;
            case 401, 403 -> CapabilityCode.UPSTREAM_UNAUTHORIZED;
            case 422 -> CapabilityCode.QUERY_TOO_EXPENSIVE;
            case 429 -> CapabilityCode.RATE_LIMITED;
            default -> status >= 500 ? CapabilityCode.UPSTREAM_ERROR : CapabilityCode.PARSE_ERROR;
        };
        String message = body == null || body.isBlank()
                ? "PromQL 请求失败，HTTP " + status
                : truncate(body);
        return new BackendException(code, message, java.util.List.of(), retryAfterMs);
    }

    public static BackendException fromPayload(JsonNode root) {
        String errorType = root.path("errorType").asText("");
        String error = root.path("error").asText("PromQL 返回 error");
        CapabilityCode code = switch (errorType) {
            case "bad_data" -> CapabilityCode.INVALID_ARGUMENT;
            case "timeout" -> CapabilityCode.UPSTREAM_TIMEOUT;
            case "too_many_samples", "too_many_points" -> CapabilityCode.QUERY_TOO_EXPENSIVE;
            default -> CapabilityCode.UPSTREAM_ERROR;
        };
        return new BackendException(code, errorType.isBlank() ? error : errorType + ": " + error);
    }

    private static String truncate(String body) {
        String value = body.length() > 500 ? body.substring(0, 500) : body;
        return value.replace('\n', ' ');
    }
}
