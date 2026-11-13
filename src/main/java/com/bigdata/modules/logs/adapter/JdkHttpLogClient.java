package com.bigdata.modules.logs.adapter;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 基于 JDK HttpClient 的实现：连接超时固定 2s，请求超时由调用方传入（受 20s 查询预算约束）。
 */
@Component
public class JdkHttpLogClient implements HttpLogClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public HttpTextResponse get(String url, Map<String, String> headers, Duration timeout) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "text/plain, application/json;q=0.9, */*;q=0.5")
                .GET();
        if (headers != null) {
            headers.forEach((key, value) -> {
                if (key != null && value != null) {
                    builder.header(key, value);
                }
            });
        }
        HttpResponse<String> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String contentType = response.headers().firstValue("content-type").orElse("");
        return new HttpTextResponse(response.statusCode(), response.body(), contentType);
    }
}
