package com.bigdata.modules.telemetry.compat;

import com.bigdata.modules.logs.dto.LogQueryDTO;
import com.bigdata.modules.logs.vo.LogQueryResultVO;
import com.bigdata.modules.telemetry.dto.TelemetryQueryRequest;
import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.TelemetryIdentity;
import com.bigdata.modules.telemetry.model.TelemetryOutcome;
import com.bigdata.modules.telemetry.service.TelemetryQueryService;
import com.bigdata.modules.telemetry.vo.QueryError;
import com.bigdata.modules.telemetry.vo.QueryMeta;
import com.bigdata.modules.telemetry.vo.TelemetryQueryResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyLogTelemetryRouterTest {

    @Test
    @DisplayName("spark/flink 请求映射到四元组，ds/yarn/file 与显式 sourceId 保持旧路径")
    void routeBoundary() {
        LegacyLogTelemetryRouter router = new LegacyLogTelemetryRouter(mock(TelemetryQueryService.class));

        assertNull(router.queryIfEngine(dto("ds", "master", "runtime")), "ds 是 legacy");
        assertNull(router.queryIfEngine(dto("yarn", "resource_manager", "runtime")), "yarn 是 legacy");
        assertNull(router.queryIfEngine(dto("file", "generic", "runtime")), "file 是 transport");
        assertNull(router.queryIfEngine(dto("spark", "driver", "system_table")), "无法映射的类型走旧实现");

        LogQueryDTO withSource = dto("spark", "driver", "runtime");
        withSource.setSourceId("spark-shs-default");
        assertNull(router.queryIfEngine(withSource), "显式 sourceId 不能被改写");
    }

    @Test
    @DisplayName("spark.driver 映射为 spark.driver + log.runtime + cluster")
    void mapsSparkDriver() {
        TelemetryQueryService telemetry = mock(TelemetryQueryService.class);
        when(telemetry.query(any())).thenReturn(okResponse("spark", "spark.driver", "log.runtime"));
        LegacyLogTelemetryRouter router = new LegacyLogTelemetryRouter(telemetry);

        LogQueryResultVO vo = router.queryIfEngine(dto("spark", "driver", "runtime"));

        ArgumentCaptor<TelemetryQueryRequest> captor = ArgumentCaptor.forClass(TelemetryQueryRequest.class);
        verify(telemetry).query(captor.capture());
        assertEquals("spark", captor.getValue().getServiceType());
        assertEquals("spark.driver", captor.getValue().getComponentId());
        assertEquals("cluster", captor.getValue().getHostId());
        assertEquals("log.runtime", captor.getValue().getDataTypeId());
        assertNotNull(vo);
        assertFalse(vo.isDegraded());
    }

    @Test
    @DisplayName("旧响应 JSON 字段集合与顺序被锁定")
    void legacyJsonContractIsFrozen() throws Exception {
        TelemetryQueryService telemetry = mock(TelemetryQueryService.class);
        when(telemetry.query(any())).thenReturn(okResponse("flink", "flink.jobmanager", "log.runtime"));
        LegacyLogTelemetryRouter router = new LegacyLogTelemetryRouter(telemetry);

        LogQueryResultVO vo = router.queryIfEngine(dto("flink", "job_manager", "runtime"));
        JsonNode node = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(vo));
        List<String> fields = new ArrayList<>();
        node.fieldNames().forEachRemaining(fields::add);

        assertEquals(List.of("sourceId", "serviceType", "cluster", "componentRole", "logKind",
                "content", "lines", "lineCount", "byteSize", "nextCursor", "hasMore",
                "truncated", "degraded", "degradationReason", "errorCode",
                "resolvedHost", "resolvedPath", "elapsedMs", "message"), fields);
        assertEquals("flink", node.get("serviceType").asText());
        assertEquals("job_manager", node.get("componentRole").asText());
        assertEquals("runtime", node.get("logKind").asText());
        assertEquals(2, node.get("lines").size());
        assertEquals(false, node.get("degraded").asBoolean());
    }

    @Test
    @DisplayName("能力不可用时还原成 degraded=true 的旧结构")
    void unavailableBecomesDegraded() {
        TelemetryQueryService telemetry = mock(TelemetryQueryService.class);
        TelemetryIdentity identity = new TelemetryIdentity("flink", "flink.jobmanager", "cluster", "log.runtime");
        when(telemetry.query(any())).thenReturn(new TelemetryQueryResponse(identity,
                TelemetryOutcome.UNAVAILABLE, false, "log-adapter", null, null,
                new QueryError(CapabilityCode.SOURCE_DISABLED, "日志源已禁用", List.of()),
                new QueryMeta(null, false, false, 3)));
        LegacyLogTelemetryRouter router = new LegacyLogTelemetryRouter(telemetry);

        LogQueryResultVO vo = router.queryIfEngine(dto("flink", "job_manager", "runtime"));

        assertTrue(vo.isDegraded());
        assertEquals("SOURCE_DISABLED", vo.getErrorCode());
        assertEquals("日志源已禁用", vo.getDegradationReason());
        assertEquals(List.of(), vo.getLines());
        assertEquals(3, vo.getElapsedMs());
    }

    private static LogQueryDTO dto(String service, String role, String kind) {
        LogQueryDTO dto = new LogQueryDTO();
        dto.setServiceType(service);
        dto.setComponentRole(role);
        dto.setLogKind(kind);
        dto.setCluster("default");
        return dto;
    }

    private static TelemetryQueryResponse okResponse(String service, String component, String dataType) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sourceId", "flink-default");
        data.put("content", "line-1\nline-2\n");
        data.put("lines", List.of("line-1", "line-2"));
        data.put("lineCount", 2);
        data.put("byteSize", 14);
        data.put("nextCursor", "c2");
        data.put("hasMore", true);
        data.put("truncated", false);
        data.put("resolvedHost", "node-1");
        data.put("resolvedPath", "/var/log/flink/app.log");
        return new TelemetryQueryResponse(new TelemetryIdentity(service, component, "cluster", dataType),
                TelemetryOutcome.OK, true, "log-adapter", "2026-11-15T00:00:00Z", data, null,
                new QueryMeta("c2", false, false, 5));
    }
}
