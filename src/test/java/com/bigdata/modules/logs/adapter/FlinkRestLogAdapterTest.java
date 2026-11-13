package com.bigdata.modules.logs.adapter;

import com.bigdata.modules.logs.LogTestSupport;
import com.bigdata.modules.logs.config.LogAccessProperties;
import com.bigdata.modules.logs.model.LogSource;
import com.bigdata.modules.logs.model.ServiceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Flink REST 适配器：异常聚合、404、不可达降级")
class FlinkRestLogAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("多层异常链：根异常 + 按时间排序的全部异常 + 堆栈")
    void aggregatesExceptionChain() {
        FakeHttp http = new FakeHttp();
        http.on("/jobs/j1", 200, jobJson());
        http.on("/jobs/j1/exceptions", 200, """
                {
                  "root-exception": "java.lang.RuntimeException: root boom\\n\\tat a.b.C.d(C.java:10)",
                  "all-exceptions": [
                    {
                      "exceptionName": "java.io.IOException: disk",
                      "stacktrace": "java.io.IOException: disk\\n\\tat x.Y.z(Y.java:1)",
                      "timestamp": 1700000050000,
                      "taskName": "Sink",
                      "taskId": "v2"
                    },
                    {
                      "exceptionName": "java.lang.RuntimeException: root boom",
                      "stacktrace": "java.lang.RuntimeException: root boom\\n\\tat a.b.C.d(C.java:10)",
                      "timestamp": 1700000010000,
                      "taskName": "Source",
                      "taskId": "v1"
                    }
                  ]
                }
                """);
        http.on("/jobs/j1/checkpoints", 200, """
                {"counts":{"completed":3,"failed":1,"in_progress":0,"restored":1},
                 "latest":{"failed":{"id":2,"trigger_timestamp":1700000040000}}}
                """);
        http.on("/jobs/j1/metrics", 200, "[{\"id\":\"numRestarts\",\"value\":\"2\"}]");

        FlinkRestLogAdapter adapter = new FlinkRestLogAdapter(http, mapper);
        AdapterResult result = adapter.query(context());

        assertTrue(result.getContent().contains("# Flink REST 结构化摘要（非原始日志）"));
        assertTrue(result.getContent().contains("ROOT_EXCEPTION"));
        assertTrue(result.getContent().contains("RESTARTS numRestarts=2"));
        assertTrue(result.getContent().contains("CHECKPOINT completed=3 failed=1"));
        assertTrue(result.getContent().contains("CHECKPOINT latestFailed id=2"));

        int sourceIndex = result.getContent().indexOf("task=Source");
        int sinkIndex = result.getContent().indexOf("task=Sink");
        assertTrue(sourceIndex > 0 && sinkIndex > sourceIndex, "异常应按时间升序排列");
        assertTrue(result.getContent().contains("at x.Y.z(Y.java:1)"));
        assertTrue(result.getMessage().contains("不是原始日志"));
    }

    @Test
    @DisplayName("空异常：仍然返回作业摘要并明确标注无异常")
    void emptyExceptions() {
        FakeHttp http = new FakeHttp();
        http.on("/jobs/j1", 200, jobJson());
        http.on("/jobs/j1/exceptions", 200, "{\"root-exception\":null,\"all-exceptions\":[]}");

        FlinkRestLogAdapter adapter = new FlinkRestLogAdapter(http, mapper);
        AdapterResult result = adapter.query(context());

        assertTrue(result.getContent().contains("EXCEPTIONS 无异常记录"));
        assertTrue(result.getContent().contains("JOB id=j1"));
    }

    @Test
    @DisplayName("job 不存在：返回 NOT_FOUND")
    void jobNotFound() {
        FakeHttp http = new FakeHttp();
        http.on("/jobs/missing", 404, "{\"errors\":[\"Job not found\"]}");

        FlinkRestLogAdapter adapter = new FlinkRestLogAdapter(http, mapper);
        LogAdapterException error = assertThrows(LogAdapterException.class,
                () -> adapter.query(context("missing")));

        assertEquals(LogAdapterException.Code.NOT_FOUND, error.getCode());
        assertTrue(error.getMessage().contains("不存在"));
    }

    @Test
    @DisplayName("REST 不可达：返回 UNAVAILABLE，由上层转降级")
    void unreachable() {
        HttpLogClient failing = (url, headers, timeout) -> {
            throw new IOException("Connection refused");
        };
        FlinkRestLogAdapter adapter = new FlinkRestLogAdapter(failing, mapper);

        LogAdapterException error = assertThrows(LogAdapterException.class,
                () -> adapter.query(context()));
        assertEquals(LogAdapterException.Code.UNAVAILABLE, error.getCode());
        assertTrue(error.getMessage().contains("不可达"));
    }

    private LogQueryContext context() {
        return context("j1");
    }

    private LogQueryContext context(String jobId) {
        LogAccessProperties.Source config = LogTestSupport.sourceConfig("flink-default", "flink", "http://jm:8081");
        LogSource source = LogTestSupport.source(config, ServiceType.FLINK);
        return LogTestSupport.context(source, 2000, 262144, Map.of("jobId", jobId));
    }

    private String jobJson() {
        return """
                {"jid":"j1","name":"demo-job","state":"FAILED","start-time":1700000000000,
                 "end-time":1700000100000,"duration":100000,"tasks":{"failed":1}}
                """;
    }

    private static final class FakeHttp implements HttpLogClient {
        private final Map<String, HttpTextResponse> responses = new HashMap<>();

        private void on(String suffix, int status, String body) {
            responses.put(suffix, new HttpTextResponse(status, body, "application/json"));
        }

        @Override
        public HttpTextResponse get(String url, Map<String, String> headers, Duration timeout) {
            // 前缀相同的端点（/jobs/j1 与 /jobs/j1/exceptions）取最长匹配，避免命中错误响应
            String matched = null;
            for (String suffix : responses.keySet()) {
                if (url.contains(suffix) && (matched == null || suffix.length() > matched.length())) {
                    matched = suffix;
                }
            }
            return matched == null ? new HttpTextResponse(404, "{}", "application/json") : responses.get(matched);
        }
    }
}
