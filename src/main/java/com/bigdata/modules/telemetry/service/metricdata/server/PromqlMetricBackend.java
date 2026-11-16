package com.bigdata.modules.telemetry.service.metricdata.server;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.Requirement;
import com.bigdata.modules.telemetry.common.model.RequirementKind;
import com.bigdata.modules.telemetry.common.model.BackendType;
import com.bigdata.modules.telemetry.common.model.QueryMode;
import com.bigdata.modules.telemetry.common.model.BackendCapability;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.bigdata.modules.telemetry.service.metricdata.spi.MetricBackend;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricQueryResult;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricQuerySpec;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prometheus / VictoriaMetrics 共用的 PromQL 后端。
 *
 * <p>统一用 POST form 提交，避免长 selector 撞 URL 长度；只使用两者都支持的 PromQL 公共子集。</p>
 */
public final class PromqlMetricBackend implements MetricBackend {

    private final String backendId;
    private final PromqlDialect dialect;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public PromqlMetricBackend(String backendId, PromqlDialect dialect, ObjectMapper objectMapper) {
        this.backendId = backendId;
        this.dialect = dialect;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(dialect.timeout().toMillis(), 1000)))
                .build();
    }

    @Override
    public String backendId() {
        return backendId;
    }

    @Override
    public BackendType backendType() {
        return dialect.type();
    }

    @Override
    public BackendCapability evaluate(MetricQuerySpec spec) {
        if (!dialect.configured()) {
            return BackendCapability.unavailable(CapabilityCode.NOT_CONFIGURED,
                    dialect.configurationError(),
                    List.of(new Requirement(RequirementKind.CONFIG,
                            configKey(), "配置 PromQL 后端地址与认证后可用")));
        }
        if (spec.mode() == QueryMode.RANGE) {
            if (spec.start() == null || spec.end() == null || spec.step() == null
                    || spec.step().isZero() || spec.step().isNegative()) {
                return BackendCapability.unavailable(CapabilityCode.INVALID_ARGUMENT,
                        "range 查询必须提供 start/end/step，且 step > 0", List.of());
            }
            if (!spec.start().isBefore(spec.end())) {
                return BackendCapability.unavailable(CapabilityCode.INVALID_ARGUMENT,
                        "timeRange.start 必须早于 timeRange.end", List.of());
            }
        }
        return BackendCapability.available();
    }

    @Override
    public MetricQueryResult fetch(MetricQuerySpec spec) throws BackendException {
        String selector = PromqlSelectorBuilder.build(spec);
        Duration timeout = spec.timeout() != null ? spec.timeout() : dialect.timeout();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("query", selector);
        if (spec.mode() == QueryMode.RANGE) {
            long points = estimatePoints(spec);
            if (points > dialect.maxPointsPerSeries()) {
                throw new BackendException(CapabilityCode.QUERY_TOO_EXPENSIVE,
                        "预计 " + points + " 个点，超过单序列上限 " + dialect.maxPointsPerSeries()
                                + "，请增大 step");
            }
            form.put("start", String.valueOf(spec.start().getEpochSecond()));
            form.put("end", String.valueOf(spec.end().getEpochSecond()));
            form.put("step", formatStep(spec.step()));
        } else {
            form.put("time", String.valueOf(spec.time() == null
                    ? java.time.Instant.now().getEpochSecond() : spec.time().getEpochSecond()));
        }
        form.put("timeout", formatStep(timeout));
        form.putAll(dialect.extraParams());

        String apiPath = spec.mode() == QueryMode.RANGE ? "/query_range" : "/query";
        HttpResponse<String> response = post(apiPath, form, timeout);
        if (response.statusCode() >= 400) {
            throw PromqlErrorMapper.fromStatus(response.statusCode(), response.body(), null);
        }
        if (response.body().getBytes(StandardCharsets.UTF_8).length > dialect.maxResponseBytes()) {
            throw new BackendException(CapabilityCode.QUERY_TOO_EXPENSIVE,
                    "响应超过 " + dialect.maxResponseBytes() + " 字节上限，请收窄查询");
        }
        return PromqlResponseParser.parse(response.body(), objectMapper, dialect);
    }

    private HttpResponse<String> post(String apiPath, Map<String, String> form, Duration timeout) {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(),
                            StandardCharsets.UTF_8));
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(dialect.url(apiPath)))
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (dialect.authorizationHeader() != null && !dialect.authorizationHeader().isBlank()) {
            builder.header("Authorization", dialect.authorizationHeader());
        }
        try {
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new BackendException(CapabilityCode.UPSTREAM_TIMEOUT,
                    "查询超时 " + timeout.toMillis() + "ms");
        } catch (IOException e) {
            throw new BackendException(CapabilityCode.IO_ERROR,
                    "连接 PromQL 后端失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackendException(CapabilityCode.IO_ERROR, "PromQL 请求被中断");
        }
    }

    private static long estimatePoints(MetricQuerySpec spec) {
        long stepMillis = spec.step().toMillis();
        if (stepMillis <= 0) {
            return Long.MAX_VALUE;
        }
        return (spec.end().toEpochMilli() - spec.start().toEpochMilli()) / stepMillis + 1;
    }

    private static String formatStep(Duration duration) {
        long millis = duration.toMillis();
        if (millis % 1000 == 0) {
            return (millis / 1000) + "s";
        }
        return (millis / 1000.0) + "s";
    }

    private String configKey() {
        if (dialect.type() == BackendType.VICTORIAMETRICS) {
            return "telemetry.backends.victoriametrics[].base-url";
        }
        return "telemetry.backends.prometheus[].base-url";
    }
}
