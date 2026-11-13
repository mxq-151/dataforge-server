package com.bigdata.modules.logs.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.logs.LogTestSupport;
import com.bigdata.modules.logs.adapter.AdapterResult;
import com.bigdata.modules.logs.adapter.LogAdapter;
import com.bigdata.modules.logs.adapter.LogAdapterException;
import com.bigdata.modules.logs.adapter.LogAdapterRegistry;
import com.bigdata.modules.logs.adapter.LogQueryContext;
import com.bigdata.modules.logs.config.LogAccessProperties;
import com.bigdata.modules.logs.dto.LogQueryDTO;
import com.bigdata.modules.logs.model.ComponentRole;
import com.bigdata.modules.logs.model.LogCursor;
import com.bigdata.modules.logs.model.LogKind;
import com.bigdata.modules.logs.model.ServiceType;
import com.bigdata.modules.logs.vo.LogQueryResultVO;
import com.bigdata.modules.system.service.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DisplayName("日志查询编排：未启用、RBAC、脱敏、降级、关键字")
class LogQueryServiceTest {

    private LogAccessProperties properties;
    private StubAdapter adapter;

    @BeforeEach
    void setUp() {
        properties = LogTestSupport.properties();
        properties.getRbac().setDefaultAllowedServices(new ArrayList<>(List.of("ds")));
        properties.getSources().add(LogTestSupport.sourceConfig("ds-default", "ds", "http://127.0.0.1:12345"));
        adapter = new StubAdapter();
    }

    @Test
    @DisplayName("未配置地址的源：返回明确的未启用错误，而不是异常堆栈")
    void disabledSourceReturnsBusinessError() {
        properties.getSources().clear();
        properties.getSources().add(LogTestSupport.sourceConfig("spark-shs-default", "spark", ""));
        properties.getRbac().setDefaultAllowedServices(new ArrayList<>(List.of("ds", "spark")));
        LogQueryService service = buildService();

        LogQueryDTO dto = dto();
        dto.setServiceType("spark");
        dto.setRef(Map.of("appId", "application_1"));

        BusinessException error = assertThrows(BusinessException.class, () -> service.query(dto));
        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("未启用"));
    }

    @Test
    @DisplayName("RBAC：未允许的服务返回 403")
    void rbacDenied() {
        properties.getRbac().setDefaultAllowedServices(new ArrayList<>());
        LogQueryService service = buildService();

        BusinessException error = assertThrows(BusinessException.class, () -> service.query(dto()));
        assertEquals(403, error.getCode());
    }

    @Test
    @DisplayName("出口脱敏：返回内容中不出现明文凭据")
    void maskingApplied() {
        adapter.result = AdapterResult.of("token=abcd1234", 1, 1, false);
        LogQueryService service = buildService();

        LogQueryResultVO result = service.query(dto());

        assertFalse(result.getContent().contains("abcd1234"));
        assertTrue(result.getContent().contains("******"));
    }

    @Test
    @DisplayName("适配器不可达：转为 degraded 结果并保留原因")
    void upstreamFailureDegrades() {
        adapter.toThrow = new LogAdapterException(LogAdapterException.Code.UNAVAILABLE, "connection refused");
        LogQueryService service = buildService();

        LogQueryResultVO result = service.query(dto());

        assertTrue(result.isDegraded());
        assertEquals("SOURCE_UNREACHABLE", result.getErrorCode());
        assertTrue(result.getMessage().contains("connection refused"));
    }

    @Test
    @DisplayName("关键字过滤只影响展示行，不影响游标推进")
    void keywordFiltersLines() {
        adapter.result = AdapterResult.of("info ok\nerror boom\ninfo again", 3, 3, true);
        LogQueryService service = buildService();

        LogQueryDTO dto = dto();
        dto.setKeyword("error");
        LogQueryResultVO result = service.query(dto);

        assertEquals(1, result.getLineCount());
        assertEquals("error boom", result.getContent());
        assertTrue(result.isHasMore());
        assertTrue(result.getNextCursor() != null);
    }

    @Test
    @DisplayName("游标跨源回传被拒绝")
    void cursorSourceMismatch() {
        LogQueryService service = buildService();
        LogQueryDTO dto = dto();
        dto.setCursor(new LogCursor("yarn-default", 10, null).encode());

        BusinessException error = assertThrows(BusinessException.class, () -> service.query(dto));
        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("游标"));
    }

    private LogQueryService buildService() {
        LogSourceCatalog catalog = new LogSourceCatalog(properties,
                new SecretResolver(new StandardEnvironment()), new StandardEnvironment());
        return new LogQueryService(
                properties,
                catalog,
                new LogAdapterRegistry(List.of(adapter)),
                new LogMaskingService(properties),
                new LogAccessControlService(properties),
                new LogCircuitBreaker(),
                new LogExecutorRegistry(properties),
                new LogCache(properties),
                new LogAuditService(mock(AuditLogService.class), new ObjectMapper()),
                new LogAccessMetrics(properties));
    }

    private LogQueryDTO dto() {
        LogQueryDTO dto = new LogQueryDTO();
        dto.setServiceType("ds");
        dto.setCluster("default");
        dto.setComponentRole("worker");
        dto.setLogKind("runtime");
        dto.setRef(Map.of("taskInstanceId", "1"));
        return dto;
    }

    private static final class StubAdapter implements LogAdapter {
        private AdapterResult result = AdapterResult.of("", 0, 0, false);
        private RuntimeException toThrow;

        @Override
        public boolean supports(ServiceType serviceType, LogKind logKind, ComponentRole role) {
            return serviceType == ServiceType.DS && logKind == LogKind.RUNTIME;
        }

        @Override
        public AdapterResult query(LogQueryContext context) {
            if (toThrow != null) {
                throw toThrow;
            }
            return result;
        }
    }
}
