package com.bigdata.modules.telemetry.v1.backend.spark;

import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties.SparkRestConfig;
import com.bigdata.modules.telemetry.v1.model.MetricDefinition;
import com.bigdata.modules.telemetry.v1.model.QueryMode;
import com.bigdata.modules.telemetry.v1.model.Target;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SparkRestMetricBackendTest {

    @Test
    @DisplayName("stage 快照：按 stage 返回完成态数值与标签")
    void stageSnapshot() throws IOException {
        try (FakeSpark spark = new FakeSpark()) {
            MetricQueryResult result = backend(spark).fetch(
                    spec("spark_stage_input_records_total"));

            assertThat(result.series()).hasSize(2);
            assertThat(result.series().get(0).labels())
                    .containsEntry("application_id", "app-1")
                    .containsEntry("stage_id", "1")
                    .containsEntry("attempt_id", "0");
            assertThat(result.series().get(0).samples().get(0).value()).isEqualTo(100.0);
            assertThat(result.series().get(0).samples().get(0).timestamp())
                    .isEqualTo(Instant.parse("2026-11-15T01:01:00Z"));
        }
    }

    @Test
    @DisplayName("executor 快照：GC 毫秒转秒，driver 不计入 executor 指标")
    void executorSnapshot() throws IOException {
        try (FakeSpark spark = new FakeSpark()) {
            MetricQueryResult result = backend(spark).fetch(
                    spec("spark_executor_gc_seconds_total"));

            assertThat(result.series()).singleElement().satisfies(series -> {
                assertThat(series.labels()).containsEntry("executor_id", "1");
                assertThat(series.samples().get(0).value()).isEqualTo(3.5);
            });
        }
    }

    @Test
    @DisplayName("stage 过滤：labelMatchers.stage_id 只保留指定 stage")
    void stageFilter() throws IOException {
        try (FakeSpark spark = new FakeSpark()) {
            MetricQuerySpec spec = new MetricQuerySpec(target(), definition("spark_stage_input_records_total"),
                    Map.of("stage_id", "2"), QueryMode.INSTANT, Instant.now(),
                    null, null, null, Map.of("applicationId", "app-1"), null);

            MetricQueryResult result = backend(spark).fetch(spec);
            assertThat(result.series()).singleElement()
                    .satisfies(series -> assertThat(series.labels()).containsEntry("stage_id", "2"));
        }
    }

    @Test
    @DisplayName("range：SHS 没有等步长历史，明确返回 QUERY_MODE_UNSUPPORTED")
    void rangeUnsupported() throws IOException {
        try (FakeSpark spark = new FakeSpark()) {
            MetricQuerySpec spec = new MetricQuerySpec(target(), definition("spark_stage_input_records_total"),
                    Map.of(), QueryMode.RANGE, null,
                    Instant.parse("2026-11-15T01:00:00Z"), Instant.parse("2026-11-15T02:00:00Z"),
                    java.time.Duration.ofSeconds(30), Map.of("applicationId", "app-1"), null);

            BackendCapability capability = backend(spark).evaluate(spec);
            assertThat(capability.isAvailable()).isFalse();
            assertThat(capability.code()).isEqualTo(CapabilityCode.QUERY_MODE_UNSUPPORTED);
        }
    }

    @Test
    @DisplayName("拓扑：jobs + stageIds 结构化返回")
    void topology() throws IOException {
        try (FakeSpark spark = new FakeSpark()) {
            JsonNode topology = backend(spark).topology("app-1");

            assertThat(topology).hasSize(1);
            assertThat(topology.get(0).path("stageIds")).hasSize(2);
        }
    }

    // ------------------------------------------------------------------ 辅助

    private SparkRestMetricBackend backend(FakeSpark spark) {
        SparkRestConfig config = new SparkRestConfig();
        config.setId("spark-rest");
        config.setBaseUrl(spark.baseUrl());
        config.setMode("history");
        return new SparkRestMetricBackend("spark-rest", config, null, new ObjectMapper());
    }

    private MetricQuerySpec spec(String metric) {
        return new MetricQuerySpec(target(), definition(metric), Map.of(), QueryMode.INSTANT,
                Instant.parse("2026-11-15T02:00:00Z"), null, null, null,
                Map.of("applicationId", "app-1"), null);
    }

    private Target target() {
        return new Target("spark", "spark.driver", "cluster");
    }

    private MetricDefinition definition(String name) {
        return new MetricDefinition(name, "counter", "count", "stage",
                List.of(), List.of("applicationId"), Map.of(), List.of());
    }

    private static final class FakeSpark implements AutoCloseable {

        private final HttpServer server;

        private FakeSpark() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/api/v1/applications/app-1/stages", exchange -> respond(exchange, """
                    [{"stageId":1,"attemptId":0,"status":"COMPLETE","inputRecords":100,
                      "outputRecords":90,"shuffleReadBytes":1024,"shuffleWriteBytes":2048,
                      "numFailedTasks":1,"submissionTime":"2026-11-15T01:00:00Z",
                      "completionTime":"2026-11-15T01:01:00Z"},
                     {"stageId":2,"attemptId":0,"status":"COMPLETE","inputRecords":50,
                      "outputRecords":40,"shuffleReadBytes":512,"shuffleWriteBytes":256,
                      "numFailedTasks":0,"submissionTime":"2026-11-15T01:02:00Z",
                      "completionTime":"2026-11-15T01:03:00Z"}]
                    """));
            server.createContext("/api/v1/applications/app-1/allexecutors", exchange -> respond(exchange, """
                    [{"id":"driver","totalGCTime":100,"maxMemory":1000,"memoryUsed":500,
                      "totalDuration":1000,"totalCores":0,"activeTasks":0,
                      "totalInputBytes":0,"failedTasks":0},
                     {"id":"1","totalGCTime":3500,"maxMemory":2000,"memoryUsed":1500,
                      "totalDuration":2000,"totalCores":2,"activeTasks":1,
                      "totalInputBytes":4096,"failedTasks":3}]
                    """));
            server.createContext("/api/v1/applications/app-1/jobs", exchange -> respond(exchange, """
                    [{"jobId":0,"name":"job","stageIds":[1,2],"status":"SUCCEEDED"}]
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
