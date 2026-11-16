package com.bigdata.modules.telemetry.service.metadata;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.Requirement;
import com.bigdata.modules.telemetry.common.model.RequirementKind;
import com.bigdata.modules.telemetry.common.model.CapabilityCode;
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
import com.bigdata.modules.telemetry.config.TelemetryMetadataProperties;
import com.bigdata.modules.telemetry.dto.MetadataTarget;
import com.bigdata.modules.telemetry.vo.MetricNameView;
import com.bigdata.modules.telemetry.common.model.Target;
import com.bigdata.modules.telemetry.dto.MetadataQueryRequest;
import com.bigdata.modules.telemetry.dto.MetadataTarget;
import com.bigdata.modules.telemetry.vo.TargetAttempt;
import com.bigdata.modules.telemetry.service.metadata.registry.ServiceMetadataProviderRegistry;
import com.bigdata.modules.telemetry.service.metadata.registry.TargetDiscoveryProviderRegistry;
import com.bigdata.modules.telemetry.service.metadata.model.RawTarget;
import com.bigdata.modules.telemetry.service.metadata.spi.TargetDiscoveryProvider;
import com.bigdata.modules.telemetry.service.metadata.model.TargetDiscoveryQuery;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataCapability;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataFetchResult;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataQuery;
import com.bigdata.modules.telemetry.service.metadata.model.RawConfigEntry;
import com.bigdata.modules.telemetry.vo.VersionView;
import com.bigdata.modules.telemetry.service.metadata.model.RawVersion;
import com.bigdata.modules.telemetry.service.metadata.spi.ServiceMetadataProvider;
import com.bigdata.modules.telemetry.service.metadata.support.MetadataMasker;
import com.bigdata.modules.telemetry.service.metadata.support.VersionNormalizer;
import com.bigdata.modules.telemetry.vo.MetadataQueryResponse;
import com.bigdata.modules.telemetry.common.model.Target;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.bigdata.modules.telemetry.service.metadata.support.SensitiveDataMasker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetadataTelemetryQueryServiceTest {

    private static final MetadataTarget CH_1 = new MetadataTarget(
            new Target("clickhouse", "clickhouse.server", "ch-1"), null);
    private static final MetadataTarget CH_2 = new MetadataTarget(
            new Target("clickhouse", "clickhouse.server", "ch-2"), null);

    @Test
    @DisplayName("单 target 成功：版本标准化 + 配置脱敏 + attempts 记录")
    void singleTargetSuccess() {
        MetadataTelemetryQueryService service = service(provider("clickhouse-http", true, query ->
                new MetadataFetchResult(
                        List.of(new RawVersion("clickhouse.server", "ch-1", "24.8.14.39", null,
                                "ClickHouse", "clickhouse-server", "clickhouse:version()")),
                        List.of(
                                new RawConfigEntry("server_settings", "max_connections", "4096",
                                        "UInt64", false, "clickhouse:system.server_settings"),
                                new RawConfigEntry("server_settings", "auth_token", "plain-token",
                                        "String", true,
                                        "clickhouse:system.server_settings")),
                        List.of())));

        MetadataQueryResponse response = service.query(request("both", CH_1));

        assertThat(response.outcome()).isEqualTo("ok");
        assertThat(response.meta().partial()).isFalse();
        assertThat(response.data().versions()).singleElement().satisfies(version -> {
            assertThat(version.rawVersion()).isEqualTo("24.8.14.39");
            assertThat(version.normalized().semver()).isEqualTo("24.8.14");
            assertThat(version.normalized().build()).isEqualTo("39");
        });
        assertThat(response.data().configs()).hasSize(2);
        assertThat(response.data().configs()).filteredOn(config -> "auth_token".equals(config.key()))
                .singleElement()
                .satisfies(config -> {
                    assertThat(config.value()).isEqualTo("***");
                    assertThat(config.redacted()).isTrue();
                });
        assertThat(response.providers().attempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.providerId()).isEqualTo("clickhouse-http");
            assertThat(attempt.outcome()).isEqualTo(TargetAttempt.OK);
            assertThat(attempt.code()).isEqualTo(CapabilityCode.OK);
        });
        assertThat(response.providers().selected()).singleElement()
                .satisfies(selected -> assertThat(selected.providerId())
                        .isEqualTo("clickhouse-http"));
    }

    @Test
    @DisplayName("scope=both：只支持 config 的 provider 也出配置，版本显式 NOT_SUPPORTED")
    void bothScopeKeepsConfigWhenVersionUnsupported() {
        MetadataTarget target = new MetadataTarget(
                new Target("clickhouse", "clickhouse.server", "ch-1"), Map.of());
        ServiceMetadataProvider configOnly = new FakeProvider("config-only", true,
                new MetadataCapability("config-only", "config-only", Set.of("clickhouse"),
                        Set.of("clickhouse.server"), false, true, false, 20,
                        List.of(), List.of()),
                query -> new MetadataFetchResult(List.of(),
                        List.of(new RawConfigEntry("server_settings", "max_memory_usage",
                                "10000000000", "string", false, "test")),
                        List.of()));

        MetadataQueryResponse response = service(configOnly).query(request("both", target));

        assertThat(response.outcome()).isEqualTo("ok");
        assertThat(response.data().configs()).singleElement()
                .satisfies(config -> assertThat(config.key()).isEqualTo("max_memory_usage"));
        assertThat(response.data().versions()).singleElement().satisfies(version -> {
            assertThat(version.availability()).isEqualTo(VersionView.NOT_SUPPORTED);
            assertThat(version.rawVersion()).isNull();
            assertThat(version.reason()).contains("不支持版本");
        });
        assertThat(response.meta().warnings())
                .anySatisfy(warning -> assertThat(warning.code()).isEqualTo("SCOPE_NOT_SUPPORTED"));
    }

    @Test
    @DisplayName("部分 target 失败：ok + partial=true，失败项逐条进 attempts")
    void partialFailure() {
        MetadataTelemetryQueryService service = service(provider("doris-fe", true, query -> {
            if ("ch-2".equals(query.target().target().hostId())) {
                throw new BackendException(CapabilityCode.UPSTREAM_TIMEOUT, "连接超时");
            }
            return new MetadataFetchResult(
                    List.of(new RawVersion("clickhouse.server", "ch-1", "24.8.14.39", null,
                            "ClickHouse", "clickhouse-server", "clickhouse:version()")),
                    List.of(), List.of());
        }));

        MetadataQueryResponse response = service.query(request("version", CH_1, CH_2));

        assertThat(response.outcome()).isEqualTo("ok");
        assertThat(response.meta().partial()).isTrue();
        assertThat(response.providers().attempts()).hasSize(2);
        assertThat(response.providers().attempts())
                .filteredOn(attempt -> TargetAttempt.UNAVAILABLE.equals(attempt.outcome()))
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.target().target().hostId()).isEqualTo("ch-2");
                    assertThat(attempt.code()).isEqualTo(CapabilityCode.UPSTREAM_TIMEOUT);
                    assertThat(attempt.reason()).isEqualTo("连接超时");
                });
    }

    @Test
    @DisplayName("全部失败且都是可用性类：unavailable + retryable")
    void allAvailabilityFailures() {
        MetadataTelemetryQueryService service = service(provider("ambari", true, query -> {
            throw new BackendException(CapabilityCode.UPSTREAM_ERROR, "502 Bad Gateway");
        }));

        MetadataQueryResponse response = service.query(request("both", CH_1));

        assertThat(response.outcome()).isEqualTo("unavailable");
        assertThat(response.retryable()).isTrue();
        assertThat(response.data()).isNull();
        assertThat(response.error().code()).isEqualTo(CapabilityCode.UPSTREAM_ERROR);
        assertThat(response.providers().attempts()).singleElement()
                .satisfies(attempt -> assertThat(attempt.outcome())
                        .isEqualTo(TargetAttempt.UNAVAILABLE));
    }

    @Test
    @DisplayName("全部失败且是权限类：failed，不冒充 unavailable")
    void allSemanticFailures() {
        MetadataTelemetryQueryService service = service(provider("ambari", true, query -> {
            throw new BackendException(CapabilityCode.UPSTREAM_UNAUTHORIZED, "401",
                    List.of(new Requirement(RequirementKind.CREDENTIAL, "ambari.auth",
                            "配置 Ambari 账号密码")));
        }));

        MetadataQueryResponse response = service.query(request("both", CH_1));

        assertThat(response.outcome()).isEqualTo("failed");
        assertThat(response.retryable()).isFalse();
        assertThat(response.error().code()).isEqualTo(CapabilityCode.UPSTREAM_UNAUTHORIZED);
        assertThat(response.error().requirements()).extracting(Requirement::kind)
                .containsExactly(RequirementKind.CREDENTIAL);
        assertThat(response.providers().attempts()).singleElement()
                .satisfies(attempt -> assertThat(attempt.outcome())
                        .isEqualTo(TargetAttempt.FAILED));
    }

    @Test
    @DisplayName("没有 provider 匹配：not_implemented + IMPLEMENTATION 补齐项")
    void noProviderMatched() {
        MetadataTelemetryQueryService service = service();

        MetadataQueryResponse response = service.query(request("both", CH_1));

        assertThat(response.outcome()).isEqualTo("not_implemented");
        assertThat(response.data()).isNull();
        assertThat(response.error().code()).isEqualTo(CapabilityCode.NO_PROVIDER);
        assertThat(response.error().requirements()).extracting(Requirement::kind)
                .containsExactly(RequirementKind.IMPLEMENTATION);
    }

    @Test
    @DisplayName("provider 未启用：unauthorized 不是答案，返回 NOT_CONFIGURED")
    void disabledProvider() {
        MetadataTelemetryQueryService service = service(provider("manual", false, query ->
                MetadataFetchResult.empty()));

        MetadataQueryResponse response = service.query(request("both", CH_1));

        assertThat(response.outcome()).isEqualTo("unavailable");
        assertThat(response.error().code()).isEqualTo(CapabilityCode.NOT_CONFIGURED);
        assertThat(response.providers().skipped()).singleElement()
                .satisfies(skip -> assertThat(skip.reason()).isEqualTo("未启用"));
    }

    @Test
    @DisplayName("配置条数超过 limit 时截断并标记 truncated")
    void truncatesConfigs() {
        List<RawConfigEntry> configs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            configs.add(new RawConfigEntry("fe_config", "k" + i, "v" + i, "string", false,
                    "doris:ADMIN SHOW FRONTEND CONFIG"));
        }
        MetadataTelemetryQueryService service = service(provider("doris-fe", true, query ->
                new MetadataFetchResult(List.of(), configs, List.of())));
        MetadataQueryRequest request = request("config", CH_1);
        request.setLimit(2);

        MetadataQueryResponse response = service.query(request);

        assertThat(response.data().configs()).hasSize(2);
        assertThat(response.meta().truncated()).isTrue();
    }

    @Test
    @DisplayName("版本取不到时不伪造：rawVersion=null + UNAVAILABLE + reason")
    void missingVersionIsUnavailable() {
        MetadataTelemetryQueryService service = service(provider("ambari", true, query ->
                new MetadataFetchResult(List.of(), List.of(), List.of())));

        MetadataQueryResponse response = service.query(request("version", CH_1));

        assertThat(response.data().versions()).singleElement().satisfies(version -> {
            assertThat(version.rawVersion()).isNull();
            assertThat(version.normalized()).isNull();
            assertThat(version.availability()).isEqualTo("UNAVAILABLE");
            assertThat(version.reason()).isEqualTo("PROVIDER_RETURNED_NO_VERSION");
        });
    }

    @Test
    @DisplayName("无法解析的版本：raw 原样保留，normalized=null，reason=VERSION_UNPARSEABLE")
    void unparseableVersionKeepsRaw() {
        MetadataTelemetryQueryService service = service(provider("manual", true, query ->
                new MetadataFetchResult(List.of(new RawVersion("clickhouse.server", "ch-1",
                        "unknown-build", null, null, null, "manual:entries")),
                        List.of(), List.of())));

        MetadataQueryResponse response = service.query(request("version", CH_1));

        assertThat(response.outcome()).isEqualTo("ok");
        assertThat(response.data().versions()).singleElement().satisfies(version -> {
            assertThat(version.rawVersion()).isEqualTo("unknown-build");
            assertThat(version.normalized()).isNull();
            assertThat(version.availability()).isEqualTo("AVAILABLE");
            assertThat(version.reason()).isEqualTo("VERSION_UNPARSEABLE");
        });
    }

    @Test
    @DisplayName("attempts 不携带原始配置值")
    void attemptsDoNotLeakValues() {
        MetadataTelemetryQueryService service = service(provider("doris-fe", true, query -> {
            throw new BackendException(CapabilityCode.NOT_FOUND, "集群不存在");
        }));

        MetadataQueryResponse response = service.query(request("both", CH_1));

        assertThat(response.providers().attempts()).allSatisfy(attempt ->
                assertThat(attempt.reason()).doesNotContain("auth_token").doesNotContain("secret"));
    }

    @Test
    @DisplayName("请求校验：targets 为空 / scope 非法 / limit 越界 / 未知组件")
    void validatesRequest() {
        MetadataTelemetryQueryService service = service();

        assertThatThrownBy(() -> service.query(request("both")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("targets");
        assertThatThrownBy(() -> service.query(request("everything", CH_1)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("scope");
        MetadataQueryRequest badLimit = request("both", CH_1);
        badLimit.setLimit(100000);
        assertThatThrownBy(() -> service.query(badLimit))
                .isInstanceOf(BusinessException.class).hasMessageContaining("limit");
        assertThatThrownBy(() -> service.query(request("both", new MetadataTarget(
                new Target("clickhouse", "clickhouse.nope", "ch-1"), null))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("组件不属于该服务");
    }

    // ------------------------------------------------------------------ 辅助

    @Test
    @DisplayName("scope=metrics：返回运行时发现的指标名与来源")
    void metricsScopeReturnsDiscoveredNames() {
        MetadataTelemetryQueryService service = serviceWithDiscovery(
                new FakeDiscovery(List.of(
                        new DiscoveredMetric("Status.JVM.Memory.Heap.Used", "flink-rest-discovery",
                                "flink:taskmanagers/tm-1/metrics", null, null),
                        new DiscoveredMetric("Status.JVM.Threads.Count", "flink-rest-discovery",
                                "flink:taskmanagers/tm-1/metrics", null, null))));
        MetadataQueryRequest request = new MetadataQueryRequest();
        request.setTargets(List.of(new MetadataTarget(
                new Target("flink", "flink.taskmanager", "cluster"), Map.of())));
        request.setScope("metrics");

        MetadataQueryResponse response = service.query(request);

        assertThat(response.outcome()).isEqualTo("ok");
        assertThat(response.data().metrics()).hasSize(2);
        assertThat(response.data().metrics()).extracting(MetricNameView::name)
                .containsExactly("Status.JVM.Memory.Heap.Used", "Status.JVM.Threads.Count");
        assertThat(response.data().metrics()).allSatisfy(metric ->
                assertThat(metric.providerId()).isEqualTo("flink-rest-discovery"));
    }

    @Test
    @DisplayName("scope=metrics 无发现能力：UNAVAILABLE + METRIC_DISCOVERY_UNSUPPORTED")
    void metricsScopeWithoutDiscoveryIsUnavailable() {
        MetadataTelemetryQueryService service = serviceWithDiscovery(null);
        MetadataQueryRequest request = new MetadataQueryRequest();
        request.setTargets(List.of(new MetadataTarget(
                new Target("spark", "spark.driver", "cluster"), Map.of())));
        request.setScope("metrics");

        MetadataQueryResponse response = service.query(request);

        assertThat(response.outcome()).isEqualTo("unavailable");
        assertThat(response.error().code()).isEqualTo(CapabilityCode.METRIC_DISCOVERY_UNSUPPORTED);
    }

    @Test
    @DisplayName("scope=targets：唯一命中返回 applicationId")
    void targetsSingleMatch() {
        MetadataQueryRequest request = new MetadataQueryRequest();
        request.setScope("targets");
        request.setTaskName("etl_a");

        MetadataQueryResponse response = serviceWithTargets(
                new FakeTargets(List.of(new RawTarget("spark", "spark.driver",
                        Map.of("applicationId", "app-1"), "etl_a", "COMPLETED"))))
                .query(request);

        assertThat(response.outcome()).isEqualTo("ok");
        assertThat(response.mode()).isEqualTo("targets");
        assertThat(response.data().targets()).singleElement().satisfies(target -> {
            assertThat(target.serviceType()).isEqualTo("spark");
            assertThat(target.ref()).containsEntry("applicationId", "app-1");
            assertThat(target.state()).isEqualTo("COMPLETED");
        });
    }

    @Test
    @DisplayName("scope=targets：不传 taskName 时返回清单，不按歧义处理")
    void targetsList() {
        MetadataQueryRequest request = new MetadataQueryRequest();
        request.setScope("targets");

        MetadataQueryResponse response = serviceWithTargets(
                new FakeTargets(List.of(
                        new RawTarget("spark", "spark.driver",
                                Map.of("applicationId", "app-1"), "etl_a", "COMPLETED"),
                        new RawTarget("spark", "spark.driver",
                                Map.of("applicationId", "app-2"), "etl_b", "RUNNING"))))
                .query(request);

        assertThat(response.outcome()).isEqualTo("ok");
        assertThat(response.data().targets()).hasSize(2);
    }

    @Test
    @DisplayName("scope=targets：多条命中返回 AMBIGUOUS_REFERENCE，不随便挑一条")
    void targetsAmbiguous() {
        MetadataQueryRequest request = new MetadataQueryRequest();
        request.setScope("targets");
        request.setTaskName("etl");

        MetadataQueryResponse response = serviceWithTargets(
                new FakeTargets(List.of(
                        new RawTarget("spark", "spark.driver",
                                Map.of("applicationId", "app-1"), "etl_a", "COMPLETED"),
                        new RawTarget("spark", "spark.driver",
                                Map.of("applicationId", "app-2"), "etl_b", "RUNNING"))))
                .query(request);

        assertThat(response.outcome()).isEqualTo("failed");
        assertThat(response.error().code()).isEqualTo(CapabilityCode.AMBIGUOUS_REFERENCE);
        assertThat(response.error().message()).contains("app-1").contains("app-2");
        assertThat(response.data()).isNull();
    }

    @Test
    @DisplayName("scope=targets：limit 小于命中数时仍按歧义拒绝，不能截断成唯一命中")
    void targetsAmbiguousEvenWhenLimitTruncates() {
        MetadataQueryRequest request = new MetadataQueryRequest();
        request.setScope("targets");
        request.setTaskName("etl");
        request.setLimit(1);

        MetadataQueryResponse response = serviceWithTargets(
                new FakeTargets(List.of(
                        new RawTarget("spark", "spark.driver",
                                Map.of("applicationId", "app-1"), "etl_a", "COMPLETED"),
                        new RawTarget("spark", "spark.driver",
                                Map.of("applicationId", "app-2"), "etl_b", "RUNNING"))))
                .query(request);

        assertThat(response.outcome()).isEqualTo("failed");
        assertThat(response.error().code()).isEqualTo(CapabilityCode.AMBIGUOUS_REFERENCE);
        // 命中总数必须是真实值（2），候选列表不能被 limit=1 切掉
        assertThat(response.error().message()).contains("命中 2 条")
                .contains("app-1").contains("app-2");
        assertThat(response.meta().truncated()).isFalse();
    }

    @Test
    @DisplayName("scope=targets：不传 taskName 时 limit 仍生效并标记 truncated")
    void targetsListTruncatedByLimit() {
        MetadataQueryRequest request = new MetadataQueryRequest();
        request.setScope("targets");
        request.setLimit(1);

        MetadataQueryResponse response = serviceWithTargets(
                new FakeTargets(List.of(
                        new RawTarget("spark", "spark.driver",
                                Map.of("applicationId", "app-1"), "etl_a", "COMPLETED"),
                        new RawTarget("spark", "spark.driver",
                                Map.of("applicationId", "app-2"), "etl_b", "RUNNING"))))
                .query(request);

        assertThat(response.outcome()).isEqualTo("ok");
        assertThat(response.data().targets()).hasSize(1);
        assertThat(response.meta().truncated()).isTrue();
        assertThat(response.meta().warnings())
                .anySatisfy(warning -> assertThat(warning.code()).isEqualTo("RESULT_TRUNCATED"));
    }

    @Test
    @DisplayName("scope=targets：provider 全部未启用返回 unavailable + requirements")
    void targetsNotConfigured() {
        MetadataQueryRequest request = new MetadataQueryRequest();
        request.setScope("targets");

        MetadataQueryResponse response = serviceWithTargets(
                new FakeTargets(List.of(), false)).query(request);

        assertThat(response.outcome()).isEqualTo("unavailable");
        assertThat(response.error().code()).isEqualTo(CapabilityCode.NOT_CONFIGURED);
        assertThat(response.error().requirements()).isNotEmpty();
    }

    private MetadataTelemetryQueryService serviceWithTargets(TargetDiscoveryProvider... providers) {
        TelemetryCatalogRegistry catalog = new TelemetryCatalogRegistry(
                List.of(), new RestMetricSemanticsRegistry(
                        new FlinkMetricSemantics(), new SparkShsMetricSemantics()));
        return new MetadataTelemetryQueryService(
                new ServiceMetadataProviderRegistry(List.of()),
                new TargetDiscoveryProviderRegistry(List.of(providers)),
                new MetadataMasker(new SensitiveDataMasker()),
                new VersionNormalizer(),
                new TelemetryMetadataProperties(),
                new MetricDiscoveryService(new MetricDiscoveryRegistry(List.of()), catalog));
    }

    private static final class FakeTargets implements TargetDiscoveryProvider {

        private final List<RawTarget> targets;
        private final boolean enabled;

        private FakeTargets(List<RawTarget> targets) {
            this(targets, true);
        }

        private FakeTargets(List<RawTarget> targets, boolean enabled) {
            this.targets = targets;
            this.enabled = enabled;
        }

        @Override
        public String providerId() {
            return "fake-targets";
        }

        @Override
        public Set<String> serviceTypes() {
            return Set.of("spark");
        }

        @Override
        public Set<String> componentIds() {
            return Set.of("spark.driver");
        }

        @Override
        public int order() {
            return 10;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public List<RawTarget> discover(TargetDiscoveryQuery query) {
            return targets;
        }
    }

    private MetadataTelemetryQueryService serviceWithDiscovery(MetricDiscoveryProvider provider) {
        TelemetryCatalogRegistry catalog = new TelemetryCatalogRegistry(
                List.of(), new RestMetricSemanticsRegistry(
                        new FlinkMetricSemantics(), new SparkShsMetricSemantics()));
        List<MetricDiscoveryProvider> providers = provider == null
                ? List.of() : List.of(provider);
        return new MetadataTelemetryQueryService(
                new ServiceMetadataProviderRegistry(List.of()),
                new TargetDiscoveryProviderRegistry(List.of()),
                new MetadataMasker(new SensitiveDataMasker()),
                new VersionNormalizer(),
                new TelemetryMetadataProperties(),
                new MetricDiscoveryService(new MetricDiscoveryRegistry(providers), catalog));
    }

    private static final class FakeDiscovery implements MetricDiscoveryProvider {

        private final List<DiscoveredMetric> metrics;

        private FakeDiscovery(List<DiscoveredMetric> metrics) {
            this.metrics = metrics;
        }

        @Override
        public String providerId() {
            return "flink-rest-discovery";
        }

        @Override
        public String providerType() {
            return "flink-rest";
        }

        @Override
        public boolean supports(Target target) {
            return "flink".equals(target.serviceType());
        }

        @Override
        public MetricDiscoveryResult discover(MetricDiscoveryQuery query) {
            return MetricDiscoveryResult.supported(providerId(), metrics, List.of());
        }
    }

    private MetadataTelemetryQueryService service(ServiceMetadataProvider... providers) {
        TelemetryCatalogRegistry catalog = new TelemetryCatalogRegistry(
                List.of(), new RestMetricSemanticsRegistry(
                        new FlinkMetricSemantics(), new SparkShsMetricSemantics()));
        return new MetadataTelemetryQueryService(
                new ServiceMetadataProviderRegistry(List.of(providers)),
                new TargetDiscoveryProviderRegistry(List.of()),
                new MetadataMasker(new SensitiveDataMasker()),
                new VersionNormalizer(),
                new TelemetryMetadataProperties(),
                new MetricDiscoveryService(new MetricDiscoveryRegistry(List.of()), catalog));
    }

    private ServiceMetadataProvider provider(String id, boolean enabled,
                                             Function<MetadataQuery, MetadataFetchResult> fetch) {
        return new FakeProvider(id, enabled, new MetadataCapability(id, id,
                Set.of("clickhouse"), Set.of("clickhouse.server"), true, true, true, 20,
                List.of(), List.of()), fetch);
    }

    private MetadataQueryRequest request(String scope, MetadataTarget... targets) {
        MetadataQueryRequest request = new MetadataQueryRequest();
        request.setTargets(List.of(targets));
        request.setScope(scope);
        return request;
    }

    private static final class FakeProvider implements ServiceMetadataProvider {

        private final String id;
        private final boolean enabled;
        private final MetadataCapability capability;
        private final Function<MetadataQuery, MetadataFetchResult> fetch;

        private FakeProvider(String id, boolean enabled, MetadataCapability capability,
                             Function<MetadataQuery, MetadataFetchResult> fetch) {
            this.id = id;
            this.enabled = enabled;
            this.capability = capability;
            this.fetch = fetch;
        }

        @Override
        public String providerId() {
            return id;
        }

        @Override
        public MetadataCapability capability() {
            return capability;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public MetadataFetchResult fetch(MetadataQuery query) {
            return fetch.apply(query);
        }
    }
}
