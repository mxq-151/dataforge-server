package com.bigdata.modules.telemetry.service.logdata.server;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.exception.BackendException;
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
 * 极简 Elasticsearch REST 客户端：只覆盖 {@code _search} / {@code _pit} / {@code _mapping}。
 *
 * <p>不引官方客户端，避免 httpasyncclient/Netty 依赖与现有 HBase 排除规则冲突。</p>
 */
final class EsHttpClient {

    private final String baseUrl;
    private final String authorizationHeader;
    private final Duration timeout;
    private final long maxResponseBytes;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    EsHttpClient(String baseUrl, String authorizationHeader, Duration timeout,
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

    ObjectMapper objectMapper() {
        return objectMapper;
    }

    Response get(String path) {
        return send("GET", path, null);
    }

    Response post(String path, String body) {
        return send("POST", path, body == null ? "" : body);
    }

    private Response send(String method, String path, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "application/json");
        if (authorizationHeader != null && !authorizationHeader.isBlank()) {
            builder.header("Authorization", authorizationHeader);
        }
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        try {
            HttpResponse<String> response =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.body().getBytes(StandardCharsets.UTF_8).length > maxResponseBytes) {
                throw new BackendException(CapabilityCode.QUERY_TOO_EXPENSIVE,
                        "Elasticsearch 响应超过 " + maxResponseBytes + " 字节上限");
            }
            return new Response(response.statusCode(), response.body());
        } catch (HttpTimeoutException e) {
            throw new BackendException(CapabilityCode.UPSTREAM_TIMEOUT,
                    "Elasticsearch 请求超时 " + timeout.toMillis() + "ms");
        } catch (IOException e) {
            throw new BackendException(CapabilityCode.IO_ERROR,
                    "连接 Elasticsearch 失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackendException(CapabilityCode.IO_ERROR, "Elasticsearch 请求被中断");
        }
    }

    record Response(int status, String body) {
    }
}
