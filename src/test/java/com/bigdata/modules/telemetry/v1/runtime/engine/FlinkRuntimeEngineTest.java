package com.bigdata.modules.telemetry.v1.runtime.engine;

import com.bigdata.modules.telemetry.v1.catalog.FlinkMetricSemantics;
import com.bigdata.modules.telemetry.v1.catalog.RestMetricSemanticsRegistry;
import com.bigdata.modules.telemetry.v1.catalog.SeedCatalogContributor;
import com.bigdata.modules.telemetry.v1.catalog.TelemetryCatalogRegistry;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties.FlinkRestConfig;
import com.bigdata.modules.telemetry.v1.model.Target;
import com.bigdata.modules.telemetry.v1.runtime.config.RuntimeV1Properties;
import com.bigdata.modules.telemetry.v1.runtime.model.ConfigData;
import com.bigdata.modules.telemetry.v1.runtime.model.RuntimeComponent;
import com.bigdata.modules.telemetry.v1.runtime.model.RuntimeMetric;
import com.bigdata.modules.telemetry.v1.runtime.model.SnapshotData;
import com.bigdata.modules.telemetry.v1.runtime.spi.RuntimeQuerySpec;
import com.bigdata.modules.telemetry.v1.runtime.support.RuntimeFamilies;
import com.bigdata.modules.telemetry.v1.spi.BackendException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlinkRuntimeEngineTest {

    private static final Target TARGET = new Target("flink", "flink.jobmanager", "cluster");

    @Test
    @DisplayName("jobs：/jobs/overview 解析为真实作业条目")
    void listsJobs() throws IOException {
        try (FakeFlink flink = new FakeFlink()) {
            var jobs = engine(flink).jobs(spec(null, List.of(), List.of(), List.of()));

            assertThat(jobs).singleElement().satisfies(job -> {
                assertThat(job.jobId()).isEqualTo("j1");
                assertThat(job.name()).isEqualTo("demo");
                assertThat(job.state()).isEqualTo("RUNNING");
                assertThat(job.note()).contains("4/4");
            });
        }
    }

    @Test
    @DisplayName("components：timeSinceLastHeartbeat 是时间戳，需按 now-heartbeat 判定 ACTIVE")
    void taskManagerHeartbeatIsTimestamp() throws IOException {
        try (FakeFlink flink = new FakeFlink()) {
            List<RuntimeComponent> components = engine(flink).components(
                    spec(null, List.of(RuntimeFamilies.JVM_GC), List.of(), List.of())
                            .withComponentRole("taskmanager"));

            assertThat(components).singleElement().satisfies(component -> {
                assertThat(component.id()).isEqualTo("tm-1");
                assertThat(component.status()).isEqualTo("ACTIVE");
                assertThat(component.host()).isEqualTo("127.0.0.1");
                assertThat(component.cores()).isEqualTo(4);
                assertThat(metric(component, "jvm_heap_used_bytes").value()).isEqualTo(1024.0);
            });
        }
    }

    @Test
    @DisplayName("snapshot：作业级/vertex/checkpoint 指标来自真实端点，缺失指标不填 0")
    void snapshotAggregatesEndpoints() throws IOException {
        try (FakeFlink flink = new FakeFlink()) {
            SnapshotData snapshot = engine(flink).snapshot(spec("j1",
                    List.of(RuntimeFamilies.THROUGHPUT, RuntimeFamilies.SHUFFLE,
                            RuntimeFamilies.FAILURES, RuntimeFamilies.JVM_GC),
                    List.of(), List.of()));

            assertThat(snapshot.jobId()).isEqualTo("j1");
            assertThat(snapshot.state()).isEqualTo("RUNNING");
            assertThat(metric(snapshot.jobMetrics(), "flink_records_in_total").value())
                    .isEqualTo(42.0);
            assertThat(metric(snapshot.jobMetrics(), "flink_tasks_failed").value()).isEqualTo(1.0);

            RuntimeComponent vertex = snapshot.components().stream()
                    .filter(component -> "vertex".equals(component.type()))
                    .findFirst().orElseThrow();
            assertThat(metric(vertex, "flink_shuffle_bytes_out_total").value()).isEqualTo(15.0);
            assertThat(metric(vertex, "flink_vertex_records_out_rate").value()).isEqualTo(10.0);

            RuntimeComponent tm = snapshot.components().stream()
                    .filter(component -> "taskmanager".equals(component.type()))
                    .findFirst().orElseThrow();
            assertThat(metric(tm, "jvm_heap_used_bytes").value()).isEqualTo(1024.0);

            assertThat(snapshot.checkpoint().available()).isTrue();
            assertThat(snapshot.checkpoint().completed()).isEqualTo(2);
            assertThat(snapshot.checkpoint().latestFailedReason()).contains("timeout");

            RuntimeMetric missing = snapshot.jobMetrics().stream()
                    .filter(metric -> "flink_records_out_total".equals(metric.name()))
                    .findFirst().orElseThrow();
            assertThat(missing.availability()).isEqualTo(RuntimeMetric.UNAVAILABLE);
            assertThat(missing.value()).isNull();
            assertThat(missing.reason()).isNotBlank();
        }
    }

    @Test
    @DisplayName("config：job + jobmanager 两段，未返回时如实标注")
    void readsConfig() throws IOException {
        try (FakeFlink flink = new FakeFlink()) {
            ConfigData config = engine(flink).config(spec("j1", List.of(), List.of(), List.of()));

            assertThat(config.available()).isTrue();
            assertThat(config.sections()).containsKey("job").containsKey("jobmanager");
            assertThat(config.warnings()).anyMatch(warning -> warning.contains("JVM"));
        }
    }

    @Test
    @DisplayName("未配置 base-url 时返回 NOT_CONFIGURED，不发请求")
    void unconfiguredEngine() {
        FlinkRestConfig config = new FlinkRestConfig();
        FlinkRuntimeEngine engine = new FlinkRuntimeEngine("flink-rest", config, null,
                "未配置 telemetry.backends.flink-rest[].base-url", new ObjectMapper(),
                catalog(), new RuntimeV1Properties());

        assertThat(engine.configured()).isFalse();
        assertThatThrownBy(() -> engine.jobs(spec(null, List.of(), List.of(), List.of())))
                .isInstanceOf(BackendException.class)
                .hasMessageContaining("base-url");
    }

    // ------------------------------------------------------------------ 辅助

    private FlinkRuntimeEngine engine(FakeFlink flink) {
        FlinkRestConfig config = new FlinkRestConfig();
        config.setBaseUrl(flink.baseUrl());
        config.getLimits().setTimeoutMs(3000);
        return new FlinkRuntimeEngine("flink-rest", config, null, null, new ObjectMapper(),
                catalog(), new RuntimeV1Properties());
    }

    private TelemetryCatalogRegistry catalog() {
        return new TelemetryCatalogRegistry(List.of(new SeedCatalogContributor()),
                new RestMetricSemanticsRegistry(new FlinkMetricSemantics()));
    }

    private RuntimeQuerySpec spec(String jobId, List<String> families, List<String> metrics,
                                  List<String> components) {
        return new RuntimeQuerySpec(TARGET, "live", jobId, families, metrics, components, null,
                false, 10, 5);
    }

    private RuntimeMetric metric(List<RuntimeMetric> metrics, String name) {
        return metrics.stream().filter(metric -> name.equals(metric.name())).findFirst()
                .orElseThrow();
    }

    private RuntimeMetric metric(RuntimeComponent component, String name) {
        return metric(component.metrics(), name);
    }

    /** 只覆盖本用例需要的 Flink JM 端点。 */
    private static final class FakeFlink implements AutoCloseable {

        private final HttpServer server;

        private FakeFlink() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String body = switch (path) {
                case "/jobs/overview" -> """
                        {"jobs":[{"jid":"j1","name":"demo","state":"RUNNING",
                          "start-time":1000,"end-time":-1,"duration":500,
                          "tasks":{"failed":0,"total":4}}]}
                        """;
                case "/jobs/j1" -> """
                        {"jid":"j1","name":"demo","state":"RUNNING","start-time":1000,
                         "end-time":-1,"duration":500,"tasks":{"failed":1},
                         "vertices":[{"id":"v1","name":"map","status":"RUNNING"}]}
                        """;
                case "/taskmanagers" -> """
                        {"taskmanagers":[{"id":"tm-1",
                          "path":"pekko.tcp://flink@127.0.0.1:1234/user/rpc/taskmanager_0",
                          "timeSinceLastHeartbeat":%d,"slotsNumber":8,"freeSlots":8,
                          "totalResource":{"cpuCores":4.0,"memory":1024,"managedMemory":512},
                          "hardware":{"physicalMemory":2048}}]}
                        """.formatted(System.currentTimeMillis() - 1000);
                case "/taskmanagers/tm-1/metrics" -> """
                        [{"id":"Status.JVM.Memory.Heap.Used","value":"1024"}]
                        """;
                case "/jobs/j1/metrics" -> """
                        [{"id":"numRecordsIn","value":"42"},
                         {"id":"numBytesOutPerSecond","value":"7"}]
                        """;
                case "/jobs/j1/vertices/v1/subtasks/metrics" -> """
                        [{"id":"numBytesOut","min":5.0,"max":10.0,"sum":15.0},
                         {"id":"numRecordsOutPerSecond","min":10.0,"max":10.0,"sum":10.0},
                         {"id":"busyTimeMsPerSecond","min":5.0,"max":5.0,"sum":5.0}]
                        """;
                case "/jobs/j1/checkpoints" -> """
                        {"counts":{"completed":2,"failed":1,"in_progress":0,"restored":0},
                         "latest":{"failed":{"id":2,"failure_message":"timeout"}},
                         "history":[{"id":1,"status":"COMPLETED","trigger_timestamp":1000,
                           "latest_ack_timestamp":1500,"end_to_end_duration":500,
                           "state_size":2048}]}
                        """;
                case "/jobs/j1/config" -> """
                        {"execution-config":{"parallelism.default":"2"}}
                        """;
                case "/jobmanager/config" -> """
                        [{"key":"taskmanager.memory.process.size","value":"1024m"}]
                        """;
                default -> "{}";
            };
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
