package com.bigdata.modules.telemetry.provider;

import com.bigdata.modules.telemetry.model.Capability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryProviderRegistryTest {

    @Test
    @DisplayName("重复的 providerId 启动期直接失败")
    void duplicateProviderIdFailsFast() {
        TelemetryProvider a = provider("same", 1, true);
        TelemetryProvider b = provider("same", 2, true);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new TelemetryProviderRegistry(List.of(a, b)));
        assertTrue(error.getMessage().contains("same"));
    }

    @Test
    @DisplayName("按 order 选优先级最高的实现")
    void resolvesByOrder() {
        TelemetryProvider slow = provider("slow", 200, true);
        TelemetryProvider fast = provider("fast", 10, true);

        TelemetryProviderRegistry registry = new TelemetryProviderRegistry(List.of(slow, fast));

        assertEquals("fast", registry.resolve("spark", "spark.driver", "log.runtime")
                .orElseThrow().providerId());
    }

    @Test
    @DisplayName("没有实现声明支持时返回空")
    void unresolvedIsEmpty() {
        TelemetryProviderRegistry registry = new TelemetryProviderRegistry(
                List.of(provider("none", 1, false)));

        assertTrue(registry.resolve("spark", "spark.driver", "log.runtime").isEmpty());
    }

    private static TelemetryProvider provider(String id, int order, boolean supported) {
        return new TelemetryProvider() {
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
                return Capability.available(context.dataTypeId(), id, List.of());
            }

            @Override
            public int order() {
                return order;
            }
        };
    }
}
