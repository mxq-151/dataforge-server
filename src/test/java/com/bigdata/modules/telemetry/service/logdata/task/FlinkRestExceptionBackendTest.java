package com.bigdata.modules.telemetry.service.logdata.task;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.Target;
import com.bigdata.modules.telemetry.common.model.BackendCapability;
import com.bigdata.modules.telemetry.common.exception.BackendException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.bigdata.modules.telemetry.config.properties.FlinkRestConfig;
import com.bigdata.modules.telemetry.service.logdata.model.ExceptionQueryResult;
import com.bigdata.modules.telemetry.service.logdata.model.ExceptionQuerySpec;

/**
 * Flink 2.2 异常摘要后端测试。
 *
 * <p>fixture 使用 2.2 的真实响应形状 {@code exceptionHistory.entries[]}；Flink 2.2 没有
 * {@code /jobs/{jobId}/exceptions/{exception}} 子资源，因此这里不测单条端点，避免把 404 当能力。</p>
 */
class FlinkRestExceptionBackendTest {

    private static final String REAL_SAMPLE = """
            {"exceptionHistory":{"entries":[
              {"exceptionName":"org.apache.flink.runtime.JobException",
               "stacktrace":"org.apache.flink.runtime.JobException: Recovery is suppressed\\n\\tat a.B\\nCaused by: java.net.ConnectException: Connection refused",
               "timestamp":1794756213241,"failureLabels":{},
               "concurrentExceptions":[
                 {"exceptionName":"java.net.ConnectException",
                  "stacktrace":"java.net.ConnectException: Connection refused\\n\\tat a.B",
                  "timestamp":1794756213217,"failureLabels":{"type":"network"},
                  "taskName":"Source: Socket Stream -> Flat Map (1/1) - execution #0",
                  "endpoint":"flink-taskmanager:38421",
                  "taskManagerId":"172.18.0.8:44693-2bf035"}]}],
             "truncated":false}}
            """;

    @Test
    @DisplayName("有异常：保留 taskName / exceptionName / rootCause / timestamp 与并发异常")
    void listWithExceptions() throws IOException {
        try (FakeFlink flink = new FakeFlink(200, REAL_SAMPLE)) {
            ExceptionQueryResult result = backend(flink).fetch(spec(null, null));

            assertThat(result.isEmpty()).isFalse();
            assertThat(result.truncated()).isFalse();
            assertThat(result.concurrentExceptionCount()).isEqualTo(1);
            ExceptionQueryResult.ExceptionRecord entry = result.exceptions().get(0);
            assertThat(entry.exceptionName()).isEqualTo("org.apache.flink.runtime.JobException");
            assertThat(entry.rootCause()).isEqualTo("java.net.ConnectException: Connection refused");
            assertThat(entry.timestamp()).isEqualTo(Instant.ofEpochMilli(1794756213241L));
            assertThat(entry.taskName()).isNull();
            assertThat(entry.concurrentExceptions()).singleElement().satisfies(concurrent -> {
                assertThat(concurrent.exceptionName()).isEqualTo("java.net.ConnectException");
                assertThat(concurrent.taskName())
                        .isEqualTo("Source: Socket Stream -> Flat Map (1/1) - execution #0");
                assertThat(concurrent.failureLabels()).containsEntry("type", "network");
                assertThat(concurrent.taskManagerId()).isEqualTo("172.18.0.8:44693-2bf035");
            });
        }
    }

    @Test
    @DisplayName("无异常：空历史是成功的空结果，不是取不到")
    void emptyHistory() throws IOException {
        try (FakeFlink flink = new FakeFlink(200,
                "{\"exceptionHistory\":{\"entries\":[],\"truncated\":false}}")) {
            ExceptionQueryResult result = backend(flink).fetch(spec(null, null));

            assertThat(result.isEmpty()).isTrue();
            assertThat(result.truncated()).isFalse();
            assertThat(result.warnings()).isEmpty();
        }
    }

    @Test
    @DisplayName("服务端截断：映射 exceptionHistory.truncated 并保留标记")
    void serverTruncated() throws IOException {
        try (FakeFlink flink = new FakeFlink(200, """
                {"exceptionHistory":{"entries":[{"exceptionName":"E","stacktrace":"E",
                  "timestamp":1,"failureLabels":{},"concurrentExceptions":[]}],"truncated":true}}
                """)) {
            ExceptionQueryResult result = backend(flink).fetch(spec(null, null));

            assertThat(result.serverTruncated()).isTrue();
            assertThat(result.truncated()).isTrue();
            assertThat(result.exceptions().get(0).serverTruncated()).isTrue();
            assertThat(result.warnings())
                    .anyMatch(warning -> warning.code().equals("EXCEPTION_HISTORY_TRUNCATED"));
        }
    }

    @Test
    @DisplayName("条数上限：超过 maxExceptions 只返回前 N 条并标记 truncated")
    void limitTruncated() throws IOException {
        try (FakeFlink flink = new FakeFlink(200, threeEntries())) {
            ExceptionQueryResult result = backend(flink).fetch(spec(2, null));

            assertThat(result.exceptions()).hasSize(2);
            assertThat(result.limitTruncated()).isTrue();
            assertThat(result.requestedMaxExceptions()).isEqualTo(2);
            assertThat(result.warnings())
                    .anyMatch(warning -> warning.code().equals("RESULT_TRUNCATED"));
        }
    }

    @Test
    @DisplayName("并发异常上限：只返回前 N 条，总数仍然保留")
    void concurrentLimit() throws IOException {
        try (FakeFlink flink = new FakeFlink(200, """
                {"exceptionHistory":{"entries":[{"exceptionName":"E","stacktrace":"E",
                  "timestamp":1,"failureLabels":{},"concurrentExceptions":[
                    {"exceptionName":"C1","stacktrace":"C1","timestamp":1,"failureLabels":{}},
                    {"exceptionName":"C2","stacktrace":"C2","timestamp":2,"failureLabels":{}},
                    {"exceptionName":"C3","stacktrace":"C3","timestamp":3,"failureLabels":{}}]}],
                 "truncated":false}}
                """)) {
            ExceptionQueryResult result = backend(flink).fetch(spec(null, null, 2));

            assertThat(result.concurrentExceptionCount()).isEqualTo(3);
            assertThat(result.exceptions().get(0).concurrentExceptions()).hasSize(2);
            assertThat(result.truncated()).isTrue();
            assertThat(result.warnings())
                    .anyMatch(warning -> warning.code().equals("RESULT_TRUNCATED"));
        }
    }

    @Test
    @DisplayName("stacktrace 上限：客户端截断必须显式标记并保留原始字符数")
    void stackTraceTruncated() throws IOException {
        try (FakeFlink flink = new FakeFlink(200, REAL_SAMPLE)) {
            FlinkRestConfig config = new FlinkRestConfig();
            config.setBaseUrl(flink.baseUrl());
            config.setMaxStackTraceChars(20);
            FlinkRestExceptionBackend backend = new FlinkRestExceptionBackend(
                    "flink-rest", config, null, new ObjectMapper());

            ExceptionQueryResult result = backend.fetch(spec(null, null));

            ExceptionQueryResult.ExceptionRecord entry = result.exceptions().get(0);
            assertThat(entry.stackTrace()).hasSize(20);
            assertThat(entry.stackTraceTruncated()).isTrue();
            assertThat(entry.stackTraceChars()).isGreaterThan(20);
            assertThat(result.truncated()).isTrue();
            assertThat(result.warnings())
                    .anyMatch(warning -> warning.code().equals("STACKTRACE_TRUNCATED"));
        }
    }

    @Test
    @DisplayName("includeStackTraces=false：不返回 stacktrace，但保留结构化字段")
    void withoutStackTraces() throws IOException {
        try (FakeFlink flink = new FakeFlink(200, REAL_SAMPLE)) {
            ExceptionQueryResult result = backend(flink).fetch(spec(null, false));

            ExceptionQueryResult.ExceptionRecord entry = result.exceptions().get(0);
            assertThat(entry.stackTrace()).isNull();
            assertThat(entry.stackTraceChars()).isZero();
            assertThat(entry.exceptionName()).isNotBlank();
        }
    }

    @Test
    @DisplayName("404：映射 NOT_FOUND，不当作空结果")
    void notFound() throws IOException {
        try (FakeFlink flink = new FakeFlink(404, "{\"errors\":[\"not found\"]}")) {
            assertThatThrownBy(() -> backend(flink).fetch(spec(null, null)))
                    .isInstanceOf(BackendException.class)
                    .satisfies(error -> assertThat(((BackendException) error).getCode())
                            .isEqualTo(CapabilityCode.NOT_FOUND));
        }
    }

    @Test
    @DisplayName("上游 5xx：映射 UPSTREAM_ERROR 且可重试")
    void upstreamError() throws IOException {
        try (FakeFlink flink = new FakeFlink(503, "{\"errors\":[\"unavailable\"]}")) {
            assertThatThrownBy(() -> backend(flink).fetch(spec(null, null)))
                    .isInstanceOf(BackendException.class)
                    .satisfies(error -> {
                        BackendException backendError = (BackendException) error;
                        assertThat(backendError.getCode()).isEqualTo(CapabilityCode.UPSTREAM_ERROR);
                        assertThat(backendError.retryable()).isTrue();
                        assertThat(backendError.availabilityFailure()).isTrue();
                    });
        }
    }

    @Test
    @DisplayName("上游超时：映射 UPSTREAM_TIMEOUT")
    void upstreamTimeout() throws IOException {
        try (FakeFlink flink = new FakeFlink(200, REAL_SAMPLE, 1500)) {
            FlinkRestConfig config = new FlinkRestConfig();
            config.setBaseUrl(flink.baseUrl());
            config.getLimits().setTimeoutMs(500);
            FlinkRestExceptionBackend backend = new FlinkRestExceptionBackend(
                    "flink-rest", config, null, new ObjectMapper());

            assertThatThrownBy(() -> backend.fetch(spec(null, null)))
                    .isInstanceOf(BackendException.class)
                    .satisfies(error -> assertThat(((BackendException) error).getCode())
                            .isEqualTo(CapabilityCode.UPSTREAM_TIMEOUT));
        }
    }

    @Test
    @DisplayName("缺少 jobId：能力判定 INVALID_ARGUMENT，不发起调用")
    void missingJobId() throws IOException {
        try (FakeFlink flink = new FakeFlink(200, REAL_SAMPLE)) {
            BackendCapability capability = backend(flink).evaluate(
                    new ExceptionQuerySpec(TARGET, " ", null, null, null, null));

            assertThat(capability.isAvailable()).isFalse();
            assertThat(capability.code()).isEqualTo(CapabilityCode.INVALID_ARGUMENT);
        }
    }

    @Test
    @DisplayName("未配置 base-url：能力判定 NOT_CONFIGURED")
    void notConfigured() {
        FlinkRestConfig config = new FlinkRestConfig();
        FlinkRestExceptionBackend backend = new FlinkRestExceptionBackend(
                "flink-rest", config, null, new ObjectMapper());

        BackendCapability capability = backend.evaluate(spec(null, null));
        assertThat(capability.code()).isEqualTo(CapabilityCode.NOT_CONFIGURED);
        assertThat(capability.requirements()).isNotEmpty();
    }

    @Test
    @DisplayName("缺少 exceptionHistory：解析失败而不是伪造空结果")
    void missingHistory() throws IOException {
        try (FakeFlink flink = new FakeFlink(200, "{\"root-exception\":\"legacy\"}")) {
            assertThatThrownBy(() -> backend(flink).fetch(spec(null, null)))
                    .isInstanceOf(BackendException.class)
                    .satisfies(error -> assertThat(((BackendException) error).getCode())
                            .isEqualTo(CapabilityCode.PARSE_ERROR));
        }
    }

    // ------------------------------------------------------------------ 辅助

    private static final Target TARGET = new Target("flink", "flink.jobmanager", "cluster");

    private ExceptionQuerySpec spec(Integer maxExceptions, Boolean includeStackTraces) {
        return spec(maxExceptions, includeStackTraces, null);
    }

    private ExceptionQuerySpec spec(Integer maxExceptions, Boolean includeStackTraces,
                                    Integer maxConcurrent) {
        return new ExceptionQuerySpec(TARGET, "j1", maxExceptions, maxConcurrent,
                includeStackTraces, null);
    }

    private FlinkRestExceptionBackend backend(FakeFlink flink) {
        FlinkRestConfig config = new FlinkRestConfig();
        config.setId("flink-rest");
        config.setBaseUrl(flink.baseUrl());
        return new FlinkRestExceptionBackend("flink-rest", config, null, new ObjectMapper());
    }

    private static String threeEntries() {
        return """
                {"exceptionHistory":{"entries":[
                  {"exceptionName":"E1","stacktrace":"E1","timestamp":1,"failureLabels":{},
                   "concurrentExceptions":[]},
                  {"exceptionName":"E2","stacktrace":"E2","timestamp":2,"failureLabels":{},
                   "concurrentExceptions":[]},
                  {"exceptionName":"E3","stacktrace":"E3","timestamp":3,"failureLabels":{},
                   "concurrentExceptions":[]}],"truncated":false}}
                """;
    }

    private static final class FakeFlink implements AutoCloseable {

        private final HttpServer server;

        private FakeFlink(int status, String body) throws IOException {
            this(status, body, 0);
        }

        private FakeFlink(int status, String body, long delayMs) throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/jobs/j1/exceptions", exchange -> {
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                respond(exchange, status, body);
            });
            server.start();
        }

        private String baseUrl() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static void respond(HttpExchange exchange, int status, String body)
                throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
