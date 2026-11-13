package com.bigdata.modules.logs.adapter;

import com.bigdata.modules.logs.LogTestSupport;
import com.bigdata.modules.logs.config.LogAccessProperties;
import com.bigdata.modules.logs.model.LogSource;
import com.bigdata.modules.logs.model.ServiceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Spark History Server 适配器：不可达与不支持时降级")
class SparkShsLogAdapterTest {

    @Test
    @DisplayName("连接失败时降级为 SOURCE_UNREACHABLE")
    void unreachableDegrades() {
        HttpLogClient failing = (url, headers, timeout) -> {
            throw new IOException("Connection refused");
        };
        SparkShsLogAdapter adapter = new SparkShsLogAdapter(failing);

        AdapterResult result = adapter.query(context());

        assertTrue(result.isDegraded());
        assertEquals("SOURCE_UNREACHABLE", result.getErrorCode());
        assertTrue(result.getMessage().contains("不可达"));
    }

    @Test
    @DisplayName("接口返回 404 时降级为 UNSUPPORTED 并给出替代方案")
    void notFoundDegrades() {
        HttpLogClient notFound = (url, headers, timeout) ->
                new HttpTextResponse(404, "Not Found", "text/plain");
        SparkShsLogAdapter adapter = new SparkShsLogAdapter(notFound);

        AdapterResult result = adapter.query(context());

        assertTrue(result.isDegraded());
        assertEquals("UNSUPPORTED", result.getErrorCode());
        assertTrue(result.getMessage().contains("YARN"));
    }

    @Test
    @DisplayName("正常返回文本时按预算裁剪")
    void success() {
        HttpLogClient ok = (url, headers, timeout) ->
                new HttpTextResponse(200, "line1\nline2", "text/plain");
        SparkShsLogAdapter adapter = new SparkShsLogAdapter(ok);

        AdapterResult result = adapter.query(context());

        assertFalse(result.isDegraded());
        assertEquals("line1\nline2", result.getContent());
        assertEquals(2, result.getLineCount());
    }

    private LogQueryContext context() {
        LogAccessProperties.Source config = LogTestSupport.sourceConfig("spark-shs-default", "spark", "http://shs:18080");
        LogSource source = LogTestSupport.source(config, ServiceType.SPARK);
        return LogTestSupport.context(source, 2000, 262144, Map.of("appId", "application_1"));
    }
}
