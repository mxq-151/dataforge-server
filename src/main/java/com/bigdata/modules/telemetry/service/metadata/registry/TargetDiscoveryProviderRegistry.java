package com.bigdata.modules.telemetry.service.metadata.registry;

import com.bigdata.modules.telemetry.service.metadata.spi.TargetDiscoveryProvider;
import com.bigdata.modules.telemetry.common.model.Target;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 启动时收集全部 {@link TargetDiscoveryProvider}：providerId 重复即启动失败，
 * 按 order 升序、同序按 providerId 字典序固定。
 */
@Component
public class TargetDiscoveryProviderRegistry {

    private final List<TargetDiscoveryProvider> providers;

    @Autowired
    public TargetDiscoveryProviderRegistry(ObjectProvider<TargetDiscoveryProvider> providers) {
        this(providers.orderedStream().toList());
    }

    public TargetDiscoveryProviderRegistry(List<TargetDiscoveryProvider> providers) {
        List<TargetDiscoveryProvider> collected = new ArrayList<>(providers);
        Set<String> ids = new HashSet<>();
        for (TargetDiscoveryProvider provider : collected) {
            String id = provider.providerId();
            if (id == null || id.isBlank()) {
                throw new IllegalStateException("target discovery provider 缺少 providerId: "
                        + provider.getClass().getName());
            }
            if (!ids.add(id)) {
                throw new IllegalStateException("重复的 target discovery providerId: " + id);
            }
        }
        collected.sort(Comparator
                .comparingInt(TargetDiscoveryProvider::order)
                .thenComparing(TargetDiscoveryProvider::providerId));
        this.providers = List.copyOf(collected);
    }

    public List<TargetDiscoveryProvider> providers() {
        return providers;
    }

    public List<TargetDiscoveryProvider> candidates(Target filter) {
        List<TargetDiscoveryProvider> matched = new ArrayList<>();
        for (TargetDiscoveryProvider provider : providers) {
            if (filter == null) {
                matched.add(provider);
                continue;
            }
            if (!provider.serviceTypes().contains(filter.serviceType())) {
                continue;
            }
            if (!provider.componentIds().isEmpty()
                    && !provider.componentIds().contains(filter.componentId())) {
                continue;
            }
            matched.add(provider);
        }
        return matched;
    }
}
