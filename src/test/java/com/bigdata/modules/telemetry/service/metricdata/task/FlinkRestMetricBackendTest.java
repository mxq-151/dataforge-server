package com.bigdata.modules.telemetry.service.metricdata.task;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDefinition;
import com.bigdata.modules.telemetry.common.model.QueryMode;
import com.bigdata.modules.telemetry.common.model.Target;
import com.bigdata.modules.telemetry.common.model.TimeRange;
import com.bigdata.modules.telemetry.common.model.BackendCapability;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricQueryResult;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricQuerySpec;
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
import com.bigdata.modules.telemetry.config.properties.FlinkRestConfig;

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
    @DisplayName("checkpoint total range + rate：按事件间隔计算每秒速率")
    void checkpointRate() throws IOException {
        try (FakeFlink flink = new FakeFlink()) {
            MetricQueryResult result = backend(flink).fetch(specRate(
                    "flink_checkpoint_completed_total",
                    range("2026-11-15T01:00:00Z", "2026-11-15T01:02:00Z")));

            assertThat(result.series()).singleElement().satisfies(series -> {
                assertThat(series.samples()).hasSize(1);
                assertThat(series.samples().get(0).value()).isCloseTo(1.0 / 60.0,
                        org.assertj.core.data.Offset.offset(0.0001));
            });
        }
    }

    @Test
    @DisplayName("checkpoint 状态：in_progress / restored / latest failed reason")
    void checkpointView() throws IOException {
        try (FakeFlink flink = new FakeFlink()) {
            MetricQueryResult inProgress = backend(flink).fetch(
                    spec("flink_checkpoint_in_progress", QueryMode.INSTANT, null));
            MetricQueryResult restored = backend(flink).fetch(
                    spec("flink_checkpoint_restored_total", QueryMode.INSTANT, null));
            MetricQueryResult failed = backend(flink).fetch(
                    spec("flink_checkpoint_latest_failed", QueryMode.INSTANT, null));

            assertThat(inProgress.series().get(0).samples().get(0).value()).isEqualTo(1.0);
            assertThat(restored.series().get(0).samples().get(0).value()).isEqualTo(3.0);
            assertThat(failed.series()).singleElement().satisfies(series -> {
                assertThat(series.labels()).containsEntry("checkpoint_id", "3");
                assertThat(series.labels().get("reason")).contains("disk full");
            });
        }
    }

    @Test
    @DisplayName("job 详情：tasks.failed / tasks.total")
    void jobTasks() throws IOException {
        try (FakeFlink flink = new FakeFlink()) {
            MetricQueryResult failed = backend(flink).fetch(
                    spec("flink_tasks_failed", QueryMode.INSTANT, null));
            MetricQueryResult total = backend(flink).fetch(
                    spec("flink_tasks_total", QueryMode.INSTANT, null));

            assertThat(failed.series().get(0).samples().get(0).value()).isEqualTo(2.0);
            assertThat(total.series().get(0).samples().get(0).value()).isEqualTo(10.0);
        }
    }

    @Test
    @DisplayName("TM 清单：不需要 jobId，返回 slots / CPU / 内存 / 心跳年龄")
    void taskManagerInventory() throws IOException {
        try (FakeFlink flink = new FakeFlink()) {
            MetricQueryResult info = backend(flink).fetch(tmSpec("flink_taskmanager_info"));
            MetricQueryResult slots = backend(flink).fetch(tmSpec("flink_taskmanager_slots_total"));
            MetricQueryResult cores = backend(flink).fetch(tmSpec("flink_taskmanager_cpu_cores"));

            assertThat(info.series()).singleElement().satisfies(series -> {
                assertThat(series.labels()).containsEntry("taskmanager_id", "tm1");
                assertThat(series.labels()).containsEntry("host", "host1");
                assertThat(series.labels()).containsEntry("status", "ACTIVE");
                assertThat(series.samples().get(0).value()).isEqualTo(1.0);
            });
            assertThat(slots.series().get(0).samples().get(0).value()).isEqualTo(4.0);
            assertThat(cores.series().get(0).samples().get(0).value()).isEqualTo(4.0);
        }
    }

    @Test
    @DisplayName("vertex 清单：返回顶点状态、并行度与失败 task 数")
    void vertexInventory() throws IOException {
        try (FakeFlink flink = new FakeFlink()) {
            MetricQueryResult info = backend(flink).fetch(
                    spec("flink_vertex_info", QueryMode.INSTANT, null));
            MetricQueryResult parallelism = backend(flink).fetch(
                    spec("flink_vertex_parallelism", QueryMode.INSTANT, null));
            MetricQueryResult failed = backend(flink).fetch(
                    spec("flink_vertex_tasks_failed", QueryMode.INSTANT, null));

            assertThat(info.series()).hasSize(2);
            assertThat(info.series().get(0).labels())
                    .containsEntry("vertex_id", "v1")
                    .containsEntry("vertex_name", "map")
                    .containsEntry("status", "RUNNING");
            assertThat(parallelism.series().get(0).samples().get(0).value()).isEqualTo(2.0);
            assertThat(failed.series().get(0).samples().get(0).value()).isEqualTo(1.0);
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

    private MetricQuerySpec specRate(String metric, TimeRange range) {
        return new MetricQuerySpec(new Target("flink", "flink.jobmanager", "cluster"),
                definition(metric), Map.of(), QueryMode.RANGE, null,
                range.start(), range.end(), Duration.ofSeconds(30),
                Map.of("jobId", "j1"), null, true);
    }

    private MetricQuerySpec tmSpec(String metric) {
        return new MetricQuerySpec(new Target("flink", "flink.taskmanager", "cluster"),
                definition(metric), Map.of(), QueryMode.INSTANT, Instant.now(),
                null, null, null, Map.of(), null);
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
                    {"counts":{"total":3,"completed":2,"failed":1,"in_progress":1,"restored":3},
                     "history":[
                       {"id":1,"status":"COMPLETED","trigger_timestamp":1794704400000,"duration":1200,"size":1024},
                       {"id":2,"status":"COMPLETED","trigger_timestamp":1794704460000,"duration":800,"size":2048},
                       {"id":3,"status":"FAILED","trigger_timestamp":1794704520000,"duration":500,"size":0}],
                     "latest":{"completed":{"id":2,"status":"COMPLETED",
                       "trigger_timestamp":1794704460000,"duration":800,"size":2048},
                       "failed":{"id":3,"status":"FAILED","trigger_timestamp":1794704520000,
                         "failure_message":"java.io.IOException: disk full"}}}
                    """));
            server.createContext("/jobs/j1", exchange -> respond(exchange, """
                    {"jid":"j1","name":"job","state":"RUNNING",
                     "status-counts":{"CREATED":0,"SCHEDULED":0,"DEPLOYING":0,"RUNNING":0,
                       "FINISHED":6,"CANCELING":0,"CANCELED":2,"FAILED":2,
                       "RECONCILING":0,"INITIALIZING":0},
                     "vertices":[
                       {"id":"v1","name":"map","status":"RUNNING","parallelism":2,
                        "tasks":{"FAILED":1}},
                       {"id":"v2","name":"sink","status":"RUNNING","parallelism":4,
                        "tasks":{"FAILED":0}}]}
                    """));
            server.createContext("/taskmanagers", exchange -> respond(exchange,
                    "{\"taskmanagers\":[{\"id\":\"tm1\","
                            + "\"path\":\"akka.tcp://flink@host1:1234/user/taskmanager\","
                            + "\"slotsNumber\":4,\"freeSlots\":1,"
                            + "\"timeSinceLastHeartbeat\":" + (System.currentTimeMillis() - 5000)
                            + ",\"totalResource\":{\"cpuCores\":4,\"memory\":1024,"
                            + "\"managedMemory\":512},"
                            + "\"hardware\":{\"physicalMemory\":2048}}]}"));
            server.createContext("/jobs/j1/vertices/v1/backpressure", exchange -> respond(exchange, """
                    {"end-timestamp":1794704400000,"subtasks":[
                      {"subtask":0,"busyRatio":0.8,"ratio":0.2,"backpressureLevel":"ok"},
                      {"subtask":1,"busyRatio":0.6,"ratio":0.4,"backpressureLevel":"low"}]}
                    """));
            server.createContext("/jobs/j1/exceptions", exchange -> respond(exchange, """
                    {"exceptionHistory":{"entries":[{"exceptionName":"java.lang.RuntimeException",
                      "stacktrace":"java.lang.RuntimeException: boom\\nCaused by: java.io.IOException: disk",
                      "timestamp":1794704400000,"failureLabels":{},
                      "concurrentExceptions":[]}],"truncated":false}}
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
