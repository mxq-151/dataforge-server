package com.bigdata.modules.telemetry.service.metadata.registry;

import com.bigdata.modules.telemetry.service.metadata.model.MaskingRule;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataScope;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataCapability;
import com.bigdata.modules.telemetry.service.metadata.spi.ServiceMetadataProvider;
import com.bigdata.modules.telemetry.service.metadata.support.MetadataMasker;
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
     *
     * <p>scope 取<b>并集</b>：请求 version+config 时，只支持其中一项的 provider 也算候选
     * —— 否则「版本+配置」会因为没有任何 provider 同时支持两项而整体无 provider，
     * 把本来能拿到的配置一起丢掉。缺的那部分由 {@code buildSuccess} 标成不可用。</p>
     */
    public List<ServiceMetadataProvider> candidates(Target target, Set<MetadataScope> scopes) {
        boolean wantsVersion = scopes.contains(MetadataScope.VERSION);
        boolean wantsConfig = scopes.contains(MetadataScope.CONFIG);
        List<ServiceMetadataProvider> matched = new ArrayList<>();
        for (ServiceMetadataProvider provider : providers) {
            MetadataCapability capability = provider.capability();
            if (!capability.supportsService(target.serviceType())
                    || !capability.supportsComponent(target.componentId())) {
                continue;
            }
            if (wantsVersion || wantsConfig) {
                boolean coversAny = (wantsVersion && capability.supportsVersion())
                        || (wantsConfig && capability.supportsConfig());
                if (!coversAny) {
                    continue;
                }
            }
            matched.add(provider);
        }
        return matched;
    }
}
