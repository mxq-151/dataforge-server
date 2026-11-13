package com.bigdata.modules.runtime.service;

import com.bigdata.modules.logs.service.SecretResolver;
import com.bigdata.modules.runtime.RuntimeTestSupport;
import com.bigdata.modules.runtime.config.RuntimeProperties;
import com.bigdata.modules.runtime.model.RuntimeSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("运行时源能力边界：live/history、未配置端点")
class RuntimeCapabilityTest {

    private final RuntimeProperties properties = RuntimeTestSupport.properties();
    private final RuntimeSourceCatalog catalog =
            new RuntimeSourceCatalog(properties, new SecretResolver(new StandardEnvironment()));

    @Test
    @DisplayName("Flink 未配置 JobManager REST：源不可用并说明原因")
    void flinkRequiresRest() {
        properties.setSources(List.of(RuntimeTestSupport.sourceConfig(
                "flink", "flink", "live", "")));
        RuntimeSource source = catalog.listAll().get(0);
        assertFalse(source.isEnabled());
        assertTrue(source.getDisabledReason().contains("base-url"));
    }

    @Test
    @DisplayName("Flink 不支持 history 模式")
    void flinkHistoryRejected() {
        properties.setSources(List.of(RuntimeTestSupport.sourceConfig(
                "flink", "flink", "history", "http://jm:8081")));
        RuntimeSource source = catalog.listAll().get(0);
        assertFalse(source.isEnabled());
        assertTrue(source.getDisabledReason().contains("live"));
    }

    @Test
    @DisplayName("Spark history 必须配置 History Server 地址")
    void sparkHistoryRequiresBaseUrl() {
        properties.setSources(List.of(RuntimeTestSupport.sourceConfig(
                "spark-shs", "spark", "history", "")));
        RuntimeSource source = catalog.listAll().get(0);
        assertFalse(source.isEnabled());
        assertTrue(source.getDisabledReason().contains("History Server"));
    }

    @Test
    @DisplayName("Spark live 可只配置 YARN RM（运行时反查 trackingUrl）")
    void sparkLiveCanUseYarn() {
        RuntimeProperties.Source config = RuntimeTestSupport.sourceConfig(
                "spark-live", "spark", "live", "");
        config.getOptions().setYarnRmUrl("http://yarn-rm:8088");
        properties.setSources(List.of(config));
        RuntimeSource source = catalog.listAll().get(0);
        assertTrue(source.isEnabled());
    }

    @Test
    @DisplayName("runtime 只接受 flink / spark")
    void rejectsOtherServiceType() {
        properties.setSources(List.of(RuntimeTestSupport.sourceConfig(
                "ds", "ds", "live", "http://ds:12345")));
        RuntimeSource source = catalog.listAll().get(0);
        assertFalse(source.isEnabled());
        assertTrue(source.getDisabledReason().contains("仅支持 flink / spark"));
    }
}
