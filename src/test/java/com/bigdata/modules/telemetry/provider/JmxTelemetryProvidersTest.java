package com.bigdata.modules.telemetry.provider;

import com.bigdata.modules.telemetry.config.TelemetryJmxProperties;
import com.bigdata.modules.telemetry.model.Capability;
import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.DataTypes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JmxTelemetryProvidersTest {

    @Test
    @DisplayName("Jolokia 未配置：NOT_CONFIGURED + 配置键")
    void jolokiaNotConfigured() {
        JolokiaJvmTelemetryProvider provider = new JolokiaJvmTelemetryProvider(new TelemetryJmxProperties());

        Capability capability = provider.evaluate(new CapabilityContext(
                "spark", "spark.driver", "cluster", DataTypes.JVM_RUNTIME));

        assertEquals(CapabilityCode.NOT_CONFIGURED, capability.code());
        assertFalse(capability.retryable());
        assertEquals("telemetry.jmx.jolokia-url-template", capability.requirements().get(0).key());
        assertTrue(capability.transports().contains("jolokia"));
    }

    @Test
    @DisplayName("Jolokia 已配置但 host 未纳管：HOST_NOT_MANAGED")
    void jolokiaNeedsHostInventory() {
        TelemetryJmxProperties properties = new TelemetryJmxProperties();
        properties.setJolokiaUrlTemplate("http://{host}:8778/jolokia");
        JolokiaJvmTelemetryProvider provider = new JolokiaJvmTelemetryProvider(properties);

        Capability capability = provider.evaluate(new CapabilityContext(
                "flink", "flink.jobmanager", "cluster", DataTypes.JVM_GC));

        assertEquals(CapabilityCode.HOST_NOT_MANAGED, capability.code());
        assertEquals("host.inventory", capability.requirements().get(0).key());
    }

    @Test
    @DisplayName("Jolokia 具体 host：客户端未接入时如实报依赖缺失")
    void jolokiaClientMissing() {
        TelemetryJmxProperties properties = new TelemetryJmxProperties();
        properties.setJolokiaUrlTemplate("http://{host}:8778/jolokia");
        JolokiaJvmTelemetryProvider provider = new JolokiaJvmTelemetryProvider(properties);

        Capability capability = provider.evaluate(new CapabilityContext(
                "spark", "spark.driver", "node-1", DataTypes.JVM_RUNTIME));

        assertEquals(CapabilityCode.DEPENDENCY_MISSING, capability.code());
        assertEquals("telemetry.jmx.jolokia-client", capability.requirements().get(0).key());
    }

    @Test
    @DisplayName("服务端 JMX 桥未部署：DEPENDENCY_MISSING + 待补接口")
    void serverJmxBridgeMissing() {
        TelemetryJmxProperties properties = new TelemetryJmxProperties();
        properties.setServerUrlTemplate("http://jmx-bridge:9000");
        ServerJmxTelemetryProvider provider = new ServerJmxTelemetryProvider(properties);

        Capability capability = provider.evaluate(new CapabilityContext(
                "spark", "spark.driver", "cluster", DataTypes.JVM_RUNTIME));

        assertEquals(CapabilityCode.DEPENDENCY_MISSING, capability.code());
        assertEquals("jmx.bridge", capability.requirements().get(0).key());
        assertTrue(capability.transports().contains("jmx"));
    }

    @Test
    @DisplayName("JMX provider 只声明 runtime 未覆盖的组件，避免遮蔽可用的 REST 指标")
    void jmxDoesNotShadowRuntimeComponents() {
        TelemetryJmxProperties properties = new TelemetryJmxProperties();
        JolokiaJvmTelemetryProvider provider = new JolokiaJvmTelemetryProvider(properties);

        assertFalse(provider.supports("flink", "flink.taskmanager", DataTypes.JVM_RUNTIME));
        assertFalse(provider.supports("spark", "spark.executor", DataTypes.JVM_GC));
        assertTrue(provider.supports("spark", "spark.driver", DataTypes.JVM_GC));
    }
}
