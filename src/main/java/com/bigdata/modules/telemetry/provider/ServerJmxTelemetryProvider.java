package com.bigdata.modules.telemetry.provider;

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
 * 服务端 JMX 桥通道（预留）。
 *
 * <p>设计上由服务端统一采集 JMX 再对外提供，可避免每个调用方直连各主机。
 * 桥尚未部署，因此始终返回 DEPENDENCY_MISSING，并给出待补的配置或接口。</p>
 */
@Component
public class ServerJmxTelemetryProvider implements TelemetryProvider {

    private static final Set<String> DATA_TYPES = Set.of(DataTypes.JVM_RUNTIME, DataTypes.JVM_GC);
    private static final Set<String> COMPONENTS = Set.of("spark.driver", "flink.jobmanager");

    private final TelemetryJmxProperties properties;

    public ServerJmxTelemetryProvider(TelemetryJmxProperties properties) {
        this.properties = properties;
    }

    @Override
    public String providerId() {
        return "server-jmx";
    }

    @Override
    public int order() {
        return 300;
    }

    @Override
    public boolean supports(String serviceType, String componentId, String dataTypeId) {
        return DATA_TYPES.contains(dataTypeId) && COMPONENTS.contains(componentId);
    }

    @Override
    public Capability evaluate(CapabilityContext context) {
        if (properties.getServerUrlTemplate().isBlank()) {
            return unavailable(context, CapabilityCode.NOT_CONFIGURED,
                    "未配置服务端 JMX 桥地址",
                    new Requirement(RequirementKind.CONFIG, "telemetry.jmx.server-url-template",
                            "配置服务端 JMX 桥地址模板"));
        }
        return unavailable(context, CapabilityCode.DEPENDENCY_MISSING,
                "服务端 JMX 桥尚未部署",
                new Requirement(RequirementKind.API, "jmx.bridge",
                        "部署并暴露 JMX 桥（JMX over HTTP）后再启用"));
    }

    private Capability unavailable(CapabilityContext context, CapabilityCode code,
                                   String reason, Requirement requirement) {
        return Capability.unavailable(context.dataTypeId(), code, reason, providerId(),
                List.of("jmx"), List.of(requirement));
    }
}
