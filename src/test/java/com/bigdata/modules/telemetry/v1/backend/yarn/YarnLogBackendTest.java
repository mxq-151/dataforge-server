package com.bigdata.modules.telemetry.v1.backend.yarn;

import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.v1.backend.engine.EngineRestClient;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties.YarnConfig;
import com.bigdata.modules.telemetry.v1.model.LogDataType;
import com.bigdata.modules.telemetry.v1.model.LogFilter;
import com.bigdata.modules.telemetry.v1.model.Target;
import com.bigdata.modules.telemetry.v1.model.TimeRange;
import com.bigdata.modules.telemetry.v1.spi.BackendCapability;
import com.bigdata.modules.telemetry.v1.spi.BackendException;
import com.bigdata.modules.telemetry.v1.spi.LogQueryResult;
import com.bigdata.modules.telemetry.v1.spi.LogQuerySpec;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YarnLogBackendTest {

    @Test
    @DisplayName("给 taskId 时直接拼 yarn logs 命令，不访问 RM")
    void usesTaskIdDirectly() {
        FakeRunner runner = new FakeRunner();
        runner.output = new YarnLogBackend.CommandOutput(0,
                " INFO starting\n ERROR checkpoint failed\n INFO done\n", "", false, 51);

        LogQueryResult result = backend(runner).fetch(spec(LogDataType.TASK, "stdout",
                Map.of("taskId", "application_1"), null, 10,
                List.of("ERROR")));

        assertThat(runner.command).containsExactly("yarn", "logs", "-applicationId",
                "application_1", "-log_files", "stdout");
        assertThat(result.entries()).singleElement()
                .satisfies(entry -> assertThat(entry.message()).contains("checkpoint failed"));
        assertThat(result.scan().lines()).isEqualTo(3);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    @DisplayName("只给 jobName 时通过 RM REST 解析 applicationId，取最新一个")
    void resolvesJobNameThroughResourceManager() throws IOException {
        try (FakeRm rm = new FakeRm("{\"apps\":{\"app\":["
                + "{\"id\":\"application_old\",\"name\":\"daily_etl\",\"startedTime\":100},"
                + "{\"id\":\"application_new\",\"name\":\"daily_etl\",\"startedTime\":200},"
                + "{\"id\":\"application_other\",\"name\":\"other\",\"startedTime\":300}]}}")) {
            FakeRunner runner = new FakeRunner();

            backend(runner, rm.client()).fetch(spec(LogDataType.TASK, "stdout",
                    Map.of("jobName", "daily_etl"), null, 10, List.of()));

            assertThat(runner.command).containsSubsequence("-applicationId", "application_new");
            assertThat(rm.calls.get()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("taskId 与 jobName 同时给时以 taskId 为准，不调用 RM")
    void taskIdWinsOverJobName() throws IOException {
        try (FakeRm rm = new FakeRm("{\"apps\":{\"app\":[]}}")) {
            FakeRunner runner = new FakeRunner();

            backend(runner, rm.client()).fetch(spec(LogDataType.TASK, "stdout",
                    Map.of("taskId", "application_direct", "jobName", "daily_etl"),
                    null, 10, List.of()));

            assertThat(runner.command).containsSubsequence("-applicationId", "application_direct");
            assertThat(rm.calls.get()).isZero();
        }
    }

    @Test
    @DisplayName("时间范围不支持：明确返回 TIME_FILTER_UNSUPPORTED")
    void timeRangeUnsupported() {
        BackendCapability capability = backend(new FakeRunner()).evaluate(
                spec(LogDataType.TASK, "stdout", Map.of("taskId", "application_1"),
                        new TimeRange(Instant.parse("2026-11-15T01:00:00Z"),
                                Instant.parse("2026-11-15T02:00:00Z")), 10, List.of()));

        assertThat(capability.isAvailable()).isFalse();
        assertThat(capability.code()).isEqualTo(CapabilityCode.TIME_FILTER_UNSUPPORTED);
    }

    @Test
    @DisplayName("log.service 不能用 yarn：LOG_DATA_TYPE_MISMATCH")
    void serviceLogsRejected() {
        BackendCapability capability = backend(new FakeRunner()).evaluate(
                spec(LogDataType.SERVICE, "stdout", Map.of("taskId", "application_1"),
                        null, 10, List.of()));

        assertThat(capability.isAvailable()).isFalse();
        assertThat(capability.code()).isEqualTo(CapabilityCode.LOG_DATA_TYPE_MISMATCH);
    }

    @Test
    @DisplayName("log.task 不支持 slowquery：STREAM_NOT_SUPPORTED")
    void slowQueryUnsupported() {
        BackendCapability capability = backend(new FakeRunner()).evaluate(
                spec(LogDataType.TASK, "slowquery", Map.of("taskId", "application_1"),
                        null, 10, List.of()));

        assertThat(capability.isAvailable()).isFalse();
        assertThat(capability.code()).isEqualTo(CapabilityCode.STREAM_NOT_SUPPORTED);
    }

    @Test
    @DisplayName("taskId / jobName 都为空：INVALID_ARGUMENT")
    void missingTaskReference() {
        BackendCapability capability = backend(new FakeRunner()).evaluate(
                spec(LogDataType.TASK, "stdout", Map.of(), null, 10, List.of()));

        assertThat(capability.isAvailable()).isFalse();
        assertThat(capability.code()).isEqualTo(CapabilityCode.INVALID_ARGUMENT);
        assertThat(capability.reason()).contains("taskId").contains("jobName");
    }

    @Test
    @DisplayName("CLI 超时映射为 UPSTREAM_TIMEOUT")
    void timeoutMapsToUpstreamTimeout() {
        FakeRunner runner = new FakeRunner();
        runner.timeout = new YarnLogBackend.CommandTimeoutException("timeout");

        assertThatThrownBy(() -> backend(runner).fetch(spec(LogDataType.TASK, "stdout",
                Map.of("taskId", "application_1"), null, 10, List.of())))
                .isInstanceOf(BackendException.class)
                .extracting(e -> ((BackendException) e).getCode())
                .isEqualTo(CapabilityCode.UPSTREAM_TIMEOUT);
    }

    @Test
    @DisplayName("CLI 非零退出按 stderr 映射：应用不存在 → NOT_FOUND")
    void cliFailureMapsByStderr() {
        FakeRunner runner = new FakeRunner();
        runner.output = new YarnLogBackend.CommandOutput(1, "",
                "org.apache.hadoop.yarn.exceptions.ApplicationNotFoundException: app", false, 0);

        assertThatThrownBy(() -> backend(runner).fetch(spec(LogDataType.TASK, "stdout",
                Map.of("taskId", "application_404"), null, 10, List.of())))
                .isInstanceOf(BackendException.class)
                .extracting(e -> ((BackendException) e).getCode())
                .isEqualTo(CapabilityCode.NOT_FOUND);
    }

    @Test
    @DisplayName("超过 limit 截断并给出 OUTPUT_TRUNCATED 告警")
    void truncatesByLimit() {
        FakeRunner runner = new FakeRunner();
        runner.output = new YarnLogBackend.CommandOutput(0, "a\nb\nc\n", "", false, 6);

        LogQueryResult result = backend(runner).fetch(spec(LogDataType.TASK, "stdout",
                Map.of("taskId", "application_1"), null, 2, List.of()));

        assertThat(result.entries()).hasSize(2);
        assertThat(result.truncated()).isTrue();
        assertThat(result.nextCursor()).isNull();
        assertThat(result.warnings()).extracting(w -> w.code()).contains("OUTPUT_TRUNCATED");
        assertThat(result.scan().matched()).isEqualTo(2);
    }

    @Test
    @DisplayName("jobName 解析不到：NOT_FOUND，不返回空数组冒充成功")
    void jobNameNotFound() throws IOException {
        try (FakeRm rm = new FakeRm("{\"apps\":{\"app\":[]}}")) {
            assertThatThrownBy(() -> backend(new FakeRunner(), rm.client()).fetch(
                    spec(LogDataType.TASK, "stdout", Map.of("jobName", "missing"),
                            null, 10, List.of())))
                    .isInstanceOf(BackendException.class)
                    .extracting(e -> ((BackendException) e).getCode())
                    .isEqualTo(CapabilityCode.NOT_FOUND);
        }
    }

    // ------------------------------------------------------------------ 辅助

    private YarnLogBackend backend(FakeRunner runner) {
        return backend(runner, null);
    }

    private YarnLogBackend backend(FakeRunner runner, EngineRestClient rmClient) {
        YarnConfig config = new YarnConfig();
        config.setCommand("yarn");
        config.getLimits().setTimeoutMs(1000);
        config.getLimits().setMaxBytes(65536);
        return new YarnLogBackend("yarn", config, rmClient, runner);
    }

    private LogQuerySpec spec(LogDataType dataType, String stream, Map<String, String> refs,
                              TimeRange range, int limit, List<String> keywords) {
        return new LogQuerySpec(new Target("spark", "spark.driver", "cluster"), dataType, stream,
                range, new LogFilter(keywords, List.of(), LogFilter.MatchMode.ALL, false, false),
                null, limit, 1024 * 1024, refs, null);
    }

    private static final class FakeRunner implements YarnLogBackend.CommandRunner {

        private List<String> command;
        private YarnLogBackend.CommandOutput output =
                new YarnLogBackend.CommandOutput(0, "", "", false, 0);
        private YarnLogBackend.CommandTimeoutException timeout;

        @Override
        public YarnLogBackend.CommandOutput run(List<String> command, Duration timeout, long maxBytes)
                throws YarnLogBackend.CommandTimeoutException {
            this.command = command;
            if (this.timeout != null) {
                throw this.timeout;
            }
            return output;
        }
    }

    private static final class FakeRm implements AutoCloseable {

        private final HttpServer server;
        private final AtomicInteger calls = new AtomicInteger();

        private FakeRm(String body) throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/ws/v1/cluster/apps", exchange -> {
                calls.incrementAndGet();
                respond(exchange, body);
            });
            server.start();
        }

        private EngineRestClient client() {
            return new EngineRestClient("http://127.0.0.1:" + server.getAddress().getPort(),
                    null, Duration.ofSeconds(2), 1024 * 1024, new ObjectMapper());
        }

        private static void respond(HttpExchange exchange, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
