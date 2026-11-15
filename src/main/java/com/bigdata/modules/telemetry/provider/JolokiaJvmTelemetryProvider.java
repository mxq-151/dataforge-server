package com.bigdata.modules.telemetry.provider;

import com.bigdata.modules.telemetry.catalog.ServiceComponentCatalog;
import com.bigdata.modules.telemetry.config.TelemetryJmxProperties;
import com.bigdata.modules.telemetry.model.Capability;
import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.DataTypes;
import com.bigdata.modules.telemetry.model.Requirement;
import com.bigdata.modules.telemetry.model.RequirementKind;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Jolokia 直连通道（agentkit 侧当前采用的方式）。
 *
 * <p>只声明 runtime 指标覆盖不到的两个组件（spark.driver / flink.jobmanager）的 jvm.* 数据。
 * 本阶段服务端尚未接入 Jolokia 客户端：即使配置了模板，也如实返回依赖缺失，
 * 不用空数组或估算值冒充。</p>
 */
@Component
public class JolokiaJvmTelemetryProvider implements TelemetryProvider {

    private static final Set<String> DATA_TYPES = Set.of(DataTypes.JVM_RUNTIME, DataTypes.JVM_GC);
    private static final Set<String> COMPONENTS = Set.of("spark.driver", "flink.jobmanager");

    private final TelemetryJmxProperties properties;

    public JolokiaJvmTelemetryProvider(TelemetryJmxProperties properties) {
        this.properties = properties;
    }

    @Override
    public String providerId() {
        return "jolokia-jvm";
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public boolean supports(String serviceType, String componentId, String dataTypeId) {
        return DATA_TYPES.contains(dataTypeId) && COMPONENTS.contains(componentId);
    }

    @Override
    public Capability evaluate(CapabilityContext context) {
        if (properties.getJolokiaUrlTemplate().isBlank()) {
            return unavailable(context, CapabilityCode.NOT_CONFIGURED,
                    "未配置 Jolokia 地址模板",
                    new Requirement(RequirementKind.CONFIG, "telemetry.jmx.jolokia-url-template",
                            "配置形如 http://{host}:8778/jolokia 的模板"));
        }
        if (ServiceComponentCatalog.CLUSTER_HOST.equals(context.hostId())) {
            return unavailable(context, CapabilityCode.HOST_NOT_MANAGED,
                    "Jolokia 按主机直连，host 尚未纳管",
                    new Requirement(RequirementKind.API, "host.inventory",
                            "需要 host 纳管清单才能解析 {host}"));
        }
        return unavailable(context, CapabilityCode.DEPENDENCY_MISSING,
                "服务端 Jolokia 客户端尚未接入（当前由 agentkit 侧直连）",
                new Requirement(RequirementKind.PLUGIN, "telemetry.jmx.jolokia-client",
                        "注册 Jolokia 取数实现后再返回数据"));
    }

    private Capability unavailable(CapabilityContext context, CapabilityCode code,
                                   String reason, Requirement requirement) {
        return Capability.unavailable(context.dataTypeId(), code, reason, providerId(),
                List.of("jolokia"), List.of(requirement));
    }
}
