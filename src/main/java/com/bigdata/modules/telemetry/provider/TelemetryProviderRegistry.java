package com.bigdata.modules.telemetry.provider;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 适配器注册表：启动时收集全部 {@link TelemetryProvider}，按优先级排序并冻结。
 */
@Component
public class TelemetryProviderRegistry {

    private final List<TelemetryProvider> providers;

    public TelemetryProviderRegistry(List<TelemetryProvider> providers) {
        Set<String> ids = new HashSet<>();
        for (TelemetryProvider provider : providers) {
            if (!ids.add(provider.providerId())) {
                throw new IllegalStateException("重复的 TelemetryProvider id: " + provider.providerId());
            }
        }
        List<TelemetryProvider> sorted = new ArrayList<>(providers);
        sorted.sort(Comparator.comparingInt(TelemetryProvider::order));
        this.providers = List.copyOf(sorted);
    }

    public List<TelemetryProvider> all() {
        return providers;
    }

    public Optional<TelemetryProvider> resolve(String serviceType, String componentId, String dataTypeId) {
        return providers.stream()
                .filter(p -> p.supports(serviceType, componentId, dataTypeId))
                .findFirst();
    }
}
