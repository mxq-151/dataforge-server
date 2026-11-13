package com.bigdata.modules.logs.adapter;

import com.bigdata.modules.logs.LogTestSupport;
import com.bigdata.modules.logs.config.LogAccessProperties;
import com.bigdata.modules.logs.model.LogSource;
import com.bigdata.modules.logs.model.ServiceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DS 日志适配器：首行解析、游标、截断、worker 路由")
class DsRestLogAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("首行正常：剥离 [LOG-PATH]/[HOST] 并回填解析结果")
    void parseHeader() throws Exception {
        FakeHttp http = new FakeHttp();
        http.respond(dsResponse("[LOG-PATH]: /opt/ds/logs/1.log, [HOST]: 127.0.0.1\nline1\nline2", 2L));
        DsRestLogAdapter adapter = new DsRestLogAdapter(http, mapper);

        AdapterResult result = adapter.query(context(2000, 262144));

        assertEquals("line1\nline2", result.getContent());
        assertEquals(2, result.getLineCount());
        assertFalse(result.isHasMore());
        assertFalse(result.isDegraded());
        assertEquals("/opt/ds/logs/1.log", result.getResolvedPath());
        assertEquals("127.0.0.1", result.getResolvedHost());
        assertTrue(http.urls.get(0).contains("skipLineNum=0"));
    }

    @Test
    @DisplayName("首行格式异常：按原始内容返回并标记降级")
    void malformedHeaderDegrades() throws Exception {
        FakeHttp http = new FakeHttp();
        http.respond(dsResponse("[LOG-PATH]: /opt/ds/logs/1.log\nline1", null));
        DsRestLogAdapter adapter = new DsRestLogAdapter(http, mapper);

        AdapterResult result = adapter.query(context(2000, 262144));

        assertTrue(result.isDegraded());
        assertEquals("HEADER_MISSING", result.getErrorCode());
        assertTrue(result.getContent().contains("line1"));
        assertNotNull(result.getDegradationReason());
    }

    @Test
    @DisplayName("无 LOG-PATH 首行：按原始内容返回并标记降级")
    void missingHeaderDegrades() throws Exception {
        FakeHttp http = new FakeHttp();
        http.respond(dsResponse("plain log content", null));
        DsRestLogAdapter adapter = new DsRestLogAdapter(http, mapper);

        AdapterResult result = adapter.query(context(2000, 262144));

        assertTrue(result.isDegraded());
        assertEquals("HEADER_MISSING", result.getErrorCode());
        assertEquals("plain log content", result.getContent());
    }

    @Test
    @DisplayName("跨页游标只按实际返回行推进，不重不漏")
    void cursorAdvancesWithoutLossOrDuplicate() throws Exception {
        FakeHttp http = new FakeHttp();
        http.respond(dsResponse("[LOG-PATH]: /tmp/a.log, [HOST]: 127.0.0.1\nl1\nl2", 4L));
        http.respond(dsResponse("[LOG-PATH]: /tmp/a.log, [HOST]: 127.0.0.1\nl3\nl4", 4L));
        DsRestLogAdapter adapter = new DsRestLogAdapter(http, mapper);

        AdapterResult first = adapter.query(context(2, 262144));
        assertEquals(2L, first.getNextOffset());
        assertTrue(first.isHasMore());

        AdapterResult second = adapter.query(contextWithOffset(2, 2, 262144));
        assertEquals(4L, second.getNextOffset());
        assertFalse(second.isHasMore());

        List<String> all = new ArrayList<>();
        all.addAll(first.getContent().lines().toList());
        all.addAll(second.getContent().lines().toList());
        assertEquals(List.of("l1", "l2", "l3", "l4"), all);
    }

    @Test
    @DisplayName("单行超过字节上限：截断且不再翻页（避免重复行）")
    void singleLineTruncation() throws Exception {
        FakeHttp http = new FakeHttp();
        http.respond(dsResponse("[LOG-PATH]: /tmp/big.log, [HOST]: 127.0.0.1\n" + "a".repeat(300_000), 1L));
        DsRestLogAdapter adapter = new DsRestLogAdapter(http, mapper);

        AdapterResult result = adapter.query(context(2000, 1024));

        assertTrue(result.isTruncated());
        assertFalse(result.isHasMore());
        assertTrue(result.getContent().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024);
        assertNotNull(result.getMessage());
    }

    @Test
    @DisplayName("日志在远端 worker 且未配置该节点地址：降级并说明缺哪项配置")
    void workerEndpointMissingDegrades() throws Exception {
        FakeHttp http = new FakeHttp();
        http.respond(dsResponse("[LOG-PATH]: /opt/ds/logs/1.log, [HOST]: worker-2", null));
        DsRestLogAdapter adapter = new DsRestLogAdapter(http, mapper);

        AdapterResult result = adapter.query(context(2000, 262144));

        assertTrue(result.isDegraded());
        assertEquals("WORKER_ENDPOINT_MISSING", result.getErrorCode());
        assertTrue(result.getMessage().contains("worker-2"));
        assertEquals("worker-2", result.getResolvedHost());
    }

    @Test
    @DisplayName("配置了 worker 地址时自动路由到该 worker 重取")
    void workerRoutingSuccess() throws Exception {
        LogAccessProperties.Source config = LogTestSupport.sourceConfig("ds-default", "ds", "http://127.0.0.1:12345");
        config.getWorkerEndpoints().put("worker-2", "http://worker-2:12345");
        LogSource source = LogTestSupport.source(config, ServiceType.DS);

        FakeHttp http = new FakeHttp();
        http.respond(dsResponse("[LOG-PATH]: /opt/ds/logs/1.log, [HOST]: worker-2", null));
        http.respond(dsResponse("remote line", null));
        DsRestLogAdapter adapter = new DsRestLogAdapter(http, mapper);

        AdapterResult result = adapter.query(LogTestSupport.context(source, 2000, 262144,
                Map.of("taskInstanceId", "42")));

        assertEquals("remote line", result.getContent());
        assertEquals(2, http.urls.size());
        assertTrue(http.urls.get(1).startsWith("http://worker-2:12345"));
    }

    private LogQueryContext context(int maxLines, int maxBytes) {
        return contextWithOffset(0, maxLines, maxBytes);
    }

    private LogQueryContext contextWithOffset(long offset, int maxLines, int maxBytes) {
        LogAccessProperties.Source config = LogTestSupport.sourceConfig("ds-default", "ds", "http://127.0.0.1:12345");
        LogSource source = LogTestSupport.source(config, ServiceType.DS);
        LogQueryContext context = LogTestSupport.context(source, maxLines, maxBytes,
                Map.of("taskInstanceId", "42"));
        context.setOffset(offset);
        return context;
    }

    private HttpTextResponse dsResponse(String message, Long lineNum) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("code", 0);
        root.put("msg", "success");
        ObjectNode data = root.putObject("data");
        data.put("message", message);
        if (lineNum != null) {
            data.put("lineNum", lineNum);
        }
        return new HttpTextResponse(200, mapper.writeValueAsString(root), "application/json");
    }

    private static final class FakeHttp implements HttpLogClient {
        private final Deque<HttpTextResponse> responses = new ArrayDeque<>();
        private final List<String> urls = new ArrayList<>();

        private void respond(HttpTextResponse response) {
            responses.add(response);
        }

        @Override
        public HttpTextResponse get(String url, Map<String, String> headers, Duration timeout) {
            urls.add(url);
            return responses.isEmpty() ? new HttpTextResponse(500, "", "text/plain") : responses.removeFirst();
        }
    }
}
