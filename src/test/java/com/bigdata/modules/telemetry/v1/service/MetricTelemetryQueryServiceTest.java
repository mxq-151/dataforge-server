package com.bigdata.modules.telemetry.v1.service;

import com.bigdata.modules.telemetry.shared.LogCircuitBreaker;
import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.v1.catalog.CatalogContributor;
import com.bigdata.modules.telemetry.v1.catalog.TelemetryCatalogRegistry;
import com.bigdata.modules.telemetry.v1.metadata.catalog.MetadataCatalogRegistry;
import com.bigdata.modules.telemetry.v1.dto.MetricQueryV1Request;
import com.bigdata.modules.telemetry.v1.model.BackendBinding;
import com.bigdata.modules.telemetry.v1.model.BackendStatus;
import com.bigdata.modules.telemetry.v1.model.BackendType;
import com.bigdata.modules.telemetry.v1.model.CatalogStatus;
import com.bigdata.modules.telemetry.v1.model.ComponentCatalogEntry;
import com.bigdata.modules.telemetry.v1.model.MetricDefinition;
import com.bigdata.modules.telemetry.v1.model.QueryMode;
import com.bigdata.modules.telemetry.v1.model.Sampling;
import com.bigdata.modules.telemetry.v1.model.Target;
import com.bigdata.modules.telemetry.v1.model.TimeRange;
import com.bigdata.modules.telemetry.v1.routing.BackendRouter;
import com.bigdata.modules.telemetry.v1.routing.RoutingProperties;
import com.bigdata.modules.telemetry.v1.spi.BackendCapability;
import com.bigdata.modules.telemetry.v1.spi.BackendException;
import com.bigdata.modules.telemetry.v1.spi.MetricBackend;
import com.bigdata.modules.telemetry.v1.spi.MetricQueryResult;
import com.bigdata.modules.telemetry.v1.spi.MetricQuerySpec;
import com.bigdata.modules.telemetry.v1.spi.TelemetryBackendRegistry;
import com.bigdata.modules.telemetry.v1.vo.TelemetryQueryV1Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MetricTelemetryQueryServiceTest {

    private static final Target TARGET = new Target("flink", "flink.jobmanager", "cluster");

    @Test
    @DisplayName("首选成功：outcome=ok，selected 为首选，不回退")
    void primarySucceeds() {
        FakeBackend first = new FakeBackend("a");
        FakeBackend second = new FakeBackend("b");
        TelemetryQueryV1Response response = service(first, second, false).query(request());

        assertThat(response.outcome()).isEqualTo("ok");
        assertThat(response.backend().selected().backendId()).isEqualTo("a");
        assertThat(response.backend().fallback()).isFalse();
        assertThat(response.meta().degraded()).isFalse();
        assertThat(second.fetches).isZero();
        assertThat(response.data()).isInstanceOf(TelemetryQueryV1Response.MetricData.class);
    }

    @Test
    @DisplayName("首选超时 + 配置批准等价组：回退成功且回退可见")
    void fallbackWhenEquivalent() {
        FakeBackend first = new FakeBackend("a");
        first.failure = CapabilityCode.UPSTREAM_TIMEOUT;
        FakeBackend second = new FakeBackend("b");
        TelemetryQueryV1Response response = service(first, second, true).query(request());

        assertThat(response.outcome()).isEqualTo("ok");
        assertThat(response.backend().selected().backendId()).isEqualTo("b");
        assertThat(response.backend().fallback()).isTrue();
        assertThat(response.backend().attempts()).hasSize(2);
        assertThat(response.backend().attempts().get(0).code())
                .isEqualTo(CapabilityCode.UPSTREAM_TIMEOUT);
        assertThat(response.meta().degraded()).isTrue();
        assertThat(response.meta().warnings()).extracting(warning -> warning.code())
                .contains("PRIMARY_BACKEND_UNAVAILABLE");
    }

    @Test
    @DisplayName("首选超时但未声明等价组：不回退，失败保留每个尝试")
    void noFallbackWithoutEquivalence() {
        FakeBackend first = new FakeBackend("a");
        first.failure = CapabilityCode.UPSTREAM_TIMEOUT;
        FakeBackend second = new FakeBackend("b");
        TelemetryQueryV1Response response = service(first, second, false).query(request());

        assertThat(response.outcome()).isEqualTo("failed");
        assertThat(response.error().code()).isEqualTo(CapabilityCode.UPSTREAM_TIMEOUT);
        assertThat(response.retryable()).isTrue();
        assertThat(response.backend().selected()).isNull();
        assertThat(response.backend().attempts()).hasSize(1);
        assertThat(response.backend().skipped()).extracting(skip -> skip.code())
                .contains(CapabilityCode.NOT_EQUIVALENT);
        assertThat(second.fetches).isZero();
    }

    @Test
    @DisplayName("权限错误即使有等价组也不回退，避免掩盖安全问题")
    void permissionErrorDoesNotFallback() {
        FakeBackend first = new FakeBackend("a");
        first.failure = CapabilityCode.UPSTREAM_UNAUTHORIZED;
        FakeBackend second = new FakeBackend("b");
        TelemetryQueryV1Response response = service(first, second, true).query(request());

        assertThat(response.outcome()).isEqualTo("failed");
        assertThat(response.error().code()).isEqualTo(CapabilityCode.UPSTREAM_UNAUTHORIZED);
        assertThat(response.retryable()).isFalse();
        assertThat(second.fetches).isZero();
    }

    @Test
    @DisplayName("真实空结果不回退：resultState=empty，备选不被调用")
    void emptyResultIsNotFailure() {
        FakeBackend first = new FakeBackend("a");
        first.empty = true;
        FakeBackend second = new FakeBackend("b");
        TelemetryQueryV1Response response = service(first, second, true).query(request());

        assertThat(response.outcome()).isEqualTo("ok");
        assertThat(response.backend().fallback()).isFalse();
        TelemetryQueryV1Response.MetricData data =
                (TelemetryQueryV1Response.MetricData) response.data();
        assertThat(data.resultState()).isEqualTo("empty");
        assertThat(second.fetches).isZero();
    }

    @Test
    @DisplayName("只有声明未实现的后端：NOT_IMPLEMENTED + requirements，不返回空数组")
    void declaredNotImplemented() {
        FakeBackend first = new FakeBackend("a");
        MetricDefinition definition = definition(List.of(
                new BackendBinding("flink-rest", BackendType.FLINK_REST,
                        BackendStatus.DECLARED_NOT_IMPLEMENTED, 10,
                        Set.of(QueryMode.RANGE, QueryMode.INSTANT), Set.of(Sampling.RAW),
                        true, null, "未实现")), Map.of());
        TelemetryQueryV1Response response =
                service(first, null, false, definition).query(request());

        assertThat(response.outcome()).isEqualTo("not_implemented");
        assertThat(response.data()).isNull();
        assertThat(response.error().code()).isEqualTo(CapabilityCode.DATA_TYPE_NOT_IMPLEMENTED);
        assertThat(response.error().requirements()).isNotEmpty();
        assertThat(first.fetches).isZero();
    }

    // ------------------------------------------------------------------ 辅助

    private MetricTelemetryQueryService service(FakeBackend first, FakeBackend second,
                                                boolean ratifyEquivalence) {
        return service(first, second, ratifyEquivalence, definition(List.of(
                new BackendBinding("a", BackendType.PROMETHEUS, BackendStatus.IMPLEMENTED, 10,
                        Set.of(QueryMode.RANGE, QueryMode.INSTANT), Set.of(Sampling.STEP),
                        true, "g", null),
                new BackendBinding("b", BackendType.VICTORIAMETRICS, BackendStatus.IMPLEMENTED, 20,
                        Set.of(QueryMode.RANGE, QueryMode.INSTANT), Set.of(Sampling.STEP),
                        false, "g", null)),
                Map.of("range", List.of("a", "b"))));
    }

    private MetricTelemetryQueryService service(FakeBackend first, FakeBackend second,
                                                boolean ratifyEquivalence,
                                                MetricDefinition definition) {
        TelemetryBackendRegistry registry = new TelemetryBackendRegistry(
                second == null ? List.of(first) : List.of(first, second), List.of());
        RoutingProperties routing = new RoutingProperties();
        routing.getRouting().getMetric().setDefaultOrder(List.of("a", "b"));
        if (ratifyEquivalence) {
            RoutingProperties.EquivalenceGroup group = new RoutingProperties.EquivalenceGroup();
            group.setBackends(List.of("a", "b"));
            group.setServiceType("flink");
            routing.getRouting().getEquivalenceGroups().put("g", group);
        }
        BackendRouter router = new BackendRouter(routing, registry);
        TelemetryCatalogRegistry catalog = new TelemetryCatalogRegistry(
                List.of((CatalogContributor) () -> List.of(new ComponentCatalogEntry(
                        "flink", "flink.jobmanager", List.of(definition), List.of(), List.of(),
                        CatalogStatus.IMPLEMENTED, List.of()))));
        return new MetricTelemetryQueryService(catalog,
                new MetadataCatalogRegistry(List.of()), router, registry,
                new LogCircuitBreaker());
    }

    private MetricDefinition definition(List<BackendBinding> bindings,
                                        Map<String, List<String>> preference) {
        return new MetricDefinition("m", "gauge", "count", "job",
                List.of(), List.of(), preference, bindings);
    }

    private MetricQueryV1Request request() {
        MetricQueryV1Request request = new MetricQueryV1Request();
        request.setMode("range");
        request.setTarget(TARGET);
        request.setMetric("m");
        request.setTimeRange(new TimeRange(Instant.parse("2026-11-15T01:00:00Z"),
                Instant.parse("2026-11-15T02:00:00Z")));
        request.setStep("15s");
        request.setRef(Map.of());
        return request;
    }

    private static final class FakeBackend implements MetricBackend {

        private final String id;
        private CapabilityCode failure;
        private boolean empty;
        private int fetches;

        private FakeBackend(String id) {
            this.id = id;
        }

        @Override
        public String backendId() {
            return id;
        }

        @Override
        public BackendType backendType() {
            return "a".equals(id) ? BackendType.PROMETHEUS : BackendType.VICTORIAMETRICS;
        }

        @Override
        public BackendCapability evaluate(MetricQuerySpec spec) {
            return BackendCapability.available();
        }

        @Override
        public MetricQueryResult fetch(MetricQuerySpec spec) {
            fetches++;
            if (failure != null) {
                throw new BackendException(failure, "boom");
            }
            if (empty) {
                return new MetricQueryResult(List.of(), false, List.of());
            }
            return new MetricQueryResult(List.of(new MetricQueryResult.Series(Map.of("k", "v"),
                    List.of(new MetricQueryResult.Sample(Instant.parse("2026-11-15T01:00:00Z"), 1.0)))),
                    false, List.of());
        }
    }
}
