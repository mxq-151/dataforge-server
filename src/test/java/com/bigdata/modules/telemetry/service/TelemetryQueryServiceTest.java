package com.bigdata.modules.telemetry.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.telemetry.dto.TelemetryQueryRequest;
import com.bigdata.modules.telemetry.model.Capability;
import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.DataTypes;
import com.bigdata.modules.telemetry.model.Requirement;
import com.bigdata.modules.telemetry.model.RequirementKind;
import com.bigdata.modules.telemetry.model.TelemetryOutcome;
import com.bigdata.modules.telemetry.provider.CapabilityContext;
import com.bigdata.modules.telemetry.provider.TelemetryProvider;
import com.bigdata.modules.telemetry.provider.TelemetryProviderRegistry;
import com.bigdata.modules.telemetry.provider.TelemetryQueryContext;
import com.bigdata.modules.telemetry.provider.TelemetryQueryException;
import com.bigdata.modules.telemetry.vo.TelemetryQueryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryQueryServiceTest {

    @Test
    @DisplayName("声明未实现的数据类型：NOT_IMPLEMENTED、data=null、绝不调用 provider")
    void unimplementedNeverCallsProvider() {
        CountingProvider provider = new CountingProvider(Capability.available("x", "p", List.of()));
        TelemetryQueryService service = serviceWith(provider);

        TelemetryQueryResponse response = service.query(request("doris", "doris.fe", DataTypes.OS_CPU));

        assertEquals(TelemetryOutcome.NOT_IMPLEMENTED, response.outcome());
        assertFalse(response.retryable());
        assertNull(response.data());
        assertEquals(CapabilityCode.DATA_TYPE_NOT_IMPLEMENTED, response.error().code());
        assertEquals(0, provider.fetches.get());
        assertEquals(0, provider.evaluations.get());
    }

    @Test
    @DisplayName("能力不可用：UNAVAILABLE、不可重试、data=null、不调用取数")
    void unavailableIsNotFetched() {
        CountingProvider provider = new CountingProvider(Capability.unavailable(DataTypes.LOG_RUNTIME,
                CapabilityCode.SOURCE_DISABLED, "日志源已禁用", "p", List.of("file"),
                List.of(new Requirement(RequirementKind.CONFIG, "logs.sources[flink].enabled", "启用"))));
        TelemetryQueryService service = serviceWith(provider);

        TelemetryQueryResponse response = service.query(request("flink", "flink.jobmanager",
                DataTypes.LOG_RUNTIME));

        assertEquals(TelemetryOutcome.UNAVAILABLE, response.outcome());
        assertFalse(response.retryable());
        assertNull(response.data());
        assertEquals(CapabilityCode.SOURCE_DISABLED, response.error().code());
        assertEquals(0, provider.fetches.get());
    }

    @Test
    @DisplayName("取数超时：FAILED 且可重试")
    void failureIsRetryable() {
        CountingProvider provider = new CountingProvider(Capability.available("x", "p", List.of())) {
            @Override
            public Map<String, Object> fetch(TelemetryQueryContext context) {
                fetches.incrementAndGet();
                throw new TelemetryQueryException(CapabilityCode.UPSTREAM_TIMEOUT, "上游超时");
            }
        };
        TelemetryQueryService service = serviceWith(provider);

        TelemetryQueryResponse response = service.query(request("flink", "flink.jobmanager",
                DataTypes.LOG_RUNTIME));

        assertEquals(TelemetryOutcome.FAILED, response.outcome());
        assertTrue(response.retryable());
        assertNull(response.data());
        assertEquals(CapabilityCode.UPSTREAM_TIMEOUT, response.error().code());
        assertEquals(1, provider.fetches.get());
    }

    @Test
    @DisplayName("取数成功：OK + data + 游标元信息")
    void successReturnsPayload() {
        CountingProvider provider = new CountingProvider(Capability.available("x", "p", List.of())) {
            @Override
            public Map<String, Object> fetch(TelemetryQueryContext context) {
                fetches.incrementAndGet();
                return Map.of("lines", List.of("a", "b"), "nextCursor", "c1", "truncated", true);
            }
        };
        TelemetryQueryService service = serviceWith(provider);

        TelemetryQueryResponse response = service.query(request("flink", "flink.jobmanager",
                DataTypes.LOG_RUNTIME));

        assertEquals(TelemetryOutcome.OK, response.outcome());
        assertTrue(response.retryable());
        assertEquals("c1", response.meta().cursor());
        assertTrue(response.meta().truncated());
        assertEquals(List.of("a", "b"), response.data().get("lines"));
    }

    @Test
    @DisplayName("host 未纳管：UNAVAILABLE + HOST_NOT_MANAGED，不调用 provider")
    void unknownHostIsNotManaged() {
        CountingProvider provider = new CountingProvider(Capability.available("x", "p", List.of()));
        TelemetryQueryService service = serviceWith(provider);
        TelemetryQueryRequest request = request("flink", "flink.jobmanager", DataTypes.LOG_RUNTIME);
        request.setHostId("node-1");

        TelemetryQueryResponse response = service.query(request);

        assertEquals(TelemetryOutcome.UNAVAILABLE, response.outcome());
        assertEquals(CapabilityCode.HOST_NOT_MANAGED, response.error().code());
        assertEquals(0, provider.evaluations.get());
    }

    @Test
    @DisplayName("身份非法：400，而不是伪造 UNAVAILABLE")
    void invalidIdentityIsBadRequest() {
        TelemetryQueryService service = serviceWith(new CountingProvider(
                Capability.available("x", "p", List.of())));

        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.query(request("no-such-engine", "x.y", DataTypes.LOG_RUNTIME))).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.query(request("flink", "flink.jobmanager", "no.such.type"))).getCode());

        TelemetryQueryRequest missing = new TelemetryQueryRequest();
        missing.setServiceType("flink");
        assertEquals(400, assertThrows(BusinessException.class, () -> service.query(missing)).getCode());
    }

    private static TelemetryQueryService serviceWith(TelemetryProvider provider) {
        return new TelemetryQueryService(new TelemetryProviderRegistry(List.of(provider)));
    }

    private static TelemetryQueryRequest request(String service, String component, String dataType) {
        TelemetryQueryRequest request = new TelemetryQueryRequest();
        request.setServiceType(service);
        request.setComponentId(component);
        request.setHostId("cluster");
        request.setDataTypeId(dataType);
        return request;
    }

    private static class CountingProvider implements TelemetryProvider {
        final AtomicInteger evaluations = new AtomicInteger();
        final AtomicInteger fetches = new AtomicInteger();
        private final Capability capability;

        CountingProvider(Capability capability) {
            this.capability = capability;
        }

        @Override
        public String providerId() {
            return "counting";
        }

        @Override
        public boolean supports(String serviceType, String componentId, String dataTypeId) {
            return true;
        }

        @Override
        public Capability evaluate(CapabilityContext context) {
            evaluations.incrementAndGet();
            return capability;
        }

        @Override
        public Map<String, Object> fetch(TelemetryQueryContext context) {
            fetches.incrementAndGet();
            return Map.of();
        }
    }
}
