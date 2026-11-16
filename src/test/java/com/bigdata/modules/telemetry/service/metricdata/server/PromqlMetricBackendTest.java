package com.bigdata.modules.telemetry.service.metricdata.server;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.BackendType;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDefinition;
import com.bigdata.modules.telemetry.common.model.QueryMode;
import com.bigdata.modules.telemetry.common.model.Target;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricQueryResult;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricQuerySpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromqlMetricBackendTest {

    private HttpServer server;
    private final AtomicReference<String> capturedForm = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/query_range", exchange -> {
            capturedForm.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            respond(exchange);
        });
        server.createContext("/api/v1/query", exchange -> {
            capturedForm.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            respond(exchange);
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("query_range：selector 注入标准标签与已知 ref，解析 matrix")
    void queryRangeBuildsSelectorAndParsesMatrix() throws Exception {
        responseBody.set("""
                {"status":"success","data":{"resultType":"matrix","result":[
                  {"metric":{"job_id":"j1"},"values":[[1763173200,"1.5"],[1763173215,"NaN"]]}]}}
                """);
        MetricQueryResult result = backend(1000).fetch(spec(Duration.ofSeconds(15)));

        Map<String, String> form = form();
        assertThat(form.get("query")).isEqualTo(
                "flink_checkpoint_duration_seconds{service_type=\"flink\","
                        + "component_id=\"flink.jobmanager\",job_id=\"j1\",vertex_id=\"v1\"}");
        assertThat(form.get("step")).isEqualTo("15s");
        assertThat(form.get("start")).isEqualTo(epoch("2026-11-15T01:00:00Z"));
        assertThat(form.get("end")).isEqualTo(epoch("2026-11-15T02:00:00Z"));
        assertThat(result.series()).hasSize(1);
        assertThat(result.series().get(0).labels()).containsEntry("job_id", "j1");
        assertThat(result.series().get(0).samples()).hasSize(2);
        assertThat(result.series().get(0).samples().get(0).value()).isEqualTo(1.5);
        assertThat(result.series().get(0).samples().get(1).value()).isNaN();
    }

    @Test
    @DisplayName("instant：解析 vector 单点")
    void instantParsesVector() {
        responseBody.set("""
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{"job_id":"j1"},"value":[1763173200,"42"]}]}}
                """);
        MetricQuerySpec spec = new MetricQuerySpec(target(), definition(), Map.of(), QueryMode.INSTANT,
                Instant.parse("2026-11-15T01:00:00Z"), null, null, null,
                Map.of("jobId", "j1"), Duration.ofSeconds(5));

        MetricQueryResult result = backend(1000).fetch(spec);

        assertThat(result.series()).singleElement()
                .satisfies(series -> assertThat(series.samples()).singleElement()
                        .satisfies(sample -> assertThat(sample.value()).isEqualTo(42.0)));
        assertThat(form().get("time")).isEqualTo(epoch("2026-11-15T01:00:00Z"));
    }

    @Test
    @DisplayName("HTTP 422 → QUERY_TOO_EXPENSIVE，可读原因保留")
    void mapsExpensiveQuery() {
        status.set(422);
        responseBody.set("{\"status\":\"error\",\"errorType\":\"bad_data\",\"error\":\"too many\"}");

        assertThatThrownBy(() -> backend(1000).fetch(spec(Duration.ofSeconds(15))))
                .isInstanceOf(BackendException.class)
                .satisfies(e -> assertThat(((BackendException) e).getCode())
                        .isEqualTo(CapabilityCode.QUERY_TOO_EXPENSIVE));
    }

    @Test
    @DisplayName("预计点数超过上限时本地直接拒绝，不发起 HTTP 请求")
    void rejectsTooManyPoints() {
        assertThatThrownBy(() -> backend(100).fetch(spec(Duration.ofSeconds(1))))
                .isInstanceOf(BackendException.class)
                .satisfies(e -> assertThat(((BackendException) e).getCode())
                        .isEqualTo(CapabilityCode.QUERY_TOO_EXPENSIVE));
        assertThat(capturedForm.get()).isNull();
    }

    @Test
    @DisplayName("未配置 base-url：能力判定返回 NOT_CONFIGURED")
    void unconfiguredBackend() {
        PromqlDialect dialect = new PromqlDialect(BackendType.PROMETHEUS, "", "/api/v1", null,
                Map.of(), Duration.ofSeconds(5), 100, 1000, 1_000_000,
                "未配置 telemetry.backends.prometheus[].base-url");

        assertThat(new PromqlMetricBackend("prometheus-prod", dialect, new ObjectMapper())
                .evaluate(spec(Duration.ofSeconds(15))).isAvailable()).isFalse();
    }

    // ------------------------------------------------------------------ 辅助

    private PromqlMetricBackend backend(int maxPoints) {
        PromqlDialect dialect = new PromqlDialect(BackendType.PROMETHEUS,
                "http://localhost:" + server.getAddress().getPort(), "/api/v1", null,
                Map.of(), Duration.ofSeconds(5), 100, maxPoints, 1_000_000, null);
        return new PromqlMetricBackend("prometheus-prod", dialect, new ObjectMapper());
    }

    private MetricQuerySpec spec(Duration step) {
        return new MetricQuerySpec(target(), definition(), Map.of("vertex_id", "v1"),
                QueryMode.RANGE, null, Instant.parse("2026-11-15T01:00:00Z"),
                Instant.parse("2026-11-15T02:00:00Z"), step, Map.of("jobId", "j1"),
                Duration.ofSeconds(5));
    }

    private Target target() {
        return new Target("flink", "flink.jobmanager", "cluster");
    }

    private MetricDefinition definition() {
        return new MetricDefinition("flink_checkpoint_duration_seconds", "gauge", "seconds",
                "job", List.of(), List.of("jobId"), Map.of(), List.of());
    }

    private String epoch(String iso) {
        return String.valueOf(Instant.parse(iso).getEpochSecond());
    }

    private Map<String, String> form() {        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : capturedForm.get().split("&")) {
            String[] parts = pair.split("=", 2);
            form.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return form;
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status.get(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
