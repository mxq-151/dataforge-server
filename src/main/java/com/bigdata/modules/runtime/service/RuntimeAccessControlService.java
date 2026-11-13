package com.bigdata.modules.runtime.service;

import com.bigdata.modules.logs.service.LogAccessControlService;
import com.bigdata.modules.runtime.config.RuntimeProperties;
import com.bigdata.modules.runtime.model.RuntimeSource;
import org.springframework.stereotype.Service;

/**
 * runtime 的 RBAC：直接复用日志模块的服务 + 集群维度判定逻辑，
 * 但使用 {@code runtime.rbac} 这套独立策略（默认允许 spark / flink）。
 */
@Service
public class RuntimeAccessControlService {

    private final LogAccessControlService delegate;
    private final RuntimeProperties properties;

    public RuntimeAccessControlService(LogAccessControlService delegate, RuntimeProperties properties) {
        this.delegate = delegate;
        this.properties = properties;
    }

    public void check(RuntimeSource source) {
        delegate.check(source.getServiceType().wireName(), source.getCluster(),
                properties.getRbac(), "运行时指标");
    }

    public boolean canRead(RuntimeSource source) {
        if (source == null || source.getServiceType() == null) {
            return false;
        }
        return delegate.canRead(source.getServiceType().wireName(), source.getCluster(),
                properties.getRbac());
    }
}
