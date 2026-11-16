package com.bigdata.modules.telemetry.service.logdata.task;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.service.logdata.model.LogDataType;
import com.bigdata.modules.telemetry.service.logdata.model.LogEntry;
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.bigdata.modules.telemetry.config.properties.DsConfig;

/**
 * DolphinScheduler 任务日志后端单测。
 *
 * <p>使用本地 fake DS HTTP 服务模拟 token 校验、项目 / 任务实例查询与 {@code /log/detail} 分页；
 * 覆盖真实 DS 接口契约，但日志正文读取仍以 fake 响应为准。</p>
 */
class DsApiLogBackendTest {

    @Test
    @DisplayName("jobId 直接定位 taskInstanceId，按 skipLineNum 分页拼接")
    void jobIdPaginates() throws IOException {
        try (FakeDs ds = new FakeDs()) {
            ds.logDetail = query -> {
                String skip = queryParam(query, "skipLineNum");
                if ("0".equals(skip)) {
                    return page(2, "line-1\nline-2\n");
                }
                if ("2".equals(skip)) {
                    return page(1, "line-3\n");
                }
                return emptyLogBody();
            };

            LogQueryResult result = backend(ds, 2).fetch(
                    spec(LogDataType.TASK, "stdout", Map.of("jobId", "42")));

            assertThat(result.entries()).extracting(LogEntry::message)
                    .containsExactly("line-1", "line-2", "line-3");
            assertThat(result.truncated()).isFalse();
            assertThat(result.nextCursor()).isNull();
            assertThat(result.scan().lines()).isEqualTo(3);
            assertThat(result.entries().get(0).fields())
                    .containsEntry("taskInstanceId", "42")
                    .containsEntry("source", "ds-log-detail");
            assertThat(ds.logDetailCalls.get()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("jobName 唯一命中：带 workflowInstanceId 过滤，hostId 取 taskInstance.host")
    void jobNameResolvesAndUsesHost() throws IOException {
        try (FakeDs ds = new FakeDs()) {
            ds.taskInstances = query -> tasks(task(77, "probe_ds_log", 5, 1, "worker-1:1234"));
            ds.logDetail = query -> page(1, "probe line\n");

            LogQueryResult result = backend(ds, 10).fetch(spec(LogDataType.TASK, "stdout",
                    Map.of("jobName", "probe_ds_log", "workflowInstanceId", "5")));

            assertThat(result.entries()).singleElement().satisfies(entry -> {
                assertThat(entry.hostId()).isEqualTo("worker-1:1234");
                assertThat(entry.fields())
                        .containsEntry("workflowInstanceId", "5")
                        .containsEntry("projectCode", "1");
            });
            assertThat(ds.lastTaskInstancesQuery)
                    .contains("workflowInstanceId=5")
                    .contains("taskName=probe_ds_log");
            assertThat(ds.lastLogDetailQuery).contains("taskInstanceId=77");
        }
    }

    @Test
    @DisplayName("jobName 命中多条且缺 workflowInstanceId：AMBIGUOUS_REFERENCE，不取第一条")
    void ambiguousJobName() throws IOException {
        try (FakeDs ds = new FakeDs()) {
            ds.taskInstances = query -> tasks(
                    task(1, "probe_ds_log", 5, 1, "h1"),
                    task(2, "probe_ds_log", 6, 1, "h2"));

            assertThatThrownBy(() -> backend(ds, 10).fetch(
                    spec(LogDataType.TASK, "stdout", Map.of("jobName", "probe_ds_log"))))
                    .isInstanceOf(BackendException.class)
                    .extracting(error -> ((BackendException) error).getCode())
                    .isEqualTo(CapabilityCode.AMBIGUOUS_REFERENCE);
            assertThat(ds.logDetailCalls.get()).isZero();
        }
    }

    @Test
    @DisplayName("jobName 多条命中但给了 workflowInstanceId：唯一命中")
    void workflowInstanceIdDisambiguates() throws IOException {
        try (FakeDs ds = new FakeDs()) {
            ds.taskInstances = query -> query.contains("workflowInstanceId=5")
                    ? tasks(task(1, "probe_ds_log", 5, 1, "h1"))
                    : tasks(task(1, "probe_ds_log", 5, 1, "h1"),
                    task(2, "probe_ds_log", 6, 1, "h2"));
            ds.logDetail = query -> page(1, "line\n");

            LogQueryResult result = backend(ds, 10).fetch(spec(LogDataType.TASK, "stdout",
                    Map.of("jobName", "probe_ds_log", "workflowInstanceId", "5")));

            assertThat(result.entries()).hasSize(1);
            assertThat(ds.lastLogDetailQuery).contains("taskInstanceId=1");
        }
    }

    @Test
    @DisplayName("DS code=10008 映射为 NOT_FOUND")
    void taskInstanceNotFound() throws IOException {
        try (FakeDs ds = new FakeDs()) {
            ds.logDetail = query -> "{\"code\":10008,\"msg\":\"task instance not found\"}";

            assertThatThrownBy(() -> backend(ds, 10).fetch(
                    spec(LogDataType.TASK, "stdout", Map.of("jobId", "999"))))
                    .isInstanceOf(BackendException.class)
                    .extracting(error -> ((BackendException) error).getCode())
                    .isEqualTo(CapabilityCode.NOT_FOUND);
        }
    }

    @Test
    @DisplayName("返回 HTML 错误页：PARSE_ERROR，不把 HTML 当日志正文")
    void htmlIsRejected() throws IOException {
        try (FakeDs ds = new FakeDs()) {
            ds.html = true;

            assertThatThrownBy(() -> backend(ds, 10).fetch(
                    spec(LogDataType.TASK, "stdout", Map.of("jobId", "42"))))
                    .isInstanceOf(BackendException.class)
                    .extracting(error -> ((BackendException) error).getCode())
                    .isEqualTo(CapabilityCode.PARSE_ERROR);
        }
    }

    @Test
    @DisplayName("空日志：NO_LOG_CONTENT，不返回 0 行成功")
    void emptyLog() throws IOException {
        try (FakeDs ds = new FakeDs()) {
            assertThatThrownBy(() -> backend(ds, 10).fetch(
                    spec(LogDataType.TASK, "stdout", Map.of("jobId", "42"))))
                    .isInstanceOf(BackendException.class)
                    .extracting(error -> ((BackendException) error).getCode())
                    .isEqualTo(CapabilityCode.NO_LOG_CONTENT);
        }
    }

    @Test
    @DisplayName("超过 maxLines：truncated=true 并带 OUTPUT_TRUNCATED")
    void truncatesByMaxLines() throws IOException {
        try (FakeDs ds = new FakeDs()) {
            ds.logDetail = query -> page(3, "a\nb\nc\n");

            LogQueryResult result = backend(ds, 10, 2).fetch(
                    spec(LogDataType.TASK, "stdout", Map.of("jobId", "42")));

            assertThat(result.entries()).hasSize(2);
            assertThat(result.truncated()).isTrue();
            assertThat(result.warnings()).extracting(warning -> warning.code())
                    .contains("OUTPUT_TRUNCATED");
        }
    }

    @Test
    @DisplayName("log.service 不能用 ds-api：LOG_DATA_TYPE_MISMATCH")
    void serviceLogsRejected() throws IOException {
        try (FakeDs ds = new FakeDs()) {
            BackendCapability capability = backend(ds, 10).evaluate(
                    spec(LogDataType.SERVICE, "stdout", Map.of("jobId", "42")));

            assertThat(capability.isAvailable()).isFalse();
            assertThat(capability.code()).isEqualTo(CapabilityCode.LOG_DATA_TYPE_MISMATCH);
        }
    }

    @Test
    @DisplayName("log.task 不支持 slowquery：STREAM_NOT_SUPPORTED")
    void slowQueryRejected() throws IOException {
        try (FakeDs ds = new FakeDs()) {
            BackendCapability capability = backend(ds, 10).evaluate(
                    spec(LogDataType.TASK, "slowquery", Map.of("jobId", "42")));

            assertThat(capability.isAvailable()).isFalse();
            assertThat(capability.code()).isEqualTo(CapabilityCode.STREAM_NOT_SUPPORTED);
        }
    }

    @Test
    @DisplayName("时间范围与 cursor：明确返回不支持")
    void timeRangeAndCursorRejected() throws IOException {
        try (FakeDs ds = new FakeDs()) {
            DsApiLogBackend backend = backend(ds, 10);
            BackendCapability range = backend.evaluate(spec(LogDataType.TASK, "stdout",
                    Map.of("jobId", "42"), new TimeRange(
                            Instant.parse("2026-11-15T01:00:00Z"),
                            Instant.parse("2026-11-15T02:00:00Z")), null));
            BackendCapability cursor = backend.evaluate(spec(LogDataType.TASK, "stdout",
                    Map.of("jobId", "42"), null, "cursor-1"));

            assertThat(range.code()).isEqualTo(CapabilityCode.TIME_FILTER_UNSUPPORTED);
            assertThat(cursor.code()).isEqualTo(CapabilityCode.INVALID_ARGUMENT);
        }
    }

    @Test
    @DisplayName("缺 base-url / 访问令牌：NOT_CONFIGURED + requirements")
    void missingConfiguration() {
        DsApiLogBackend noBaseUrl = new DsApiLogBackend("ds-api", new DsConfig(),
                "token", new ObjectMapper());
        BackendCapability baseUrl = noBaseUrl.evaluate(
                spec(LogDataType.TASK, "stdout", Map.of("jobId", "42")));

        DsConfig configured = new DsConfig();
        configured.setBaseUrl("http://127.0.0.1:1/dolphinscheduler");
        DsApiLogBackend noCredential = new DsApiLogBackend("ds-api", configured,
                "", new ObjectMapper());
        BackendCapability credential = noCredential.evaluate(
                spec(LogDataType.TASK, "stdout", Map.of("jobId", "42")));

        assertThat(baseUrl.code()).isEqualTo(CapabilityCode.NOT_CONFIGURED);
        assertThat(baseUrl.requirements()).isNotEmpty();
        assertThat(credential.code()).isEqualTo(CapabilityCode.NOT_CONFIGURED);
        assertThat(credential.reason()).contains("令牌");
    }

    @Test
    @DisplayName("缺 jobId / jobName：INVALID_ARGUMENT")
    void missingReference() throws IOException {
        try (FakeDs ds = new FakeDs()) {
            BackendCapability capability = backend(ds, 10).evaluate(
                    spec(LogDataType.TASK, "stdout", Map.of()));

            assertThat(capability.code()).isEqualTo(CapabilityCode.INVALID_ARGUMENT);
            assertThat(capability.reason()).contains("jobId").contains("jobName");
        }
    }

    @Test
    @DisplayName("jobId 也能通过项目扫描拿到真实 host 与 workflowInstanceId")
    void jobIdResolvesHostByScan() throws IOException {
        try (FakeDs ds = new FakeDs()) {
            ds.taskInstances = query -> tasks(task(42, "probe_ds_log", 9, 1, "worker-9:1234"));
            ds.logDetail = query -> page(1, "line\n");

            LogQueryResult result = backend(ds, 10).fetch(
                    spec(LogDataType.TASK, "stdout", Map.of("jobId", "42")));

            assertThat(result.entries()).singleElement().satisfies(entry -> {
                assertThat(entry.hostId()).isEqualTo("worker-9:1234");
                assertThat(entry.fields())
                        .containsEntry("workflowInstanceId", "9")
                        .containsEntry("projectCode", "1");
            });
            assertThat(result.warnings()).extracting(warning -> warning.code())
                    .doesNotContain("HOST_FALLBACK");
        }
    }

    @Test
    @DisplayName("仅给 jobId 且项目扫描未命中时 host 回退 cluster，并带 HOST_FALLBACK 告警")
    void hostFallsBackToCluster() throws IOException {
        try (FakeDs ds = new FakeDs()) {
            ds.logDetail = query -> page(1, "line\n");

            LogQueryResult result = backend(ds, 10).fetch(
                    spec(LogDataType.TASK, "stdout", Map.of("jobId", "42")));

            assertThat(result.entries()).singleElement()
                    .satisfies(entry -> assertThat(entry.hostId()).isEqualTo("cluster"));
            assertThat(result.warnings()).extracting(warning -> warning.code())
                    .contains("HOST_FALLBACK");
        }
    }

    @Test
    @DisplayName("令牌无效 401：UPSTREAM_UNAUTHORIZED，不把令牌写进消息")
    void tokenUnauthorized() throws IOException {
        try (FakeDs ds = new FakeDs()) {
            ds.unauthorized = true;

            assertThatThrownBy(() -> backend(ds, 10).fetch(
                    spec(LogDataType.TASK, "stdout", Map.of("jobId", "42"))))
                    .isInstanceOf(BackendException.class)
                    .satisfies(error -> {
                        BackendException backend = (BackendException) error;
                        assertThat(backend.getCode())
                                .isEqualTo(CapabilityCode.UPSTREAM_UNAUTHORIZED);
                        assertThat(backend.getMessage()).doesNotContain("test-token");
                    });
        }
    }

    // ------------------------------------------------------------------ 辅助

    private DsApiLogBackend backend(FakeDs ds, int pageSize) {
        return backend(ds, pageSize, 100);
    }

    private DsApiLogBackend backend(FakeDs ds, int pageSize, int maxLines) {
        DsConfig config = new DsConfig();
        config.setBaseUrl(ds.baseUrl());
        config.getLimits().setTimeoutMs(3000);
        config.getLimits().setMaxBytes(65536);
        config.getLimits().setMaxLines(maxLines);
        config.getLimits().setPageSize(pageSize);
        config.getLimits().setMaxPages(10);
        return new DsApiLogBackend("ds-api", config, "test-token", new ObjectMapper());
    }

    private LogQuerySpec spec(LogDataType dataType, String stream, Map<String, String> refs) {
        return spec(dataType, stream, refs, null, null);
    }

    private LogQuerySpec spec(LogDataType dataType, String stream, Map<String, String> refs,
                              TimeRange range, String cursor) {
        return new LogQuerySpec(new Target("ds", "ds.worker", "cluster"), dataType, stream, range,
                new LogFilter(List.of(), List.of(), LogFilter.MatchMode.ALL, false, false),
                cursor, 10, 65536, refs, null);
    }

    private static String page(int lineNum, String message) {
        return "{\"code\":0,\"msg\":\"success\",\"data\":{\"lineNum\":" + lineNum
                + ",\"message\":\"" + message.replace("\n", "\\n") + "\"}}";
    }

    private static String emptyLogBody() {
        return page(0, "");
    }

    private static String tasks(String... items) {
        return "{\"code\":0,\"data\":{\"totalList\":[" + String.join(",", items)
                + "],\"totalPage\":1}}";
    }

    private static String task(long id, String name, long workflowInstanceId, long projectCode,
                               String host) {
        return String.format("{\"id\":%d,\"name\":\"%s\",\"workflowInstanceId\":%d,"
                        + "\"projectCode\":%d,\"host\":\"%s\"}",
                id, name, workflowInstanceId, projectCode, host);
    }

    private static String queryParam(String query, String name) {
        if (query == null) {
            return "";
        }
        for (String part : query.split("&")) {
            int index = part.indexOf('=');
            if (index > 0 && part.substring(0, index).equals(name)) {
                return URLDecoder.decode(part.substring(index + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static final class FakeDs implements AutoCloseable {

        private final HttpServer server;
        private final AtomicInteger logDetailCalls = new AtomicInteger();
        private volatile boolean unauthorized;
        private volatile String expectedToken = "test-token";
        private volatile String lastToken;
        private volatile String projectsBody = "{\"code\":0,\"data\":{\"totalList\":"
                + "[{\"code\":1,\"name\":\"probe_project\"}],\"totalPage\":1}}";
        private volatile Function<String, String> taskInstances = query -> tasks();
        private volatile Function<String, String> logDetail = query -> emptyLogBody();
        private volatile boolean html;
        private volatile String lastTaskInstancesQuery;
        private volatile String lastLogDetailQuery;

        private FakeDs() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/dolphinscheduler/projects", exchange -> {
                lastToken = exchange.getRequestHeaders().getFirst("token");
                if (unauthorized || !expectedToken.equals(lastToken)) {
                    respond(exchange, 401, "application/json",
                            "{\"code\":10001,\"msg\":\"token invalid\"}");
                    return;
                }
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/task-instances")) {
                    lastTaskInstancesQuery = exchange.getRequestURI().getQuery();
                    respond(exchange, 200, "application/json",
                            taskInstances.apply(lastTaskInstancesQuery));
                } else {
                    respond(exchange, 200, "application/json", projectsBody);
                }
            });
            server.createContext("/dolphinscheduler/log/detail", exchange -> {
                lastToken = exchange.getRequestHeaders().getFirst("token");
                if (unauthorized || !expectedToken.equals(lastToken)) {
                    respond(exchange, 401, "application/json",
                            "{\"code\":10001,\"msg\":\"token invalid\"}");
                    return;
                }
                logDetailCalls.incrementAndGet();
                lastLogDetailQuery = exchange.getRequestURI().getQuery();
                respond(exchange, 200, html ? "text/html" : "application/json",
                        html ? "<html><body>login</body></html>"
                                : logDetail.apply(lastLogDetailQuery));
            });
            server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/dolphinscheduler";
        }

        private static void respond(HttpExchange exchange, int status, String contentType,
                                    String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
