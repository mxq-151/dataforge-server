package com.bigdata.modules.telemetry.service.metricdata.task;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDefinition;
import com.bigdata.modules.telemetry.common.model.QueryMode;
import com.bigdata.modules.telemetry.common.model.Target;
import com.bigdata.modules.telemetry.common.model.BackendCapability;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricQueryResult;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricQuerySpec;
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
import com.bigdata.modules.telemetry.config.properties.SparkShsRestConfig;

class SparkShsRestMetricBackendTest {

    @Test
    @DisplayName("stage 快照：按 stage 返回完成态数值与标签")
    void stageSnapshot() throws IOException {
        try (FakeSpark spark = new FakeSpark()) {
            MetricQueryResult result = backend(spark).fetch(
                    spec("spark_stage_input_records_total", Map.of("applicationId", "app-1")));

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
    @DisplayName("stage 快照：spill 与 attempt 序号（attemptId + 1）")
    void stageSpillAndAttempts() throws IOException {
        try (FakeSpark spark = new FakeSpark()) {
            MetricQueryResult spill = backend(spark).fetch(
                    spec("spark_stage_disk_bytes_spilled_total", Map.of("applicationId", "app-1")));
            MetricQueryResult attempts = backend(spark).fetch(
                    spec("spark_stage_attempts", Map.of("applicationId", "app-1")));

            assertThat(spill.series()).hasSize(2);
            assertThat(spill.series().get(0).samples().get(0).value()).isEqualTo(4096.0);
            assertThat(attempts.series().get(0).samples().get(0).value()).isEqualTo(1.0);
        }
    }

    @Test
    @DisplayName("taskSummary：按 stage/attempt/quantile 返回分位值")
    void taskSummary() throws IOException {
        try (FakeSpark spark = new FakeSpark()) {
            MetricQueryResult result = backend(spark).fetch(spec(
                    "spark_task_shuffle_read_bytes",
                    Map.of("applicationId", "app-1", "stageId", "1")));

            assertThat(result.series()).hasSize(2);
            assertThat(result.series().get(0).labels())
                    .containsEntry("stage_id", "1")
                    .containsEntry("attempt_id", "0")
                    .containsEntry("quantile", "0.5");
            assertThat(result.series().get(0).samples().get(0).value()).isEqualTo(150.0);
            assertThat(result.series().get(1).samples().get(0).value()).isEqualTo(300.0);
        }
    }

    @Test
    @DisplayName("taskSummary：兼容以分位为 key 的对象形态")
    void taskSummaryObjectShape() throws IOException {
        try (FakeSpark spark = new FakeSpark()) {
            MetricQueryResult result = backend(spark).fetch(spec(
                    "spark_task_shuffle_read_bytes",
                    Map.of("applicationId", "app-1", "stageId", "2")));

            assertThat(result.series()).hasSize(2);
            assertThat(result.series().get(0).labels()).containsEntry("quantile", "0.5");
            assertThat(result.series().get(0).samples().get(0).value()).isEqualTo(150.0);
            assertThat(result.series().get(1).samples().get(0).value()).isEqualTo(300.0);
        }
    }

    @Test
    @DisplayName("executor 快照：GC 毫秒转秒，driver 作为本地执行器一并返回")
    void executorSnapshot() throws IOException {
        try (FakeSpark spark = new FakeSpark()) {
            MetricQueryResult result = backend(spark).fetch(
                    spec("spark_executor_gc_seconds_total", Map.of("applicationId", "app-1")));

            assertThat(result.series()).hasSize(2);
            assertThat(result.series()).anySatisfy(series -> {
                assertThat(series.labels()).containsEntry("executor_id", "1");
                assertThat(series.samples().get(0).value()).isEqualTo(3.5);
            });
            assertThat(result.series()).anySatisfy(series -> {
                assertThat(series.labels()).containsEntry("executor_id", "driver");
                assertThat(series.samples().get(0).value()).isEqualTo(0.1);
            });
        }
    }

    @Test
    @DisplayName("运行中应用：带 SPARK_APP_INCOMPLETE 告警，不把滞后快照当终值")
    void runningApplicationWarning() throws IOException {
        try (FakeSpark spark = new FakeSpark()) {
            MetricQueryResult result = backend(spark).fetch(
                    spec("spark_stage_input_records_total", Map.of("applicationId", "app-2")));

            assertThat(result.warnings()).singleElement()
                    .satisfies(warning -> assertThat(warning.code()).isEqualTo("SPARK_APP_INCOMPLETE"));
            assertThat(result.series()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("executor 堆峰值：历史态取 peakMemoryMetrics.JVMHeapMemory，不用恒 0 的 memoryUsed")
    void executorHeapPeak() throws IOException {
        try (FakeSpark spark = new FakeSpark()) {
            MetricQueryResult result = backend(spark).fetch(
                    spec("spark_executor_jvm_heap_peak_bytes", Map.of("applicationId", "app-1")));

            assertThat(result.series()).hasSize(2);
            assertThat(result.series()).anySatisfy(series -> {
                assertThat(series.labels()).containsEntry("executor_id", "driver");
                assertThat(series.samples().get(0).value()).isEqualTo(147040768.0);
            });
        }
    }

    @Test
    @DisplayName("application attempts：数组长度作为 gauge")
    void applicationAttempts() throws IOException {
        try (FakeSpark spark = new FakeSpark()) {
            MetricQueryResult result = backend(spark).fetch(
                    spec("spark_application_attempt_count", Map.of("applicationId", "app-1")));

            assertThat(result.series()).singleElement().satisfies(series -> {
                assertThat(series.labels()).containsEntry("application_id", "app-1");
                assertThat(series.samples().get(0).value()).isEqualTo(2.0);
            });
        }
    }

    @Test
    @DisplayName("stage 过滤：labelMatchers.stage_id 只保留指定 stage")
    void stageFilter() throws IOException {
        try (FakeSpark spark = new FakeSpark()) {
            MetricQuerySpec spec = new MetricQuerySpec(target(),
                    definition("spark_stage_input_records_total", List.of("applicationId")),
                    Map.of("stage_id", "2"), QueryMode.INSTANT, Instant.now(),
                    null, null, null, Map.of("applicationId", "app-1"), null);

            MetricQueryResult result = backend(spark).fetch(spec);
            assertThat(result.series()).singleElement()
                    .satisfies(series -> assertThat(series.labels()).containsEntry("stage_id", "2"));
        }
    }

    @Test
    @DisplayName("task 指标缺少 ref.stageId：能力判定直接 INVALID_ARGUMENT")
    void taskMissingStage() throws IOException {
        try (FakeSpark spark = new FakeSpark()) {
            BackendCapability capability = backend(spark).evaluate(spec(
                    "spark_task_input_records", Map.of("applicationId", "app-1")));

            assertThat(capability.isAvailable()).isFalse();
            assertThat(capability.code()).isEqualTo(CapabilityCode.INVALID_ARGUMENT);
        }
    }

    @Test
    @DisplayName("range：SHS 没有等步长历史，明确返回 QUERY_MODE_UNSUPPORTED")
    void rangeUnsupported() throws IOException {
        try (FakeSpark spark = new FakeSpark()) {
            MetricQuerySpec spec = new MetricQuerySpec(target(),
                    definition("spark_stage_input_records_total", List.of("applicationId")),
                    Map.of(), QueryMode.RANGE, null,
                    Instant.parse("2026-11-15T01:00:00Z"), Instant.parse("2026-11-15T02:00:00Z"),
                    java.time.Duration.ofSeconds(30), Map.of("applicationId", "app-1"), null);

            BackendCapability capability = backend(spark).evaluate(spec);
            assertThat(capability.isAvailable()).isFalse();
            assertThat(capability.code()).isEqualTo(CapabilityCode.QUERY_MODE_UNSUPPORTED);
        }
    }

    // ------------------------------------------------------------------ 辅助

    private SparkShsRestMetricBackend backend(FakeSpark spark) {
        SparkShsRestConfig config = new SparkShsRestConfig();
        config.setId("spark-shs-rest");
        config.setBaseUrl(spark.baseUrl());
        config.setMode("history");
        return new SparkShsRestMetricBackend("spark-shs-rest", config, null, new ObjectMapper());
    }

    private MetricQuerySpec spec(String metric, Map<String, String> refs) {
        List<String> required = metric.startsWith("spark_task_")
                ? List.of("applicationId", "stageId") : List.of("applicationId");
        return new MetricQuerySpec(target(), definition(metric, required), Map.of(),
                QueryMode.INSTANT, Instant.parse("2026-11-15T02:00:00Z"), null, null, null,
                refs, null);
    }

    private Target target() {
        return new Target("spark", "spark.driver", "cluster");
    }

    private MetricDefinition definition(String name, List<String> requiredRefs) {
        return new MetricDefinition(name, "counter", "count", "stage",
                List.of(), requiredRefs, Map.of(), List.of());
    }

    private static final class FakeSpark implements AutoCloseable {

        private final HttpServer server;

        private FakeSpark() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            // 应用带 attempt（YARN application_* 形态）：SHS 要求 URL 里带 attemptId。
            for (String attempt : new String[]{"", "/2"}) {
                server.createContext("/api/v1/applications/app-1" + attempt + "/stages/1/0/taskSummary",
                        exchange -> respond(exchange, TASK_SUMMARY));
                server.createContext("/api/v1/applications/app-1" + attempt + "/stages/2/0/taskSummary",
                        exchange -> respond(exchange, OBJECT_TASK_SUMMARY));
                server.createContext("/api/v1/applications/app-1" + attempt + "/stages",
                        exchange -> respond(exchange, STAGES));
                server.createContext("/api/v1/applications/app-1" + attempt + "/allexecutors",
                        exchange -> respond(exchange, EXECUTORS));
            }
            server.createContext("/api/v1/applications/app-1/jobs", exchange -> respond(exchange, """
                    [{"jobId":0,"name":"job","stageIds":[1,2],"status":"SUCCEEDED"}]
                    """));
            server.createContext("/api/v1/applications/app-1", exchange -> respond(exchange, """
                    {"id":"app-1","name":"demo",
                     "attempts":[{"attemptId":1,"completed":true},
                                 {"attemptId":2,"completed":true}]}
                    """));
            // 运行中应用：SHS 也会列出未完成的事件日志，attempt.completed=false。
            // attemptId 非空，路径必须带 /1（真实 SHS 的行为）。
            server.createContext("/api/v1/applications/app-2/1/stages",
                    exchange -> respond(exchange, STAGES));
            server.createContext("/api/v1/applications/app-2", exchange -> respond(exchange, """
                    {"id":"app-2","name":"running",
                     "attempts":[{"attemptId":"1","completed":false,
                                  "lastUpdated":"2026-11-15T02:00:05Z"}]}
                    """));
            server.start();
        }

        /**
         * Spark 3.5.9 实测形状：quantiles 是数组，各字段是按下标对齐的并列数组；
         * 嵌套指标也是「子字段 → 数组」。
         */
        private static final String TASK_SUMMARY = """
                {"quantiles":[0.5,0.9],
                 "duration":[1000.0,2000.0],
                 "executorRunTime":[800.0,1600.0],
                 "jvmGcTime":[20.0,40.0],
                 "resultSize":[128.0,256.0],
                 "peakExecutionMemory":[2048.0,4096.0],
                 "memoryBytesSpilled":[0.0,0.0],
                 "diskBytesSpilled":[0.0,0.0],
                 "inputMetrics":{"bytesRead":[64.0,128.0],"recordsRead":[10.0,20.0]},
                 "outputMetrics":{"bytesWritten":[32.0,64.0],"recordsWritten":[5.0,10.0]},
                 "shuffleReadMetrics":{"readBytes":[150.0,300.0],"readRecords":[8.0,16.0]},
                 "shuffleWriteMetrics":{"writeBytes":[256.0,512.0],"writeRecords":[12.0,24.0]}}
                """;

        /** 兼容形态：以分位为 key 的对象（早期 fixture 写法），不能因为版本差异取不到数。 */
        private static final String OBJECT_TASK_SUMMARY = """
                {"0.5":{"duration":1000,"shuffleReadMetrics":{"readBytes":150,
                  "readRecords":8}},
                 "0.9":{"duration":2000,"shuffleReadMetrics":{"readBytes":300,
                  "readRecords":16}}}
                """;

        private static final String STAGES = """
                [{"stageId":1,"attemptId":0,"status":"COMPLETE","inputRecords":100,
                  "outputRecords":90,"shuffleReadBytes":1024,"shuffleWriteBytes":2048,
                  "shuffleReadRecords":10,"shuffleWriteRecords":20,
                  "memoryBytesSpilled":2048,"diskBytesSpilled":4096,
                  "peakExecutionMemory":8192,"numFailedTasks":1,"numActiveTasks":0,
                  "numCompleteTasks":10,"executorRunTime":3000,
                  "submissionTime":"2026-11-15T01:00:00Z",
                  "completionTime":"2026-11-15T01:01:00Z"},
                 {"stageId":2,"attemptId":0,"status":"COMPLETE","inputRecords":50,
                  "outputRecords":40,"shuffleReadBytes":512,"shuffleWriteBytes":256,
                  "shuffleReadRecords":5,"shuffleWriteRecords":4,
                  "memoryBytesSpilled":0,"diskBytesSpilled":0,
                  "peakExecutionMemory":1024,"numFailedTasks":0,"numActiveTasks":0,
                  "numCompleteTasks":5,"executorRunTime":1000,
                  "submissionTime":"2026-11-15T01:02:00Z",
                  "completionTime":"2026-11-15T01:03:00Z"}]
                """;

        private static final String EXECUTORS = """
                [{"id":"driver","totalGCTime":100,"maxMemory":1000,"memoryUsed":0,
                  "totalDuration":1000,"totalCores":0,"activeTasks":0,
                  "completedTasks":0,"failedTasks":0,"totalTasks":0,
                  "totalInputBytes":0,"totalShuffleRead":0,"totalShuffleWrite":0,
                  "peakMemoryMetrics":{"JVMHeapMemory":147040768}},
                 {"id":"1","totalGCTime":3500,"maxMemory":2000,"memoryUsed":0,
                  "totalDuration":2000,"totalCores":2,"activeTasks":1,
                  "completedTasks":10,"failedTasks":3,"totalTasks":13,
                  "totalInputBytes":4096,"totalShuffleRead":1024,"totalShuffleWrite":2048,
                  "peakMemoryMetrics":{"JVMHeapMemory":52428800}}]
                """;

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
