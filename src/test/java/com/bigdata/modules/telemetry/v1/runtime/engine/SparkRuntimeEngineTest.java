package com.bigdata.modules.telemetry.v1.runtime.engine;

import com.bigdata.modules.telemetry.v1.catalog.SeedCatalogContributor;
import com.bigdata.modules.telemetry.v1.catalog.TelemetryCatalogRegistry;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties.SparkRestConfig;
import com.bigdata.modules.telemetry.v1.model.Target;
import com.bigdata.modules.telemetry.v1.runtime.config.RuntimeV1Properties;
import com.bigdata.modules.telemetry.v1.runtime.model.ConfigData;
import com.bigdata.modules.telemetry.v1.runtime.model.RuntimeComponent;
import com.bigdata.modules.telemetry.v1.runtime.model.RuntimeMetric;
import com.bigdata.modules.telemetry.v1.runtime.model.SnapshotData;
import com.bigdata.modules.telemetry.v1.runtime.spi.RuntimeQuerySpec;
import com.bigdata.modules.telemetry.v1.runtime.support.RuntimeFamilies;
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

class SparkRuntimeEngineTest {

    private static final Target TARGET = new Target("spark", "spark.driver", "cluster");

    @Test
    @DisplayName("history：/api/v1/applications 发现应用，无样本时不报错")
    void listsHistoryApplications() throws IOException {
        try (FakeSpark spark = new FakeSpark()) {
            var jobs = engine(spark).jobs(spec(null, List.of(), List.of()));

            assertThat(jobs).singleElement().satisfies(job -> {
                assertThat(job.jobId()).isEqualTo("app-1");
                assertThat(job.state()).isEqualTo("FINISHED");
                assertThat(job.note()).contains("attempts=1");
            });
        }
    }

    @Test
    @DisplayName("snapshot：executor/stage/job 指标与毫秒→秒换算，checkpoint 明确 NOT_SUPPORTED")
    void snapshotReadsRestMetrics() throws IOException {
        try (FakeSpark spark = new FakeSpark()) {
            SnapshotData snapshot = engine(spark).snapshot(spec("app-1",
                    List.of(RuntimeFamilies.JVM_GC, RuntimeFamilies.THROUGHPUT,
                            RuntimeFamilies.FAILURES, RuntimeFamilies.CHECKPOINT),
                    List.of()));

            assertThat(snapshot.state()).isEqualTo("FINISHED");
            assertThat(snapshot.unavailableFamilies())
                    .containsKey(RuntimeFamilies.CHECKPOINT);
            assertThat(snapshot.checkpoint().available()).isFalse();
            assertThat(snapshot.checkpoint().reason()).contains("checkpoint");

            RuntimeComponent executor = snapshot.components().stream()
                    .filter(component -> "executor".equals(component.type()))
                    .findFirst().orElseThrow();
            assertThat(metric(executor, "spark_executor_gc_seconds_total").value())
                    .isEqualTo(0.1);
            assertThat(metric(executor, "spark_executor_memory_used_bytes").value())
                    .isEqualTo(500.0);

            RuntimeComponent stage = snapshot.components().stream()
                    .filter(component -> "stage".equals(component.type()))
                    .findFirst().orElseThrow();
            assertThat(metric(stage, "spark_stage_input_records_total").value()).isEqualTo(10.0);
            assertThat(metric(stage, "spark_stage_attempts").value()).isEqualTo(1.0);

            assertThat(metric(snapshot.jobMetrics(), "spark_application_attempt_count").value())
                    .isEqualTo(1.0);
            assertThat(metric(snapshot.jobMetrics(), "spark_job_tasks_failed_total").value())
                    .isEqualTo(2.0);

            RuntimeMetric missing = snapshot.components().stream()
                    .filter(component -> "executor".equals(component.type()))
                    .findFirst().orElseThrow().metrics().stream()
                    .filter(metric -> "spark_executor_active_tasks".equals(metric.name()))
                    .findFirst().orElseThrow();
            assertThat(missing.availability()).isEqualTo(RuntimeMetric.UNAVAILABLE);
            assertThat(missing.value()).isNull();
        }
    }

    @Test
    @DisplayName("config：sparkProperties 来自 /environment")
    void readsEnvironment() throws IOException {
        try (FakeSpark spark = new FakeSpark()) {
            ConfigData config = engine(spark).config(spec("app-1", List.of(), List.of()));

            assertThat(config.available()).isTrue();
            assertThat(config.sections()).containsKey("sparkProperties");
        }
    }

    // ------------------------------------------------------------------ 辅助

    private SparkRuntimeEngine engine(FakeSpark spark) {
        SparkRestConfig config = new SparkRestConfig();
        config.setBaseUrl(spark.baseUrl());
        config.setMode("history");
        config.getLimits().setTimeoutMs(3000);
        return new SparkRuntimeEngine("spark-rest", config, null, null, new ObjectMapper(),
                new TelemetryCatalogRegistry(List.of(new SeedCatalogContributor())),
                new RuntimeV1Properties());
    }

    private RuntimeQuerySpec spec(String jobId, List<String> families, List<String> metrics) {
        return new RuntimeQuerySpec(TARGET, "history", jobId, families, metrics, List.of(), null,
                false, 10, 5);
    }

    private RuntimeMetric metric(List<RuntimeMetric> metrics, String name) {
        return metrics.stream().filter(metric -> name.equals(metric.name())).findFirst()
                .orElseThrow();
    }

    private RuntimeMetric metric(RuntimeComponent component, String name) {
        return metric(component.metrics(), name);
    }

    private static final class FakeSpark implements AutoCloseable {

        private final HttpServer server;

        private FakeSpark() throws IOException {
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
                case "/api/v1/applications" -> """
                        [{"id":"app-1","name":"spark demo",
                          "attempts":[{"startTime":1000,"endTime":2000,"duration":1000,
                            "completed":true}]}]
                        """;
                case "/api/v1/applications/app-1" -> """
                        {"id":"app-1","name":"spark demo",
                         "attempts":[{"startTime":1000,"endTime":2000,"duration":1000,
                           "completed":true}]}
                        """;
                case "/api/v1/applications/app-1/allexecutors" -> """
                        [{"id":"1","hostPort":"127.0.0.1:4000","totalCores":2,
                          "maxMemory":1000,"memoryUsed":500,"totalGCTime":100,
                          "totalDuration":1000,"totalInputBytes":2048,
                          "failedTasks":0,"completedTasks":3,"totalTasks":3,
                          "isActive":false,"removeTime":1500}]
                        """;
                case "/api/v1/applications/app-1/executors" -> "[]";
                case "/api/v1/applications/app-1/stages" -> """
                        [{"stageId":0,"attemptId":0,"status":"COMPLETE","name":"map",
                          "inputRecords":10,"outputRecords":9,"inputBytes":100,
                          "outputBytes":90,"shuffleReadBytes":0,"shuffleWriteBytes":0,
                          "shuffleReadRecords":0,"shuffleWriteRecords":0,
                          "shuffleFetchWaitTime":1,"shuffleWriteTime":2,
                          "numFailedTasks":1,"numKilledTasks":0,
                          "submissionTime":1100,"completionTime":1200}]
                        """;
                case "/api/v1/applications/app-1/jobs" -> """
                        [{"numFailedTasks":2,"numKilledTasks":1}]
                        """;
                case "/api/v1/applications/app-1/environment" -> """
                        {"sparkProperties":[["spark.app.name","spark demo"],
                          ["spark.executor.memory","1g"]],
                         "hadoopProperties":[],"systemProperties":[],"classpathEntries":[]}
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
