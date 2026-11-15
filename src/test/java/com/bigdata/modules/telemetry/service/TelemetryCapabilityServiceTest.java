package com.bigdata.modules.telemetry.service;

import com.bigdata.modules.telemetry.model.Capability;
import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.CapabilityState;
import com.bigdata.modules.telemetry.model.DataTypes;
import com.bigdata.modules.telemetry.model.Requirement;
import com.bigdata.modules.telemetry.model.RequirementKind;
import com.bigdata.modules.telemetry.provider.CapabilityContext;
import com.bigdata.modules.telemetry.provider.TelemetryProvider;
import com.bigdata.modules.telemetry.provider.TelemetryProviderRegistry;
import com.bigdata.modules.telemetry.vo.CapabilityHostView;
import com.bigdata.modules.telemetry.vo.CapabilityItem;
import com.bigdata.modules.telemetry.vo.CapabilityServiceView;
import com.bigdata.modules.telemetry.vo.CapabilityTreeView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 能力判定：结构化可用性、未实现类型的硬约束、过滤与不可重试语义。
 */
class TelemetryCapabilityServiceTest {

    @Test
    @DisplayName("声明未实现的类型固定返回 NOT_IMPLEMENTED，且不携带任何数据/实现")
    void declaredDataTypesAreNotImplemented() {
        TelemetryCapabilityService service = serviceWith(new FakeProvider("fake", true));

        CapabilityTreeView tree = service.capabilities("doris", "doris.fe", null);

        assertEquals(1, tree.services().size());
        List<CapabilityItem> items = dataTypesOf(tree, "doris", "doris.fe");
        for (String dataTypeId : List.of(DataTypes.OS_CPU, DataTypes.OS_MEMORY,
                DataTypes.OS_DISK, DataTypes.LOG_SLOW_QUERY)) {
            CapabilityItem item = find(items, dataTypeId);
            assertEquals(CapabilityState.NOT_IMPLEMENTED, item.state(), dataTypeId);
            assertEquals(CapabilityCode.DATA_TYPE_NOT_IMPLEMENTED, item.code(), dataTypeId);
            assertFalse(item.retryable(), "未实现不可重试: " + dataTypeId);
            assertNull(item.providerId(), "未实现不能挂 provider: " + dataTypeId);
            assertTrue(item.transports().isEmpty(), "未实现不能声称有传输方式: " + dataTypeId);
            assertFalse(item.requirements().isEmpty(), "未实现必须说明缺什么: " + dataTypeId);
        }
    }

    @Test
    @DisplayName("没有已注册实现时返回 NO_PROVIDER，而不是假数据")
    void noProviderYieldsTypedUnavailable() {
        TelemetryCapabilityService service = serviceWith(new FakeProvider("fake", false));

        CapabilityTreeView tree = service.capabilities("kafka", "kafka.broker", null);
        CapabilityItem item = find(dataTypesOf(tree, "kafka", "kafka.broker"), DataTypes.LOG_RUNTIME);

        assertEquals(CapabilityState.UNAVAILABLE, item.state());
        assertEquals(CapabilityCode.NO_PROVIDER, item.code());
        assertFalse(item.retryable());
        assertEquals(RequirementKind.PLUGIN, item.requirements().get(0).kind());
    }

    @Test
    @DisplayName("provider 的不可用原因与补齐项原样透出，且不可重试")
    void providerUnavailableIsPropagated() {
        TelemetryProvider disabled = new FakeProvider("disabled", true) {
            @Override
            public Capability evaluate(CapabilityContext context) {
                return Capability.unavailable(context.dataTypeId(), CapabilityCode.SOURCE_DISABLED,
                        "日志源已禁用", providerId(), List.of("file"),
                        List.of(new Requirement(RequirementKind.CONFIG,
                                "logs.sources[flink].enabled", "启用日志源")));
            }
        };
        TelemetryCapabilityService service = serviceWith(disabled);

        CapabilityItem item = find(dataTypesOf(service.capabilities("flink", "flink.jobmanager", null),
                "flink", "flink.jobmanager"), DataTypes.LOG_RUNTIME);

        assertEquals(CapabilityState.UNAVAILABLE, item.state());
        assertEquals(CapabilityCode.SOURCE_DISABLED, item.code());
        assertFalse(item.retryable());
        assertEquals("logs.sources[flink].enabled", item.requirements().get(0).key());
    }

    @Test
    @DisplayName("可用时 state=AVAILABLE 且 retryable=true")
    void availableIsRetryable() {
        TelemetryCapabilityService service = serviceWith(new FakeProvider("ok", true));

        CapabilityItem item = find(dataTypesOf(service.capabilities("flink", "flink.taskmanager", null),
                "flink", "flink.taskmanager"), DataTypes.JVM_GC);

        assertEquals(CapabilityState.AVAILABLE, item.state());
        assertEquals(CapabilityCode.OK, item.code());
        assertTrue(item.retryable());
        assertEquals("ok", item.providerId());
    }

    @Test
    @DisplayName("服务层只包含 agentkit 的九个引擎；file/ds/yarn 不得作为服务节点")
    void serviceTaxonomyMatchesAgentkitEngines() {
        TelemetryCapabilityService service = serviceWith(new FakeProvider("fake", false));

        List<String> serviceTypes = service.capabilities(null, null, null).services().stream()
                .map(CapabilityServiceView::serviceType)
                .toList();

        assertEquals(List.of("spark", "flink", "doris", "starrocks", "clickhouse",
                "hbase", "hudi", "kafka", "pulsar"), serviceTypes);
        assertFalse(serviceTypes.contains("file"), "file 只能是 transport/兼容别名");
        assertFalse(serviceTypes.contains("ds"), "ds 是调度接入方式，不是引擎服务类型");
        assertFalse(serviceTypes.contains("yarn"), "yarn 是资源管理接入方式，不是引擎服务类型");

        CapabilityServiceView hudi = service.capabilities("hudi", null, null).services().get(0);
        assertTrue(hudi.components().isEmpty(), "hudi 无独立组件：channels 为空，证据 handoff 给 spark");
    }

    @Test
    @DisplayName("按服务/组件过滤；未知服务返回空树而不是报错")
    void filtersWork() {
        TelemetryCapabilityService service = serviceWith(new FakeProvider("fake", false));

        assertEquals(1, service.capabilities("spark", null, null).services().size());
        assertEquals(1, service.capabilities("spark", "spark.driver", null)
                .services().get(0).components().size());
        assertTrue(service.capabilities("no-such-engine", null, null).services().isEmpty());
        assertTrue(service.capabilities(null, null, "node-1").services().stream()
                .allMatch(s -> s.components().isEmpty()), "Phase 1 只有 cluster host");
    }

    private static TelemetryCapabilityService serviceWith(TelemetryProvider provider) {
        return new TelemetryCapabilityService(new TelemetryProviderRegistry(List.of(provider)));
    }

    private static List<CapabilityItem> dataTypesOf(CapabilityTreeView tree, String serviceType, String componentId) {
        CapabilityServiceView service = tree.services().stream()
                .filter(s -> s.serviceType().equals(serviceType))
                .findFirst().orElseThrow();
        CapabilityHostView host = service.components().stream()
                .filter(c -> c.componentId().equals(componentId))
                .findFirst().orElseThrow()
                .hosts().get(0);
        return host.dataTypes();
    }

    private static CapabilityItem find(List<CapabilityItem> items, String dataTypeId) {
        return items.stream().filter(i -> i.dataTypeId().equals(dataTypeId))
                .findFirst().orElseThrow();
    }

    /** 声明支持所有四元组（或按需关闭）的假实现。 */
    private static class FakeProvider implements TelemetryProvider {
        private final String id;
        private final boolean supported;

        FakeProvider(String id, boolean supported) {
            this.id = id;
            this.supported = supported;
        }

        @Override
        public String providerId() {
            return id;
        }

        @Override
        public boolean supports(String serviceType, String componentId, String dataTypeId) {
            return supported;
        }

        @Override
        public Capability evaluate(CapabilityContext context) {
            return Capability.available(context.dataTypeId(), id, List.of("fake"));
        }
    }
}
