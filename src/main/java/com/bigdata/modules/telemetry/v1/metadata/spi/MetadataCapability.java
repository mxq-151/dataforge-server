package com.bigdata.modules.telemetry.v1.metadata.spi;

import com.bigdata.modules.telemetry.v1.metadata.model.MaskingRule;
import com.bigdata.modules.telemetry.v1.metadata.model.MetadataSection;

import java.util.List;
import java.util.Set;

/**
 * provider 的能力声明，用于路由、目录与脱敏规则。
 *
 * <p>{@code componentIds} 为空表示覆盖该 serviceType 下的全部组件；{@code order} 升序优先，
 * 相同 order 按 providerId 字典序，保证可复现。</p>
 */
public record MetadataCapability(String providerId,
                                 String providerType,
                                 Set<String> serviceTypes,
                                 Set<String> componentIds,
                                 boolean supportsVersion,
                                 boolean supportsConfig,
                                 boolean fallbackAllowed,
                                 int order,
                                 List<MetadataSection> sections,
                                 List<MaskingRule> maskingRules) {

    public MetadataCapability {
        serviceTypes = serviceTypes == null ? Set.of() : Set.copyOf(serviceTypes);
        componentIds = componentIds == null ? Set.of() : Set.copyOf(componentIds);
        sections = sections == null ? List.of() : List.copyOf(sections);
        maskingRules = maskingRules == null ? List.of() : List.copyOf(maskingRules);
    }

    public boolean supportsService(String serviceType) {
        return serviceTypes.contains("*") || serviceTypes.contains(serviceType);
    }

    public boolean supportsComponent(String componentId) {
        return componentIds.isEmpty() || componentIds.contains("*")
                || componentIds.contains(componentId);
    }
}
