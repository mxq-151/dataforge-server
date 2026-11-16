package com.bigdata.modules.telemetry.service.metricdata;

import com.bigdata.modules.telemetry.util.CircuitBreaker;
import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.telemetry.service.catalog.spi.CatalogContributor;
import com.bigdata.modules.telemetry.service.metricdata.catalog.FlinkMetricSemantics;
import com.bigdata.modules.telemetry.service.metricdata.catalog.RestMetricSemanticsRegistry;
import com.bigdata.modules.telemetry.service.metricdata.catalog.SparkShsMetricSemantics;
import com.bigdata.modules.telemetry.service.catalog.TelemetryCatalogRegistry;
import com.bigdata.modules.telemetry.service.metricdata.model.DiscoveredMetric;
import com.bigdata.modules.telemetry.service.metricdata.spi.MetricDiscoveryProvider;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDiscoveryQuery;
import com.bigdata.modules.telemetry.service.metricdata.discovery.MetricDiscoveryRegistry;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDiscoveryResult;
import com.bigdata.modules.telemetry.service.metricdata.discovery.MetricDiscoveryService;
import com.bigdata.modules.telemetry.service.metadata.registry.MetadataCatalogRegistry;
import com.bigdata.modules.telemetry.config.TelemetryMetadataProperties;
import com.bigdata.modules.telemetry.dto.MetricQueryRequest;
import com.bigdata.modules.telemetry.common.model.BackendBinding;
import com.bigdata.modules.telemetry.common.model.BackendStatus;
import com.bigdata.modules.telemetry.common.model.BackendType;
import com.bigdata.modules.telemetry.vo.CatalogStatus;
import com.bigdata.modules.telemetry.vo.ComponentCatalogEntry;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDefinition;
import com.bigdata.modules.telemetry.common.model.QueryMode;
import com.bigdata.modules.telemetry.common.model.Sampling;
import com.bigdata.modules.telemetry.common.model.Target;
import com.bigdata.modules.telemetry.common.model.TimeRange;
import com.bigdata.modules.telemetry.service.routing.BackendRouter;
import com.bigdata.modules.telemetry.config.RoutingProperties;
import com.bigdata.modules.telemetry.common.model.BackendCapability;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.bigdata.modules.telemetry.service.metricdata.spi.MetricBackend;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricQueryResult;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricQuerySpec;
import com.bigdata.modules.telemetry.config.TelemetryBackendRegistry;
import com.bigdata.modules.telemetry.vo.TelemetryQueryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.bigdata.modules.telemetry.vo.MetricData;

class MetricTelemetryQueryServiceTest {

    private static final Target TARGET = new Target("kafka", "kafka.broker", "cluster");

    @Test
    @DisplayName("首选成功：outcome=ok，selected 为首选，不回退")
    void primarySucceeds() {
        FakeBackend first = new FakeBackend("a");
        FakeBackend second = new FakeBackend("b");
        TelemetryQueryResponse response = service(first, second, false).query(request());

        assertThat(response.outcome()).isEqualTo("ok");
        assertThat(response.backend().selected().backendId()).isEqualTo("a");
        assertThat(response.backend().fallback()).isFalse();
        assertThat(response.meta().degraded()).isFalse();
        assertThat(second.fetches).isZero();
        assertThat(response.data()).isInstanceOf(MetricData.class);
    }

    @Test
    @DisplayName("首选超时 + 配置批准等价组：回退成功且回退可见")
    void fallbackWhenEquivalent() {
        FakeBackend first = new FakeBackend("a");
        first.failure = CapabilityCode.UPSTREAM_TIMEOUT;
        FakeBackend second = new FakeBackend("b");
        TelemetryQueryResponse response = service(first, second, true).query(request());

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
        TelemetryQueryResponse response = service(first, second, false).query(request());

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
        TelemetryQueryResponse response = service(first, second, true).query(request());

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
        TelemetryQueryResponse response = service(first, second, true).query(request());

        assertThat(response.outcome()).isEqualTo("ok");
        assertThat(response.backend().fallback()).isFalse();
        MetricData data =
                (MetricData) response.data();
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
        TelemetryQueryResponse response =
                service(first, null, false, definition).query(request());

        assertThat(response.outcome()).isEqualTo("not_implemented");
        assertThat(response.data()).isNull();
        assertThat(response.error().code()).isEqualTo(CapabilityCode.DATA_TYPE_NOT_IMPLEMENTED);
        assertThat(response.error().requirements()).isNotEmpty();
        assertThat(first.fetches).isZero();
    }

    @Test
    @DisplayName("发现到的 counter 类型来自运行时元数据：rate=true 可正确通过")
    void discoveredCounterTypeAllowsRate() {
        FakeBackend first = new FakeBackend("a");
        FakeDiscovery discovery = new FakeDiscovery(List.of(new DiscoveredMetric(
                "kafka_new_total", "fake", "victoriametrics:label/__name__/values",
                "counter", "count")));
        TelemetryQueryResponse response =
                serviceWithDiscovery(first, discovery).query(request("kafka_new_total", true));

        assertThat(response.outcome()).isEqualTo("ok");
        assertThat(first.fetches).isEqualTo(1);
    }

    @Test
    @DisplayName("发现到但元数据没有 type：rate=true 拒绝并说明类型未知")
    void discoveredUnknownTypeRejectsRate() {
        FakeBackend first = new FakeBackend("a");
        FakeDiscovery discovery = new FakeDiscovery(List.of(new DiscoveredMetric(
                "kafka_mystery", "fake", "victoriametrics:label/__name__/values",
                null, null)));
        assertThatThrownBy(() -> serviceWithDiscovery(first, discovery)
                .query(request("kafka_mystery", true)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("类型未知");
        assertThat(first.fetches).isZero();
    }

    @Test
    @DisplayName("发现结果里没有该指标：UNAVAILABLE + METRIC_NOT_EXPOSED，不再 400 未知指标")
    void discoveredMetricMissingReturnsUnavailable() {
        FakeBackend first = new FakeBackend("a");
        FakeDiscovery discovery = new FakeDiscovery(List.of());
        TelemetryQueryResponse response =
                serviceWithDiscovery(first, discovery).query(request("kafka_missing", false));

        assertThat(response.outcome()).isEqualTo("unavailable");
        assertThat(response.error().code()).isEqualTo(CapabilityCode.METRIC_NOT_EXPOSED);
        assertThat(first.fetches).isZero();
    }

    @Test
    @DisplayName("已知语义缺少必填 ref.jobId：先报参数错误，不误报未暴露")
    void knownDefinitionMissingRefFailsBeforeDiscovery() {
        FakeBackend first = new FakeBackend("a");
        MetricDefinition definition = new MetricDefinition("m", "gauge", "count", "job",
                List.of(), List.of("jobId"), Map.of(),
                List.of(new BackendBinding("a", BackendType.PROMETHEUS,
                        BackendStatus.IMPLEMENTED, 10,
                        Set.of(QueryMode.RANGE, QueryMode.INSTANT), Set.of(Sampling.STEP),
                        true, null, null)));

        assertThatThrownBy(() -> service(first, null, false, definition).query(request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ref.jobId");
        assertThat(first.fetches).isZero();
    }

    // ------------------------------------------------------------------ 辅助

    private MetricQueryRequest request(String metric, boolean rate) {
        MetricQueryRequest request = request();
        request.setMetric(metric);
        request.setRate(rate);
        return request;
    }

    private MetricTelemetryQueryService serviceWithDiscovery(FakeBackend backend,
                                                             FakeDiscovery discovery) {
        TelemetryBackendRegistry registry = new TelemetryBackendRegistry(List.of(backend), List.of());
        RoutingProperties routing = new RoutingProperties();
        routing.getRouting().getMetric().setDefaultOrder(List.of("a"));
        BackendRouter router = new BackendRouter(routing, registry);
        RestMetricSemanticsRegistry semantics = new RestMetricSemanticsRegistry(
                new FlinkMetricSemantics(), new SparkShsMetricSemantics());
        TelemetryCatalogRegistry catalog = new TelemetryCatalogRegistry(
                List.of((CatalogContributor) () -> List.of(new ComponentCatalogEntry(
                        "kafka", "kafka.broker", List.of(), List.of(), List.of(),
                        CatalogStatus.IMPLEMENTED, List.of()))), semantics);
        return new MetricTelemetryQueryService(catalog, new MetadataCatalogRegistry(List.of()),
                router, registry, new CircuitBreaker(),
                new MetricDiscoveryService(new MetricDiscoveryRegistry(List.of(discovery)), catalog),
                new TelemetryMetadataProperties());
    }

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
            group.setServiceType("kafka");
            routing.getRouting().getEquivalenceGroups().put("g", group);
        }
        BackendRouter router = new BackendRouter(routing, registry);
        RestMetricSemanticsRegistry semantics = new RestMetricSemanticsRegistry(
                new FlinkMetricSemantics(), new SparkShsMetricSemantics());
        TelemetryCatalogRegistry catalog = new TelemetryCatalogRegistry(
                List.of((CatalogContributor) () -> List.of(new ComponentCatalogEntry(
                        "kafka", "kafka.broker", List.of(definition), List.of(), List.of(),
                        CatalogStatus.IMPLEMENTED, List.of()))), semantics);
        return new MetricTelemetryQueryService(catalog,
                new MetadataCatalogRegistry(List.of()), router, registry,
                new CircuitBreaker(),
                new MetricDiscoveryService(new MetricDiscoveryRegistry(List.of()), catalog),
                new TelemetryMetadataProperties());
    }

    private MetricDefinition definition(List<BackendBinding> bindings,
                                        Map<String, List<String>> preference) {
        return new MetricDefinition("m", "gauge", "count", "job",
                List.of(), List.of(), preference, bindings);
    }

    private MetricQueryRequest request() {
        MetricQueryRequest request = new MetricQueryRequest();
        request.setMode("range");
        request.setTarget(TARGET);
        request.setMetric("m");
        request.setTimeRange(new TimeRange(Instant.parse("2026-11-15T01:00:00Z"),
                Instant.parse("2026-11-15T02:00:00Z")));
        request.setStep("15s");
        request.setRef(Map.of());
        return request;
    }

    private static final class FakeDiscovery implements MetricDiscoveryProvider {

        private final List<DiscoveredMetric> metrics;

        private FakeDiscovery(List<DiscoveredMetric> metrics) {
            this.metrics = metrics;
        }

        @Override
        public String providerId() {
            return "fake-discovery";
        }

        @Override
        public String providerType() {
            return "test";
        }

        @Override
        public boolean supports(Target target) {
            return true;
        }

        @Override
        public MetricDiscoveryResult discover(MetricDiscoveryQuery query) {
            return MetricDiscoveryResult.supported(providerId(), metrics, List.of());
        }
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
