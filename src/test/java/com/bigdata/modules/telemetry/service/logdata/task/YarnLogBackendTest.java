package com.bigdata.modules.telemetry.service.logdata.task;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.http.EngineRestClient;
import com.bigdata.modules.telemetry.service.logdata.model.LogDataType;
import com.bigdata.modules.telemetry.service.logdata.model.LogFilter;
import com.bigdata.modules.telemetry.common.model.Target;
import com.bigdata.modules.telemetry.common.model.TimeRange;
import com.bigdata.modules.telemetry.common.model.BackendCapability;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.bigdata.modules.telemetry.service.logdata.model.LogQueryResult;
import com.bigdata.modules.telemetry.service.logdata.model.LogQuerySpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.bigdata.modules.telemetry.config.properties.YarnConfig;

class YarnLogBackendTest {

    private static final String APP = "application_1794736400105_0002";
    private static final String NM_HOST = "0ceb522a40ec";
    private static final String CONTAINER_1 = "container_1794736400105_0002_01_000001";
    private static final String CONTAINER_2 = "container_1794736400105_0002_01_000002";

    @Test
    @DisplayName("给 taskId 时直接拼 yarn logs 命令，按容器分段并保留 containerId / NM host / 来源")
    void usesTaskIdDirectly() {
        FakeRunner runner = new FakeRunner();
        runner.output = new YarnLogBackend.CommandOutput(0,
                aggregated(CONTAINER_1, NM_HOST, "stdout",
                        " INFO starting\n ERROR checkpoint failed\n INFO done\n"),
                "", false, 512);

        LogQueryResult result = backend(runner).fetch(spec(LogDataType.TASK, "stdout",
                Map.of("taskId", APP), null, 10, List.of("ERROR")));

        assertThat(runner.command).containsExactly("yarn", "logs", "-applicationId",
                APP, "-log_files", "stdout");
        assertThat(result.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.message()).contains("checkpoint failed");
            assertThat(entry.hostId()).isEqualTo(NM_HOST);
            assertThat(entry.fields())
                    .containsEntry("containerId", CONTAINER_1)
                    .containsEntry("nmHost", NM_HOST)
                    .containsEntry("source", "aggregated")
                    .containsEntry("aggregationType", "AGGREGATED");
        });
        assertThat(result.scan().lines()).isEqualTo(3);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    @DisplayName("多容器聚合：每个容器的日志各自带 containerId，不合并成一段")
    void parsesMultipleContainers() {
        FakeRunner runner = new FakeRunner();
        runner.output = new YarnLogBackend.CommandOutput(0,
                aggregated(CONTAINER_1, NM_HOST, "stdout", "map 0 finished\n")
                        + aggregated(CONTAINER_2, NM_HOST, "stdout", "map 1 finished\n"),
                "", false, 1024);

        LogQueryResult result = backend(runner).fetch(spec(LogDataType.TASK, "stdout",
                Map.of("taskId", APP), null, 10, List.of()));

        assertThat(result.entries()).hasSize(2);
        assertThat(result.entries()).extracting(e -> e.fields().get("containerId"))
                .containsExactly(CONTAINER_1, CONTAINER_2);
        assertThat(result.scan().files()).isEqualTo(2);
    }

    @Test
    @DisplayName("只给 jobName 时通过 RM REST 解析 applicationId，取最新一个")
    void resolvesJobNameThroughResourceManager() throws IOException {
        try (FakeRm rm = new FakeRm("{\"apps\":{\"app\":["
                + "{\"id\":\"application_old\",\"name\":\"daily_etl\",\"startedTime\":100},"
                + "{\"id\":\"application_new\",\"name\":\"daily_etl\",\"startedTime\":200},"
                + "{\"id\":\"application_other\",\"name\":\"other\",\"startedTime\":300}]}}",
                "{}", "{\"app\":{\"state\":\"RUNNING\"}}")) {
            FakeRunner runner = new FakeRunner();
            runner.output = new YarnLogBackend.CommandOutput(0,
                    aggregated(CONTAINER_1, NM_HOST, "stdout", "ok\n"), "", false, 16);

            backend(runner, rm.client()).fetch(spec(LogDataType.TASK, "stdout",
                    Map.of("jobName", "daily_etl"), null, 10, List.of()));

            assertThat(runner.command).containsSubsequence("-applicationId", "application_new");
            assertThat(rm.calls.get()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("taskId 与 jobName 同时给时以 taskId 为准，不调用 RM")
    void taskIdWinsOverJobName() throws IOException {
        try (FakeRm rm = new FakeRm("{\"apps\":{\"app\":[]}}", "{}", "{}")) {
            FakeRunner runner = new FakeRunner();
            runner.output = new YarnLogBackend.CommandOutput(0,
                    aggregated("container_direct", NM_HOST, "stdout", "ok\n"), "", false, 16);

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
                spec(LogDataType.TASK, "stdout", Map.of("taskId", APP),
                        new TimeRange(Instant.parse("2026-11-15T01:00:00Z"),
                                Instant.parse("2026-11-15T02:00:00Z")), 10, List.of()));

        assertThat(capability.isAvailable()).isFalse();
        assertThat(capability.code()).isEqualTo(CapabilityCode.TIME_FILTER_UNSUPPORTED);
    }

    @Test
    @DisplayName("log.service 不能用 yarn：LOG_DATA_TYPE_MISMATCH")
    void serviceLogsRejected() {
        BackendCapability capability = backend(new FakeRunner()).evaluate(
                spec(LogDataType.SERVICE, "stdout", Map.of("taskId", APP),
                        null, 10, List.of()));

        assertThat(capability.isAvailable()).isFalse();
        assertThat(capability.code()).isEqualTo(CapabilityCode.LOG_DATA_TYPE_MISMATCH);
    }

    @Test
    @DisplayName("log.task 不支持 slowquery：STREAM_NOT_SUPPORTED")
    void slowQueryUnsupported() {
        BackendCapability capability = backend(new FakeRunner()).evaluate(
                spec(LogDataType.TASK, "slowquery", Map.of("taskId", APP),
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
    @DisplayName("standalone / local 的 taskId：明确不可用并说明 SHS 只有事件日志 zip")
    void standaloneSparkTaskIdUnavailable() {
        BackendCapability capability = backend(new FakeRunner()).evaluate(
                spec(LogDataType.TASK, "stdout", Map.of("taskId", "local-1794733783888"),
                        null, 10, List.of()));

        assertThat(capability.isAvailable()).isFalse();
        assertThat(capability.code()).isEqualTo(CapabilityCode.LOG_SOURCE_UNAVAILABLE);
        assertThat(capability.reason()).contains("standalone").contains("zip");
        assertThat(capability.requirements()).isNotEmpty();
    }

    @Test
    @DisplayName("CLI 超时映射为 UPSTREAM_TIMEOUT")
    void timeoutMapsToUpstreamTimeout() {
        FakeRunner runner = new FakeRunner();
        runner.timeout = new YarnLogBackend.CommandTimeoutException("timeout");

        assertThatThrownBy(() -> backend(runner).fetch(spec(LogDataType.TASK, "stdout",
                Map.of("taskId", APP), null, 10, List.of())))
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
        runner.output = new YarnLogBackend.CommandOutput(0,
                aggregated(CONTAINER_1, NM_HOST, "stdout", "a\nb\nc\n"), "", false, 6);

        LogQueryResult result = backend(runner).fetch(spec(LogDataType.TASK, "stdout",
                Map.of("taskId", APP), null, 2, List.of()));

        assertThat(result.entries()).hasSize(2);
        assertThat(result.truncated()).isTrue();
        assertThat(result.nextCursor()).isNull();
        assertThat(result.warnings()).extracting(w -> w.code()).contains("OUTPUT_TRUNCATED");
        assertThat(result.scan().matched()).isEqualTo(2);
    }

    @Test
    @DisplayName("聚合未完成时回退 node-local，样本带 containerId / nmHost / source，并声明平台限制")
    void fallsBackToNodeLocalWhileAggregationIncomplete(@TempDir Path root) throws IOException {
        writeLog(root, APP, CONTAINER_1, "stdout", "2026-11-15 INFO map task running\n");
        writeLog(root, APP, CONTAINER_2, "stdout", "2026-11-15 INFO second container\n");

        try (FakeRm rm = runningRm();
             FakeNm nm = new FakeNm(containers(CONTAINER_1, CONTAINER_2), 200)) {
            FakeRunner runner = aggregationNotComplete();

            LogQueryResult result = backend(runner, rm.client(), root, nm.baseUrl())
                    .fetch(spec(LogDataType.TASK, "stdout", Map.of("taskId", APP),
                            null, 10, List.of()));

            assertThat(result.entries()).hasSize(2);
            assertThat(result.entries()).allSatisfy(entry -> assertThat(entry.fields())
                    .containsEntry("source", "node-local")
                    .containsEntry("nmHost", NM_HOST)
                    .containsKey("containerId")
                    .containsEntry("containerState", "RUNNING"));
            assertThat(result.scan().files()).isEqualTo(2);
            assertThat(result.warnings()).anySatisfy(warning -> {
                assertThat(warning.code()).isEqualTo("LOG_AGGREGATION_NOT_COMPLETE");
                assertThat(warning.message())
                        .contains("聚合完成前只能走 node-local")
                        .contains("logAggregationStatus=NOT_START");
            });
        }
    }

    @Test
    @DisplayName("node-local 目录未挂载：UNAVAILABLE + reason，不用空字符串兜底")
    void nodeLocalNotMounted(@TempDir Path root) throws IOException {
        try (FakeRm rm = runningRm();
             FakeNm nm = new FakeNm(containers(CONTAINER_1), 200)) {
            FakeRunner runner = aggregationNotComplete();

            assertThatThrownBy(() -> backend(runner, rm.client(), root.resolve("not-mounted"),
                    nm.baseUrl()).fetch(spec(LogDataType.TASK, "stdout", Map.of("taskId", APP),
                    null, 10, List.of())))
                    .isInstanceOf(BackendException.class)
                    .satisfies(e -> {
                        BackendException failure = (BackendException) e;
                        assertThat(failure.getCode()).isEqualTo(CapabilityCode.LOG_SOURCE_UNAVAILABLE);
                        assertThat(failure.getMessage())
                                .contains("未挂载")
                                .contains("平台限制");
                    });
        }
    }

    @Test
    @DisplayName("部分容器无本地文件：可读容器照常返回，缺失容器单独告警并带 containerId / nmHost")
    void partialNodeLocalWarnsPerContainer(@TempDir Path root) throws IOException {
        writeLog(root, APP, CONTAINER_1, "stdout", "only container one\n");

        try (FakeRm rm = runningRm();
             FakeNm nm = new FakeNm(containers(CONTAINER_1, CONTAINER_2), 200)) {
            LogQueryResult result = backend(aggregationNotComplete(), rm.client(), root,
                    nm.baseUrl()).fetch(spec(LogDataType.TASK, "stdout", Map.of("taskId", APP),
                    null, 10, List.of()));

            assertThat(result.entries()).singleElement().satisfies(entry -> {
                assertThat(entry.fields().get("containerId")).isEqualTo(CONTAINER_1);
                assertThat(entry.fields().get("source")).isEqualTo("node-local");
            });
            assertThat(result.warnings()).anySatisfy(warning -> {
                assertThat(warning.code()).isEqualTo("NODE_LOCAL_UNREADABLE");
                assertThat(warning.message())
                        .contains(CONTAINER_2)
                        .contains("nmHost=" + NM_HOST);
            });
        }
    }

    @Test
    @DisplayName("NM 不可达：给出 NM_UNREACHABLE 告警，最终仍返回 UNAVAILABLE")
    void nmUnreachable(@TempDir Path root) throws IOException {
        try (FakeRm rm = runningRm();
             FakeNm nm = new FakeNm("{\"RemoteException\":{}}", 500)) {
            assertThatThrownBy(() -> backend(aggregationNotComplete(), rm.client(), root,
                    nm.baseUrl()).fetch(spec(LogDataType.TASK, "stdout", Map.of("taskId", APP),
                    null, 10, List.of())))
                    .isInstanceOf(BackendException.class)
                    .extracting(e -> ((BackendException) e).getCode())
                    .isEqualTo(CapabilityCode.LOG_SOURCE_UNAVAILABLE);
        }
    }

    @Test
    @DisplayName("聚合已完成但容器日志为空：NO_LOG_CONTENT，不返回空数组假装成功")
    void aggregatedEmptyIsNoLogContent(@TempDir Path root) throws IOException {
        try (FakeRm rm = new FakeRm("{\"apps\":{}}", nodes(), "{\"app\":{\"state\":\"FINISHED\","
                + "\"logAggregationStatus\":\"SUCCEEDED\"}}");
             FakeNm nm = new FakeNm(containers(CONTAINER_1), 200)) {
            FakeRunner runner = new FakeRunner();
            runner.output = new YarnLogBackend.CommandOutput(0,
                    aggregated(CONTAINER_1, NM_HOST, "stdout", ""), "", false, 128);

            assertThatThrownBy(() -> backend(runner, rm.client(), root, nm.baseUrl())
                    .fetch(spec(LogDataType.TASK, "stdout", Map.of("taskId", APP),
                            null, 10, List.of())))
                    .isInstanceOf(BackendException.class)
                    .extracting(e -> ((BackendException) e).getCode())
                    .isEqualTo(CapabilityCode.NO_LOG_CONTENT);
        }
    }

    @Test
    @DisplayName("jobName 解析不到：NOT_FOUND，不返回空数组冒充成功")
    void jobNameNotFound() throws IOException {
        try (FakeRm rm = new FakeRm("{\"apps\":{\"app\":[]}}", "{}", "{}")) {
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

    private YarnLogBackend backend(FakeRunner runner, EngineRestClient rmClient, Path nodeLogRoot,
                                   String nmBaseUrl) {
        YarnConfig config = new YarnConfig();
        config.setCommand("yarn");
        config.getLimits().setTimeoutMs(1000);
        config.getLimits().setMaxBytes(65536);
        config.setNodeLogRoot(nodeLogRoot.toString());
        config.setNmBaseUrl(nmBaseUrl == null ? "" : nmBaseUrl);
        return new YarnLogBackend("yarn", config, rmClient, runner);
    }

    private LogQuerySpec spec(LogDataType dataType, String stream, Map<String, String> refs,
                              TimeRange range, int limit, List<String> keywords) {
        return new LogQuerySpec(new Target("spark", "spark.driver", "cluster"), dataType, stream,
                range, new LogFilter(keywords, List.of(), LogFilter.MatchMode.ALL, false, false),
                null, limit, 1024 * 1024, refs, null);
    }

    private static FakeRunner aggregationNotComplete() {
        FakeRunner runner = new FakeRunner();
        runner.output = new YarnLogBackend.CommandOutput(255, "",
                "File /opt/dataforge/yarn/logs/test_tenant/logs-tfile/" + APP + " does not exist\n"
                        + "Can not find the logs for the application: " + APP
                        + " with the appOwner: test_tenant\n", false, 0);
        return runner;
    }

    private static FakeRm runningRm() throws IOException {
        return new FakeRm("{\"apps\":{}}", nodes(),
                "{\"app\":{\"state\":\"RUNNING\",\"logAggregationStatus\":\"NOT_START\"}}");
    }

    private static String nodes() {
        return "{\"nodes\":{\"node\":[{\"id\":\"" + NM_HOST + ":8043\","
                + "\"nodeHostName\":\"" + NM_HOST + "\","
                + "\"nodeHTTPAddress\":\"" + NM_HOST + ":8042\",\"state\":\"RUNNING\"}]}}";
    }

    private static String containers(String... containerIds) {
        StringBuilder body = new StringBuilder("{\"containers\":{\"container\":[");
        for (int i = 0; i < containerIds.length; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append("{\"id\":\"").append(containerIds[i]).append("\",\"state\":\"RUNNING\",")
                    .append("\"exitCode\":-1000,\"nodeId\":\"").append(NM_HOST).append(":8043\",")
                    .append("\"containerLogFiles\":[\"stdout\",\"stderr\",\"syslog\"]}");
        }
        return body.append("]}}").toString();
    }

    /** 复刻真实 `yarn logs` 输出格式（含 Container 头、LogAggregationType、LogLength）。 */
    private static String aggregated(String containerId, String nmHost, String stream, String content) {
        StringBuilder body = new StringBuilder();
        body.append("Container: ").append(containerId).append(" on ").append(nmHost).append('\n');
        body.append("LogAggregationType: AGGREGATED\n");
        body.append("======================================================================\n");
        body.append("LogType:").append(stream).append('\n');
        body.append("LogLastModifiedTime:Sun Nov 15 15:42:02 +0000 2026\n");
        body.append("LogLength:").append(content.getBytes(StandardCharsets.UTF_8).length).append('\n');
        body.append("LogContents:\n").append(content);
        body.append("End of LogType:").append(stream).append('\n');
        body.append("***********************************************************************\n");
        return body.toString();
    }

    private static void writeLog(Path root, String appId, String containerId, String stream,
                                 String content) throws IOException {
        Path file = root.resolve(appId).resolve(containerId).resolve(stream);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
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

        private FakeRm(String appsBody, String nodesBody, String appDetailBody) throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/ws/v1/cluster", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if (path.startsWith("/ws/v1/cluster/nodes")) {
                    respond(exchange, 200, nodesBody);
                } else if (path.startsWith("/ws/v1/cluster/apps/")) {
                    respond(exchange, 200, appDetailBody);
                } else {
                    calls.incrementAndGet();
                    respond(exchange, 200, appsBody);
                }
            });
            server.start();
        }

        private EngineRestClient client() {
            return new EngineRestClient("http://127.0.0.1:" + server.getAddress().getPort(),
                    null, Duration.ofSeconds(2), 1024 * 1024, new ObjectMapper());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final class FakeNm implements AutoCloseable {

        private final HttpServer server;

        private FakeNm(String body, int status) throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/ws/v1/node/containers",
                    exchange -> respond(exchange, status, body));
            server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
