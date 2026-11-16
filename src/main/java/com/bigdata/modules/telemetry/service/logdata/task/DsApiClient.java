package com.bigdata.modules.telemetry.service.logdata.task;

import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.bigdata.modules.telemetry.common.model.CapabilityCode;
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
import java.util.Locale;

/**
 * DolphinScheduler REST 只读客户端：认证走 {@code token} 请求头（t_ds_access_token），
 * 不登录、不维护 session。
 *
 * <p>只提供 GET。响应为 HTML 或非 JSON 时直接判为 PARSE_ERROR，绝不当成日志正文返回。
 * token 只放进请求头，不写日志、不进异常消息。</p>
 */
final class DsApiClient {

    private final String baseUrl;
    private final String token;
    private final Duration timeout;
    private final long maxResponseBytes;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    DsApiClient(String baseUrl, String token, Duration timeout, long maxResponseBytes,
                ObjectMapper objectMapper) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        this.baseUrl = normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1) : normalized;
        this.token = token == null ? "" : token.trim();
        this.timeout = timeout;
        this.maxResponseBytes = maxResponseBytes;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** 发起 GET 并返回 {@code data} 节点；401/403 映射为 UPSTREAM_UNAUTHORIZED。 */
    JsonNode get(String path) {
        HttpResponse<String> response = send(getRequest(path).build(), path);
        if (response.statusCode() >= 400) {
            throw httpError(response.statusCode(), response.body(), path);
        }
        JsonNode root = parseJson(response, path);
        int code = root.path("code").asInt(0);
        if (code != 0) {
            throw dsError(code, root.path("msg").asText(""), path);
        }
        JsonNode data = root.path("data");
        return data.isMissingNode() ? root : data;
    }

    private HttpRequest.Builder getRequest(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("token", token)
                .GET();
    }

    private HttpResponse<String> send(HttpRequest request, String path) {
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long bytes = response.body() == null ? 0
                    : response.body().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > maxResponseBytes) {
                throw new BackendException(CapabilityCode.QUERY_TOO_EXPENSIVE,
                        "DolphinScheduler 响应超过 " + maxResponseBytes + " 字节上限: " + path);
            }
            return response;
        } catch (HttpTimeoutException e) {
            throw new BackendException(CapabilityCode.UPSTREAM_TIMEOUT,
                    "DolphinScheduler 请求超时 " + timeout.toMillis() + "ms: " + path);
        } catch (IOException e) {
            throw new BackendException(CapabilityCode.IO_ERROR,
                    "连接 DolphinScheduler 失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackendException(CapabilityCode.IO_ERROR, "DolphinScheduler 请求被中断");
        }
    }

    /** HTML 错误页（例如旧 /log/{id} 路径）不能被当作 JSON / 日志正文解析。 */
    private JsonNode parseJson(HttpResponse<String> response, String path) {
        String contentType = response.headers().firstValue("Content-Type").orElse("")
                .toLowerCase(Locale.ROOT);
        String body = response.body() == null ? "" : response.body().trim();
        if (contentType.contains("text/html") || body.startsWith("<")) {
            throw new BackendException(CapabilityCode.PARSE_ERROR,
                    "DolphinScheduler 返回 HTML 而非 JSON: " + path);
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new BackendException(CapabilityCode.PARSE_ERROR,
                    "DolphinScheduler 响应不是合法 JSON: " + path);
        }
    }

    private BackendException httpError(int status, String body, String path) {
        CapabilityCode code = switch (status) {
            case 400 -> CapabilityCode.INVALID_ARGUMENT;
            case 401, 403 -> CapabilityCode.UPSTREAM_UNAUTHORIZED;
            case 404 -> CapabilityCode.NOT_FOUND;
            case 429 -> CapabilityCode.RATE_LIMITED;
            default -> status >= 500 ? CapabilityCode.UPSTREAM_ERROR : CapabilityCode.PARSE_ERROR;
        };
        return new BackendException(code, "DolphinScheduler 请求失败，HTTP " + status
                + ": " + path + " - " + safeMessage(msgOf(body)));
    }

    /**
     * DS 业务错误码映射：10008 明确是任务实例不存在；10001 是参数非法；
     * 其余 100xx 保留原始 code，避免把结构化错误揉成一句话。
     */
    private BackendException dsError(int code, String msg, String path) {
        CapabilityCode mapped;
        if (code == 10008) {
            mapped = CapabilityCode.NOT_FOUND;
        } else if (code == 10001) {
            mapped = CapabilityCode.INVALID_ARGUMENT;
        } else {
            mapped = CapabilityCode.UPSTREAM_ERROR;
        }
        return new BackendException(mapped, "DolphinScheduler 返回 code=" + code
                + ": " + path + " - " + safeMessage(msg));
    }

    private String msgOf(String body) {
        if (body == null || body.isBlank() || body.trim().startsWith("<")) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            return root.path("msg").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private static String safeMessage(String value) {
        if (value == null || value.isBlank()) {
            return "无错误详情";
        }
        return value.length() > 200 ? value.substring(0, 200) : value;
    }
}
