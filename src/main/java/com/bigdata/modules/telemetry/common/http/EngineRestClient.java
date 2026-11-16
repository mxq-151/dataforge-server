package com.bigdata.modules.telemetry.common.http;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Spark / Flink REST 共用的只读 JSON 客户端。
 *
 * <p>只做 GET；404 视为目标不存在（NOT_FOUND），不映射成空数据。响应体超过上限直接拒绝，
 * 避免把超大作业详情读进内存。</p>
 */
public final class EngineRestClient {

    private final String baseUrl;
    private final String authorizationHeader;
    private final Duration timeout;
    private final long maxResponseBytes;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public EngineRestClient(String baseUrl, String authorizationHeader, Duration timeout,
                            long maxResponseBytes, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.authorizationHeader = authorizationHeader;
        this.timeout = timeout;
        this.maxResponseBytes = maxResponseBytes;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(timeout.toMillis(), 1000)))
                .build();
    }

    public JsonNode getJson(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET();
        if (authorizationHeader != null && !authorizationHeader.isBlank()) {
            builder.header("Authorization", authorizationHeader);
        }
        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new BackendException(CapabilityCode.UPSTREAM_TIMEOUT,
                    "引擎 REST 请求超时 " + timeout.toMillis() + "ms: " + path);
        } catch (IOException e) {
            throw new BackendException(CapabilityCode.IO_ERROR,
                    "连接引擎 REST 失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackendException(CapabilityCode.IO_ERROR, "引擎 REST 请求被中断");
        }
        if (response.body().getBytes(StandardCharsets.UTF_8).length > maxResponseBytes) {
            throw new BackendException(CapabilityCode.QUERY_TOO_EXPENSIVE,
                    "引擎 REST 响应超过 " + maxResponseBytes + " 字节上限: " + path);
        }
        if (response.statusCode() >= 400) {
            throw error(response.statusCode(), response.body(), path);
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new BackendException(CapabilityCode.PARSE_ERROR,
                    "引擎 REST 响应不是合法 JSON: " + path);
        }
    }

    private BackendException error(int status, String body, String path) {
        CapabilityCode code = switch (status) {
            case 400 -> CapabilityCode.INVALID_ARGUMENT;
            case 401, 403 -> CapabilityCode.UPSTREAM_UNAUTHORIZED;
            case 404 -> CapabilityCode.NOT_FOUND;
            case 429 -> CapabilityCode.RATE_LIMITED;
            default -> status >= 500 ? CapabilityCode.UPSTREAM_ERROR : CapabilityCode.PARSE_ERROR;
        };
        String message = body == null || body.isBlank()
                ? "引擎 REST 失败，HTTP " + status + ": " + path
                : "引擎 REST 失败，HTTP " + status + ": " + path + " - " + truncate(body);
        return new BackendException(code, message);
    }

    private static String truncate(String body) {
        String value = body.length() > 300 ? body.substring(0, 300) : body;
        return value.replace('\n', ' ');
    }
}
