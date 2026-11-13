package com.bigdata.modules.runtime.adapter;

import com.bigdata.modules.logs.adapter.HttpLogClient;
import com.bigdata.modules.logs.adapter.HttpTextResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 运行时 REST 取数的最小封装（复用日志模块的 {@link HttpLogClient}，便于单测注入假实现）。
 *
 * <p>只做 GET：Flink REST 与 Spark History Server / live UI REST 的指标、配置、列表接口都是 GET。
 * 连接失败统一转 {@link RuntimeAdapterException.Code#UNAVAILABLE}，由编排层降级。</p>
 */
@Component
public class RuntimeRestClient {

    private final HttpLogClient httpClient;
    private final ObjectMapper objectMapper;

    public RuntimeRestClient(HttpLogClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /** 取 JSON；404 抛 NOT_FOUND，401/403/5xx 抛 UNAVAILABLE，连接失败抛 UNAVAILABLE */
    public JsonNode requireJson(String baseUrl, String path, String token, long timeoutMs,
                                String notFoundMessage) {
        Response response = get(baseUrl, path, token, timeoutMs);
        if (response.status == 404) {
            throw new RuntimeAdapterException(RuntimeAdapterException.Code.NOT_FOUND, notFoundMessage);
        }
        if (response.status == 401 || response.status == 403) {
            throw new RuntimeAdapterException(RuntimeAdapterException.Code.UNAVAILABLE,
                    "上游拒绝访问（HTTP " + response.status + "），请检查认证配置");
        }
        if (!response.isSuccess()) {
            throw new RuntimeAdapterException(RuntimeAdapterException.Code.UNAVAILABLE,
                    "上游返回 HTTP " + response.status);
        }
        return response.node;
    }

    /** 可选端点：任何失败都返回 null，由调用方记为 warning */
    public JsonNode optionalJson(String baseUrl, String path, String token, long timeoutMs) {
        try {
            Response response = get(baseUrl, path, token, timeoutMs);
            return response.isSuccess() ? response.node : null;
        } catch (RuntimeAdapterException e) {
            return null;
        }
    }

    public Response get(String baseUrl, String path, String token, long timeoutMs) {
        String url = join(baseUrl, path);
        return getAbsolute(url, token, timeoutMs);
    }

    public Response getAbsolute(String url, String token, long timeoutMs) {
        Map<String, String> headers = new HashMap<>();
        if (token != null && !token.isBlank()) {
            headers.put("Authorization", "Bearer " + token);
        }
        HttpTextResponse response;
        try {
            response = httpClient.get(url, headers, Duration.ofMillis(Math.max(timeoutMs, 500L)));
        } catch (Exception e) {
            throw new RuntimeAdapterException(RuntimeAdapterException.Code.UNAVAILABLE,
                    "上游不可达: " + e.getMessage(), e);
        }
        JsonNode node = null;
        if (response.getStatus() >= 200 && response.getStatus() < 300 && response.getBody() != null
                && !response.getBody().isBlank()) {
            try {
                node = objectMapper.readTree(response.getBody());
            } catch (Exception e) {
                throw new RuntimeAdapterException(RuntimeAdapterException.Code.FAILED,
                        "上游返回内容不是合法 JSON: " + e.getMessage(), e);
            }
        }
        return new Response(response.getStatus(), node);
    }

    public static String join(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String suffix = path == null ? "" : path;
        if (!suffix.isEmpty() && !suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }
        return base + suffix;
    }

    public static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** 解析 host（用于 JM / TM / UI 的展示） */
    public static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    public static final class Response {
        private final int status;
        private final JsonNode node;

        Response(int status, JsonNode node) {
            this.status = status;
            this.node = node;
        }

        public int getStatus() {
            return status;
        }

        public JsonNode getNode() {
            return node;
        }

        public boolean isSuccess() {
            return status >= 200 && status < 300 && node != null;
        }
    }
}
