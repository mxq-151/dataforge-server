package com.bigdata.modules.logs.adapter;

import com.bigdata.modules.logs.model.ComponentRole;
import com.bigdata.modules.logs.model.LogKind;
import com.bigdata.modules.logs.model.ServiceType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 适配器注册表：按「服务类型 + 日志种类 + 角色」匹配实现。
 *
 * <p>匹配顺序：精确角色 → {@link ComponentRole#GENERIC} 兜底。这样新增角色时
 * 不必为每个角色写一个适配器，只需要在该服务的通用实现里处理角色差异。</p>
 */
@Component
public class LogAdapterRegistry {

    private final List<LogAdapter> adapters;

    public LogAdapterRegistry(List<LogAdapter> adapters) {
        this.adapters = adapters;
    }

    public LogAdapter resolve(ServiceType serviceType, LogKind logKind, ComponentRole role) {
        for (LogAdapter adapter : adapters) {
            if (adapter.supports(serviceType, logKind, role)) {
                return adapter;
            }
        }
        if (role != ComponentRole.GENERIC) {
            for (LogAdapter adapter : adapters) {
                if (adapter.supports(serviceType, logKind, ComponentRole.GENERIC)) {
                    return adapter;
                }
            }
        }
        throw new LogAdapterException(LogAdapterException.Code.UNSUPPORTED,
                "服务 " + serviceType.wireName() + " 的 " + logKind.wireName()
                        + " 日志暂无适配器（角色 " + role.wireName() + "）");
    }

    /** 是否存在能处理该三维组合的适配器 */
    public boolean hasAdapter(ServiceType serviceType, LogKind logKind, ComponentRole role) {
        try {
            resolve(serviceType, logKind, role);
            return true;
        } catch (LogAdapterException e) {
            return false;
        }
    }
}
