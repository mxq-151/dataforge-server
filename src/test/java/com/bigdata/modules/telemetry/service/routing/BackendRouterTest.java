package com.bigdata.modules.telemetry.service.routing;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.BackendBinding;
import com.bigdata.modules.telemetry.common.model.BackendStatus;
import com.bigdata.modules.telemetry.common.model.BackendType;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDefinition;
import com.bigdata.modules.telemetry.common.model.QueryMode;
import com.bigdata.modules.telemetry.common.model.Sampling;
import com.bigdata.modules.telemetry.common.model.Target;
import com.bigdata.modules.telemetry.common.model.BackendCapability;
import com.bigdata.modules.telemetry.service.metricdata.spi.MetricBackend;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricQueryResult;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricQuerySpec;
import com.bigdata.modules.telemetry.config.TelemetryBackendRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import com.bigdata.modules.telemetry.config.RoutingProperties;
import com.bigdata.modules.telemetry.service.routing.model.BackendCandidate;
import com.bigdata.modules.telemetry.service.routing.model.BackendSelection;
import com.bigdata.modules.telemetry.service.routing.model.BackendSkip;

class BackendRouterTest {

    private static final Target TARGET = new Target("flink", "flink.jobmanager", "cluster");

    @Test
    @DisplayName("按目录 backendPreference 排序，并按查询模式过滤未声明的候选")
    void ordersByPreferenceAndFiltersMode() {
        BackendRouter router = router();
        MetricDefinition definition = definition(List.of(
                binding("prometheus-prod", BackendType.PROMETHEUS, BackendStatus.IMPLEMENTED,
                        Set.of(QueryMode.RANGE, QueryMode.INSTANT), 10),
                binding("vm-prod", BackendType.VICTORIAMETRICS, BackendStatus.IMPLEMENTED,
                        Set.of(QueryMode.RANGE, QueryMode.INSTANT), 20),
                binding("flink-rest", BackendType.FLINK_REST, BackendStatus.DECLARED_NOT_IMPLEMENTED,
                        Set.of(QueryMode.INSTANT), 5)),
                Map.of("instant", List.of("vm-prod", "prometheus-prod"),
                        "range", List.of("prometheus-prod", "vm-prod")));

        BackendSelection range = router.selectMetric(TARGET, definition, QueryMode.RANGE);
        assertThat(range.candidates()).extracting(BackendCandidate::backendId)
                .containsExactly("prometheus-prod", "vm-prod");
        assertThat(range.preferred()).isEqualTo("prometheus-prod");
        assertThat(range.skipped()).extracting(BackendSkip::code)
                .contains(CapabilityCode.QUERY_MODE_UNSUPPORTED);

        BackendSelection instant = router.selectMetric(TARGET, definition, QueryMode.INSTANT);
        assertThat(instant.candidates()).extracting(BackendCandidate::backendId)
                .containsExactly("vm-prod", "prometheus-prod");
        assertThat(instant.skipped()).extracting(BackendSkip::code)
                .contains(CapabilityCode.DATA_TYPE_NOT_IMPLEMENTED);
    }

    @Test
    @DisplayName("配置规则覆盖目录默认顺序")
    void routingRuleOverridesPreference() {
        RoutingProperties.Rule rule = new RoutingProperties.Rule();
        rule.setServiceType("flink");
        rule.setMetric("flink_*");
        rule.setOrder(List.of("prometheus-prod"));
        MetricDefinition definition = definition(List.of(
                binding("prometheus-prod", BackendType.PROMETHEUS, BackendStatus.IMPLEMENTED,
                        Set.of(QueryMode.RANGE, QueryMode.INSTANT), 10),
                binding("vm-prod", BackendType.VICTORIAMETRICS, BackendStatus.IMPLEMENTED,
                        Set.of(QueryMode.RANGE, QueryMode.INSTANT), 20)),
                Map.of("instant", List.of("vm-prod", "prometheus-prod")));

        RoutingProperties routing = routingWithRule(rule);
        BackendRouter configuredRouter = new BackendRouter(routing, registry());
        BackendSelection selection = configuredRouter.selectMetric(TARGET, definition, QueryMode.INSTANT);

        assertThat(selection.preferred()).isEqualTo("prometheus-prod");
    }

    @Test
    @DisplayName("没有已注册实例时跳过并给出 NOT_CONFIGURED，不伪造候选")
    void unconfiguredBackendIsSkipped() {
        TelemetryBackendRegistry empty = new TelemetryBackendRegistry(List.of(), List.of());
        BackendRouter router = new BackendRouter(new RoutingProperties(), empty);
        MetricDefinition definition = definition(List.of(
                binding("prometheus-prod", BackendType.PROMETHEUS, BackendStatus.IMPLEMENTED,
                        Set.of(QueryMode.RANGE), 10)), Map.of());

        BackendSelection selection = router.selectMetric(TARGET, definition, QueryMode.RANGE);

        assertThat(selection.candidates()).isEmpty();
        assertThat(selection.skipped()).singleElement()
                .satisfies(skip -> assertThat(skip.code()).isEqualTo(CapabilityCode.NOT_CONFIGURED));
    }

    @Test
    @DisplayName("等价组必须由配置显式批准，目录声明相同 group 不足以回退")
    void equivalenceRequiresConfigRatification() {
        BackendCandidate first = candidate("a", "g");
        BackendCandidate second = candidate("b", "g");

        BackendRouter withoutGroup = new BackendRouter(new RoutingProperties(), registry());
        assertThat(withoutGroup.equivalentBackends(first, second, "flink")).isFalse();

        RoutingProperties routing = new RoutingProperties();
        RoutingProperties.EquivalenceGroup group = new RoutingProperties.EquivalenceGroup();
        group.setBackends(List.of("a", "b"));
        group.setServiceType("flink");
        routing.getRouting().getEquivalenceGroups().put("g", group);
        BackendRouter withGroup = new BackendRouter(routing, registry());
        assertThat(withGroup.equivalentBackends(first, second, "flink")).isTrue();
        assertThat(withGroup.equivalentBackends(first, second, "spark")).isFalse();
    }

    // ------------------------------------------------------------------ 辅助

    private static BackendRouter router() {
        return new BackendRouter(new RoutingProperties(), registry());
    }

    private static RoutingProperties routingWithRule(RoutingProperties.Rule rule) {
        RoutingProperties routing = new RoutingProperties();
        routing.getRouting().getMetric().getRules().add(rule);
        return routing;
    }

    private static TelemetryBackendRegistry registry() {
        return new TelemetryBackendRegistry(
                List.of(fake("prometheus-prod", BackendType.PROMETHEUS),
                        fake("vm-prod", BackendType.VICTORIAMETRICS)),
                List.of());
    }

    private static MetricDefinition definition(List<BackendBinding> bindings,
                                               Map<String, List<String>> preference) {
        return new MetricDefinition("flink_metric", "gauge", "count", "job",
                List.of(), List.of(), preference, bindings);
    }

    private static BackendBinding binding(String id, BackendType type, BackendStatus status,
                                          Set<QueryMode> modes, int priority) {
        return new BackendBinding(id, type, status, priority, modes, Set.of(Sampling.STEP),
                false, null, null);
    }

    private static BackendCandidate candidate(String id, String group) {
        return new BackendCandidate(id, BackendType.PROMETHEUS, BackendStatus.IMPLEMENTED,
                CapabilityCode.OK, null, List.of(), Set.of(QueryMode.RANGE), group,
                false, 10, null);
    }

    private static MetricBackend fake(String id, BackendType type) {
        return new MetricBackend() {
            @Override
            public String backendId() {
                return id;
            }

            @Override
            public BackendType backendType() {
                return type;
            }

            @Override
            public BackendCapability evaluate(MetricQuerySpec spec) {
                return BackendCapability.available();
            }

            @Override
            public MetricQueryResult fetch(MetricQuerySpec spec) {
                return new MetricQueryResult(List.of(), false, List.of());
            }
        };
    }
}
