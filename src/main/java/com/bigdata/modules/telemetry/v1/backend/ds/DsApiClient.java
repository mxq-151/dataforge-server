package com.bigdata.modules.telemetry.v1.backend.ds;

import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.v1.spi.BackendException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * DolphinScheduler REST 只读客户端：负责登录、session 缓存、Cookie 与错误映射。
 *
 * <p>只提供 GET；登录走表单 POST。响应为 HTML 或非 JSON 时直接判为 PARSE_ERROR，
 * 绝不当成日志正文返回。凭据只用于登录表单，不写日志、不进异常消息。</p>
 */
final class DsApiClient {

    private static final String LOGIN_PATH = "/login";

    private final String baseUrl;
    private final String username;
    private final String password;
    private final Duration timeout;
    private final long maxResponseBytes;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private volatile String sessionId;

    DsApiClient(String baseUrl, String username, String password, Duration timeout,
                long maxResponseBytes, ObjectMapper objectMapper) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        this.baseUrl = normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1) : normalized;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.timeout = timeout;
        this.maxResponseBytes = maxResponseBytes;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** 发起 GET 并返回 {@code data} 节点；session 失效时重新登录后重试一次。 */
    JsonNode get(String path) {
        String session = session();
        HttpResponse<String> response = send(getRequest(path).header("Cookie",
                "sessionId=" + session).build(), path);
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            synchronized (this) {
                if (session.equals(sessionId)) {
                    sessionId = null;
                }
            }
            response = send(getRequest(path).header("Cookie", "sessionId=" + session())
                    .build(), path);
        }
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

    synchronized String session() {
        if (sessionId != null && !sessionId.isBlank()) {
            return sessionId;
        }
        login();
        return sessionId;
    }

    private void login() {
        String form = "userName=" + encode(username) + "&userPassword=" + encode(password);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + LOGIN_PATH))
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request, LOGIN_PATH);
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new BackendException(CapabilityCode.UPSTREAM_UNAUTHORIZED,
                    "DolphinScheduler 登录被拒绝，HTTP " + response.statusCode());
        }
        if (response.statusCode() >= 400) {
            throw httpError(response.statusCode(), response.body(), LOGIN_PATH);
        }
        JsonNode root = parseJson(response, LOGIN_PATH);
        int code = root.path("code").asInt(0);
        if (code != 0) {
            throw new BackendException(CapabilityCode.UPSTREAM_UNAUTHORIZED,
                    "DolphinScheduler 登录失败（code=" + code + "）: "
                            + safeMessage(root.path("msg").asText("")));
        }
        String sid = root.path("data").path("sessionId").asText("");
        if (sid.isBlank()) {
            sid = cookieSession(response);
        }
        if (sid.isBlank()) {
            throw new BackendException(CapabilityCode.PARSE_ERROR,
                    "DolphinScheduler 登录响应缺少 sessionId");
        }
        sessionId = sid;
    }

    private HttpRequest.Builder getRequest(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Accept", "application/json")
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

    private static String cookieSession(HttpResponse<String> response) {
        for (String header : response.headers().allValues("Set-Cookie")) {
            for (String part : header.split(";")) {
                String value = part.trim();
                if (value.startsWith("sessionId=")) {
                    return value.substring("sessionId=".length());
                }
            }
        }
        return "";
    }

    private static String safeMessage(String value) {
        if (value == null || value.isBlank()) {
            return "无错误详情";
        }
        return value.length() > 200 ? value.substring(0, 200) : value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
