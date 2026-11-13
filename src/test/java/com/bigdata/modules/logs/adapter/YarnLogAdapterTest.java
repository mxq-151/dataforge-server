package com.bigdata.modules.logs.adapter;

import com.bigdata.modules.logs.LogTestSupport;
import com.bigdata.modules.logs.config.LogAccessProperties;
import com.bigdata.modules.logs.model.LogSource;
import com.bigdata.modules.logs.model.ServiceType;
import com.bigdata.modules.logs.service.LogExecutorRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("YARN 聚合日志适配器：异步执行与超时降级")
class YarnLogAdapterTest {

    @Test
    @DisplayName("命令成功时返回标准输出并按预算裁剪")
    void success() {
        LogAccessProperties properties = LogTestSupport.properties();
        LogExecutorRegistry registry = new LogExecutorRegistry(properties);
        CommandRunner runner = (command, timeout) -> new CommandResult(0, "l1\nl2\n", "", false);
        YarnLogAdapter adapter = new YarnLogAdapter(runner, registry);

        AdapterResult result = adapter.query(context("application_1", 100, 4096, 2000));

        assertEquals("l1\nl2", result.getContent());
        assertEquals(2, result.getLineCount());
        assertFalse(result.isDegraded());
        registry.destroy();
    }

    @Test
    @DisplayName("命令超过查询预算时降级为 TIMEOUT，而不是抛异常")
    void timeoutDegrades() {
        LogAccessProperties properties = LogTestSupport.properties();
        LogExecutorRegistry registry = new LogExecutorRegistry(properties);
        CommandRunner slowRunner = (command, timeout) -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new CommandResult(0, "late", "", false);
        };
        YarnLogAdapter adapter = new YarnLogAdapter(slowRunner, registry);

        AdapterResult result = adapter.query(context("application_1", 100, 4096, 150));

        assertTrue(result.isDegraded());
        assertEquals("TIMEOUT", result.getErrorCode());
        registry.destroy();
    }

    @Test
    @DisplayName("聚合尚未完成时给出可读降级原因")
    void aggregationNotReady() {
        LogAccessProperties properties = LogTestSupport.properties();
        LogExecutorRegistry registry = new LogExecutorRegistry(properties);
        CommandRunner runner = (command, timeout) -> new CommandResult(1, "",
                "Log aggregation has not completed for application_1", false);
        YarnLogAdapter adapter = new YarnLogAdapter(runner, registry);

        AdapterResult result = adapter.query(context("application_1", 100, 4096, 2000));

        assertTrue(result.isDegraded());
        assertEquals("AGGREGATION_NOT_READY", result.getErrorCode());
        registry.destroy();
    }

    @Test
    @DisplayName("缺少 applicationId 时返回参数错误")
    void missingApplicationId() {
        LogAccessProperties properties = LogTestSupport.properties();
        LogExecutorRegistry registry = new LogExecutorRegistry(properties);
        YarnLogAdapter adapter = new YarnLogAdapter((command, timeout) -> new CommandResult(0, "", "", false), registry);

        LogAdapterException error = org.junit.jupiter.api.Assertions.assertThrows(LogAdapterException.class,
                () -> adapter.query(context("", 100, 4096, 2000)));
        assertEquals(LogAdapterException.Code.BAD_REQUEST, error.getCode());
        registry.destroy();
    }

    private LogQueryContext context(String appId, int maxLines, int maxBytes, long timeoutMs) {
        LogAccessProperties.Source config = LogTestSupport.sourceConfig("yarn-default", "yarn", "");
        LogSource source = LogTestSupport.source(config, ServiceType.YARN);
        LogQueryContext context = LogTestSupport.context(source, maxLines, maxBytes,
                appId == null || appId.isBlank() ? Map.of() : Map.of("applicationId", appId));
        context.setTimeoutMs(timeoutMs);
        return context;
    }
}
