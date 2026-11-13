package com.bigdata.modules.runtime.adapter;

import com.bigdata.modules.logs.model.ServiceType;
import com.bigdata.modules.runtime.model.RuntimeMode;
import org.springframework.stereotype.Component;

import java.util.List;

/** 运行时适配器注册表：按「服务类型 + 模式」匹配 */
@Component
public class RuntimeAdapterRegistry {

    private final List<RuntimeAdapter> adapters;

    public RuntimeAdapterRegistry(List<RuntimeAdapter> adapters) {
        this.adapters = adapters;
    }

    public RuntimeAdapter resolve(ServiceType serviceType, RuntimeMode mode) {
        for (RuntimeAdapter adapter : adapters) {
            if (adapter.supports(serviceType, mode)) {
                return adapter;
            }
        }
        throw new RuntimeAdapterException(RuntimeAdapterException.Code.UNSUPPORTED,
                "服务 " + (serviceType == null ? "-" : serviceType.wireName())
                        + "（mode=" + (mode == null ? "-" : mode.wireName()) + "）暂无运行时适配器");
    }
}
