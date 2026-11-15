package com.bigdata.modules.telemetry.v1.metadata.registry;

import com.bigdata.modules.telemetry.v1.metadata.model.MaskingRule;
import com.bigdata.modules.telemetry.v1.metadata.model.MetadataScope;
import com.bigdata.modules.telemetry.v1.metadata.spi.MetadataCapability;
import com.bigdata.modules.telemetry.v1.metadata.spi.ServiceMetadataProvider;
import com.bigdata.modules.telemetry.v1.metadata.support.MetadataMasker;
import com.bigdata.modules.telemetry.v1.model.Target;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 启动时收集全部 {@link ServiceMetadataProvider}：providerId 重复即启动失败，
 * 按 {@code capability.order} 升序、同序按 providerId 字典序固定下来。
 */
@Component
public class ServiceMetadataProviderRegistry {

    private final List<ServiceMetadataProvider> providers;

    @Autowired
    public ServiceMetadataProviderRegistry(ObjectProvider<ServiceMetadataProvider> providers) {
        this(providers.orderedStream().toList());
    }

    public ServiceMetadataProviderRegistry(List<ServiceMetadataProvider> providers) {
        List<ServiceMetadataProvider> collected = new ArrayList<>(providers);
        Set<String> ids = new HashSet<>();
        for (ServiceMetadataProvider provider : collected) {
            String id = provider.providerId();
            if (id == null || id.isBlank()) {
                throw new IllegalStateException("metadata provider 缺少 providerId: "
                        + provider.getClass().getName());
            }
            if (!ids.add(id)) {
                throw new IllegalStateException("重复的 metadata providerId: " + id);
            }
            MetadataCapability capability = provider.capability();
            if (capability == null) {
                throw new IllegalStateException("metadata provider 缺少 capability: " + id);
            }
            for (MaskingRule rule : capability.maskingRules()) {
                MetadataMasker.validate(rule);
            }
        }
        collected.sort(Comparator
                .comparingInt((ServiceMetadataProvider provider) -> provider.capability().order())
                .thenComparing(ServiceMetadataProvider::providerId));
        this.providers = List.copyOf(collected);
    }

    public List<ServiceMetadataProvider> providers() {
        return providers;
    }

    /**
     * 按 serviceType / componentId / scope 过滤候选 provider（含未启用的，便于在
     * {@code providers.skipped[]} 里给出原因）。是否启用由编排器判断。
     */
    public List<ServiceMetadataProvider> candidates(Target target, Set<MetadataScope> scopes) {
        List<ServiceMetadataProvider> matched = new ArrayList<>();
        for (ServiceMetadataProvider provider : providers) {
            MetadataCapability capability = provider.capability();
            if (!capability.supportsService(target.serviceType())
                    || !capability.supportsComponent(target.componentId())) {
                continue;
            }
            if (scopes.contains(MetadataScope.VERSION) && !capability.supportsVersion()) {
                continue;
            }
            if (scopes.contains(MetadataScope.CONFIG) && !capability.supportsConfig()) {
                continue;
            }
            matched.add(provider);
        }
        return matched;
    }
}
