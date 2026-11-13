package com.bigdata.modules.logs.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.logs.LogTestSupport;
import com.bigdata.modules.logs.adapter.LogAdapterRegistry;
import com.bigdata.modules.logs.config.LogAccessProperties;
import com.bigdata.modules.logs.dto.LogQueryDTO;
import com.bigdata.modules.logs.model.LogKind;
import com.bigdata.modules.logs.model.LogSource;
import com.bigdata.modules.logs.vo.LogSourceVO;
import com.bigdata.modules.system.service.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DisplayName("Flink 能力边界：未配置 logKind 的标注与明确报错")
class FlinkCapabilityTest {

    @Test
    @DisplayName("只配 REST 时 exception 可用，runtime/stdout 标注不可用原因")
    void restOnlyMarksRawLogsUnavailable() {
        LogAccessProperties properties = flinkProperties();

        LogSource source = catalog(properties).findById("flink-default");

        assertNotNull(source);
        assertNull(source.issueOf(LogKind.EXCEPTION));
        assertNotNull(source.issueOf(LogKind.RUNTIME));
        assertTrue(source.issueOf(LogKind.RUNTIME).contains("jobmanager-log-dirs"));
        assertNotNull(source.issueOf(LogKind.STDOUT));
    }

    @Test
    @DisplayName("配置了 pod-selector 但未提供目录时，明确标注 K8s 适配器 P1 才提供")
    void podSelectorMarksP1() {
        LogAccessProperties properties = flinkProperties();
        properties.getSources().get(0).getOptions().setPodSelector("app=flink,component=taskmanager");

        LogSource source = catalog(properties).findById("flink-default");

        assertEquals("K8s 容器日志适配器计划在 P1 提供（已配置 pod-selector，但 P0 未实现）",
                source.issueOf(LogKind.RUNTIME));
    }

    @Test
    @DisplayName("查询未启用的 logKind：返回 400 与具体原因")
    void queryUnavailableKindReturnsBusinessError() {
        LogAccessProperties properties = flinkProperties();
        LogQueryService service = service(properties);

        LogQueryDTO dto = new LogQueryDTO();
        dto.setServiceType("flink");
        dto.setCluster("default");
        dto.setComponentRole("task_manager");
        dto.setLogKind("stdout");

        BusinessException error = assertThrows(BusinessException.class, () -> service.query(dto));
        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("不可用"));
        assertTrue(error.getMessage().contains("jobmanager-log-dirs"));
    }

    @Test
    @DisplayName("/sources 中如实返回不可用 logKind 及原因")
    void sourcesExposeUnavailableKinds() {
        LogAccessProperties properties = flinkProperties();
        LogQueryService service = service(properties);

        List<LogSourceVO> sources = service.listSources();

        assertEquals(1, sources.size());
        Map<String, String> unavailable = sources.get(0).getUnavailableLogKinds();
        assertTrue(unavailable.containsKey("runtime"));
        assertTrue(unavailable.containsKey("stdout"));
        assertTrue(!unavailable.containsKey("exception"));
    }

    private LogAccessProperties flinkProperties() {
        LogAccessProperties properties = LogTestSupport.properties();
        properties.getRbac().setDefaultAllowedServices(new java.util.ArrayList<>(List.of("flink")));
        LogAccessProperties.Source config = LogTestSupport.sourceConfig(
                "flink-default", "flink", "http://jm:8081");
        config.setRoles(List.of("job_manager", "task_manager"));
        config.setLogKinds(List.of("exception", "runtime", "stdout"));
        properties.getSources().add(config);
        return properties;
    }

    private LogSourceCatalog catalog(LogAccessProperties properties) {
        return new LogSourceCatalog(properties, new SecretResolver(new StandardEnvironment()),
                new StandardEnvironment());
    }

    private LogQueryService service(LogAccessProperties properties) {
        return new LogQueryService(
                properties,
                catalog(properties),
                new LogAdapterRegistry(List.of()),
                new LogMaskingService(properties),
                new LogAccessControlService(properties),
                new LogCircuitBreaker(),
                new LogExecutorRegistry(properties),
                new LogCache(properties),
                new LogAuditService(mock(AuditLogService.class), new ObjectMapper()),
                new LogAccessMetrics(properties));
    }
}
