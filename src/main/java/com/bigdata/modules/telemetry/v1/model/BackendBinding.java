package com.bigdata.modules.telemetry.v1.model;

import java.util.List;
import java.util.Set;

/**
 * 目录中的一条「数据类型 × 后端」绑定。
 *
 * <p>{@code backendId} 可以是配置里的实例 id（如 {@code prometheus-prod}），也可以是逻辑名
 * （如 {@code prometheus}）：路由层按 {@code backendType} 展开为所有已配置实例。</p>
 *
 * <p>{@code equivalenceGroup} 只是目录声明；是否允许回退还要求配置
 * {@code telemetry.routing.equivalence-groups} 显式批准同一组。</p>
 */
public record BackendBinding(String backendId,
                             BackendType backendType,
                             BackendStatus status,
                             int priority,
                             Set<QueryMode> queryModes,
                             Set<Sampling> sampling,
                             boolean authoritative,
                             String equivalenceGroup,
                             String notes) {

    public boolean supports(QueryMode mode) {
        return queryModes.contains(mode);
    }
}
