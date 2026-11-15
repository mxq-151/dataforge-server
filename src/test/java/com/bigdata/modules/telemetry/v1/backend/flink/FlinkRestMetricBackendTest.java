package com.bigdata.modules.telemetry.v1.backend.flink;

import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties.FlinkRestConfig;
import com.bigdata.modules.telemetry.v1.model.MetricDefinition;
import com.bigdata.modules.telemetry.v1.model.QueryMode;
import com.bigdata.modules.telemetry.v1.model.Target;
import com.bigdata.modules.telemetry.v1.model.TimeRange;
import com.bigdata.modules.telemetry.v1.spi.BackendCapability;
import com.bigdata.modules.telemetry.v1.spi.MetricQueryResult;
import com.bigdata.modules.telemetry.v1.spi.MetricQuerySpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FlinkRestMetricBackendTest {

    @Test
    @DisplayName("checkpoint range：按事件时间返回原始采样，不插值")
    void checkpointRange() throws IOException {
        try (FakeFlink flink = new FakeFlink()) {
            FlinkRestMetricBackend backend = backend(flink);
            MetricQueryResult result = backend.fetch(spec("flink_checkpoint_duration_seconds",
                    QueryMode.RANGE, range("2026-11-15T01:00:00Z", "2026-11-15T01:01:00Z")));

            assertThat(result.series()).singleElement().satisfies(series -> {
                assertThat(series.labels()).containsEntry("job_id", "j1");
                assertThat(series.samples()).hasSize(2);
                assertThat(series.samples().get(0).value()).isEqualTo(1.2);
                assertThat(series.samples().get(1).value()).isEqualTo(0.8);
            });
        }
    }

    @Test
    @DisplayName("checkpoint instant：只返回最后一个累计值")
    void checkpointInstant() throws IOException {
        try (FakeFlink flink = new FakeFlink()) {
            MetricQueryResult result = backend(flink).fetch(
                    spec("flink_checkpoint_completed_total", QueryMode.INSTANT, null));

            assertThat(result.series()).singleElement().satisfies(series -> {
                assertThat(series.samples()).hasSize(1);
                assertThat(series.samples().get(0).value()).isEqualTo(2.0);
            });
        }
    }

    @Test
    @DisplayName("背压 range：明确返回 QUERY_MODE_UNSUPPORTED，不返回快照伪造历史")
    void backpressureRangeUnsupported() throws IOException {
        try (FakeFlink flink = new FakeFlink()) {
            BackendCapability capability = backend(flink).evaluate(
                    spec("flink_backpressure_busy_ratio", QueryMode.RANGE,
                            range("2026-11-15T01:00:00Z", "2026-11-15T02:00:00Z")));

            assertThat(capability.isAvailable()).isFalse();
            assertThat(capability.code()).isEqualTo(CapabilityCode.QUERY_MODE_UNSUPPORTED);
        }
    }

    @Test
    @DisplayName("背压 instant：按 subtask 返回 busyRatio / ratio")
    void backpressureInstant() throws IOException {
        try (FakeFlink flink = new FakeFlink()) {
            MetricQueryResult busy = backend(flink).fetch(
                    spec("flink_backpressure_busy_ratio", QueryMode.INSTANT, null));
            MetricQueryResult backpressured = backend(flink).fetch(
                    spec("flink_backpressure_backpressured_ratio", QueryMode.INSTANT, null));

            assertThat(busy.series()).hasSize(2);
            assertThat(busy.series().get(0).labels())
                    .containsEntry("vertex_id", "v1")
                    .containsEntry("subtask", "0");
            assertThat(busy.series().get(0).samples().get(0).value()).isEqualTo(0.8);
            assertThat(backpressured.series().get(0).samples().get(0).value()).isEqualTo(0.2);
        }
    }

    @Test
    @DisplayName("异常摘要：返回结构化证据，不混入日志接口")
    void exceptionSummary() throws IOException {
        try (FakeFlink flink = new FakeFlink()) {
            JsonNode evidence = backend(flink).exceptionSummary("j1");

            assertThat(evidence.path("root-exception").asText()).contains("boom");
            assertThat(evidence.path("all-exceptions")).hasSize(1);
        }
    }

    @Test
    @DisplayName("缺少 jobId：能力判定直接 INVALID_ARGUMENT")
    void missingJobId() throws IOException {
        try (FakeFlink flink = new FakeFlink()) {
            MetricQuerySpec spec = new MetricQuerySpec(new Target("flink", "flink.jobmanager", "cluster"),
                    definition("flink_checkpoint_duration_seconds"), Map.of(), QueryMode.INSTANT,
                    Instant.now(), null, null, null, Map.of(), null);

            BackendCapability capability = backend(flink).evaluate(spec);
            assertThat(capability.code()).isEqualTo(CapabilityCode.INVALID_ARGUMENT);
        }
    }

    // ------------------------------------------------------------------ 辅助

    private FlinkRestMetricBackend backend(FakeFlink flink) {
        FlinkRestConfig config = new FlinkRestConfig();
        config.setId("flink-rest");
        config.setBaseUrl(flink.baseUrl());
        return new FlinkRestMetricBackend("flink-rest", config, null, new ObjectMapper());
    }

    private MetricQuerySpec spec(String metric, QueryMode mode, TimeRange range) {
        return new MetricQuerySpec(new Target("flink", "flink.jobmanager", "cluster"),
                definition(metric), Map.of("vertex_id", "v1"), mode,
                Instant.parse("2026-11-15T01:00:30Z"),
                range == null ? null : range.start(), range == null ? null : range.end(),
                range == null ? null : Duration.ofSeconds(30),
                Map.of("jobId", "j1", "vertexId", "v1"), null);
    }

    private TimeRange range(String start, String end) {
        return new TimeRange(Instant.parse(start), Instant.parse(end));
    }

    private MetricDefinition definition(String name) {
        return new MetricDefinition(name, "gauge", "seconds", "job",
                List.of(), List.of("jobId"), Map.of(), List.of());
    }

    private static final class FakeFlink implements AutoCloseable {

        private final HttpServer server;

        private FakeFlink() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/jobs/j1/checkpoints", exchange -> respond(exchange, """
                    {"counts":{"total":3,"completed":2,"failed":1},
                     "history":[
                       {"id":1,"status":"COMPLETED","trigger_timestamp":1794704400000,"duration":1200,"size":1024},
                       {"id":2,"status":"COMPLETED","trigger_timestamp":1794704460000,"duration":800,"size":2048},
                       {"id":3,"status":"FAILED","trigger_timestamp":1794704520000,"duration":500,"size":0}],
                     "latest":{"completed":{"id":2,"status":"COMPLETED",
                       "trigger_timestamp":1794704460000,"duration":800,"size":2048}}}
                    """));
            server.createContext("/jobs/j1/vertices/v1/backpressure", exchange -> respond(exchange, """
                    {"end-timestamp":1794704400000,"subtasks":[
                      {"subtask":0,"busyRatio":0.8,"ratio":0.2,"backpressureLevel":"ok"},
                      {"subtask":1,"busyRatio":0.6,"ratio":0.4,"backpressureLevel":"low"}]}
                    """));
            server.createContext("/jobs/j1/exceptions", exchange -> respond(exchange, """
                    {"root-exception":"java.lang.RuntimeException: boom",
                     "timestamp":1794704400000,"truncated":false,
                     "all-exceptions":[{"exceptionName":"java.lang.RuntimeException","stacktrace":"..."}]}
                    """));
            server.start();
        }

        private String baseUrl() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static void respond(HttpExchange exchange, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
