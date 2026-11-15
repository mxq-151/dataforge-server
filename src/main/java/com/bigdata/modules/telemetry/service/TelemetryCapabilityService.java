package com.bigdata.modules.telemetry.service;

import com.bigdata.modules.telemetry.catalog.ServiceComponentCatalog;
import com.bigdata.modules.telemetry.model.Capability;
import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.CapabilityState;
import com.bigdata.modules.telemetry.model.DataTypeDefinition;
import com.bigdata.modules.telemetry.model.DataTypes;
import com.bigdata.modules.telemetry.model.Requirement;
import com.bigdata.modules.telemetry.model.RequirementKind;
import com.bigdata.modules.telemetry.provider.CapabilityContext;
import com.bigdata.modules.telemetry.provider.TelemetryProviderRegistry;
import com.bigdata.modules.telemetry.vo.CapabilityComponentView;
import com.bigdata.modules.telemetry.vo.CapabilityHostView;
import com.bigdata.modules.telemetry.vo.CapabilityItem;
import com.bigdata.modules.telemetry.vo.CapabilityServiceView;
import com.bigdata.modules.telemetry.vo.CapabilityTreeView;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 能力判定：把“服务类型 → 组件 → host → 数据类型”整棵树算成结构化可用性。
 */
@Service
public class TelemetryCapabilityService {

    private final TelemetryProviderRegistry providerRegistry;

    public TelemetryCapabilityService(TelemetryProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    public CapabilityTreeView capabilities(String serviceFilter, String componentFilter, String hostFilter) {
        List<CapabilityServiceView> services = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : ServiceComponentCatalog.components().entrySet()) {
            if (serviceFilter != null && !serviceFilter.equals(entry.getKey())) {
                continue;
            }
            List<CapabilityComponentView> components = new ArrayList<>();
            for (String componentId : entry.getValue()) {
                if (componentFilter != null && !componentFilter.equals(componentId)) {
                    continue;
                }
                if (hostFilter != null && !ServiceComponentCatalog.CLUSTER_HOST.equals(hostFilter)) {
                    // Phase 1 只有 cluster 一个 host；host inventory 后续单独补。
                    continue;
                }
                components.add(new CapabilityComponentView(componentId, List.of(buildHost(entry.getKey(), componentId))));
            }
            // hudi 等没有独立服务组件的服务也要保留服务节点（components 为空），
            // 这样调用方能区分“服务存在但没有组件/通道”和“服务不存在”。
            services.add(new CapabilityServiceView(entry.getKey(), List.copyOf(components)));
        }
        return new CapabilityTreeView(Instant.now(), services);
    }

    public Collection<DataTypeDefinition> dataTypes() {
        return DataTypes.all();
    }

    private CapabilityHostView buildHost(String serviceType, String componentId) {
        List<CapabilityItem> items = new ArrayList<>();
        Set<String> transports = new LinkedHashSet<>();
        for (DataTypeDefinition definition : DataTypes.all()) {
            Capability capability = evaluate(serviceType, componentId, definition);
            items.add(toItem(capability));
            transports.addAll(capability.transports());
        }
        return new CapabilityHostView(ServiceComponentCatalog.CLUSTER_HOST,
                List.copyOf(transports), List.copyOf(items));
    }

    private Capability evaluate(String serviceType, String componentId, DataTypeDefinition definition) {
        if (definition.status() == com.bigdata.modules.telemetry.model.DataTypeStatus.DECLARED_NOT_IMPLEMENTED) {
            return Capability.notImplemented(definition.dataTypeId(),
                    definition.label() + " 已声明但尚未实现；不会返回 0 值或空数组冒充数据",
                    requirementsFor(definition.dataTypeId()));
        }
        Optional<com.bigdata.modules.telemetry.provider.TelemetryProvider> provider =
                providerRegistry.resolve(serviceType, componentId, definition.dataTypeId());
        if (provider.isEmpty()) {
            return Capability.unavailable(definition.dataTypeId(), CapabilityCode.NO_PROVIDER,
                    "没有已注册的取数实现支持 " + serviceType + "|" + componentId + "|"
                            + definition.dataTypeId(),
                    null, definition.transports(),
                    List.of(new Requirement(RequirementKind.PLUGIN,
                            "telemetry.providers[" + definition.dataTypeId() + "]",
                            "实现并注册 TelemetryProvider")));
        }
        return provider.get().evaluate(new CapabilityContext(serviceType, componentId,
                ServiceComponentCatalog.CLUSTER_HOST, definition.dataTypeId()));
    }

    private List<Requirement> requirementsFor(String dataTypeId) {
        if (dataTypeId.startsWith("os.")) {
            return List.of(new Requirement(RequirementKind.API, "host.metrics",
                    "需要主机指标采集接口（node_exporter / JMX 桥 / agent 任一）"));
        }
        if (DataTypes.LOG_SLOW_QUERY.equals(dataTypeId)) {
            return List.of(new Requirement(RequirementKind.CONFIG,
                    "logs.sources[*].kinds=slow_query",
                    "需要慢查询日志采集与解析实现"));
        }
        return List.of();
    }

    private CapabilityItem toItem(Capability capability) {
        return new CapabilityItem(capability.dataTypeId(), capability.state(), capability.code(),
                capability.reason(), capability.retryable(), capability.providerId(),
                capability.transports(), capability.requirements());
    }

    /** 供测试与调用方判断：NOT_IMPLEMENTED 与 UNAVAILABLE 都不应重试。 */
    public static boolean retryable(CapabilityItem item) {
        return item != null && item.state() == CapabilityState.AVAILABLE && item.retryable();
    }
}
