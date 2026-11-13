package com.bigdata.modules.runtime.model;

import com.bigdata.modules.logs.model.ComponentRole;
import com.bigdata.modules.logs.model.ServiceType;
import com.bigdata.modules.runtime.config.RuntimeProperties;

import java.util.List;

/**
 * 运行期指标源：配置 + 解析后的凭据/地址 + 启用状态。
 */
public class RuntimeSource {

    private final RuntimeProperties.Source config;
    private final ServiceType serviceType;
    private final RuntimeMode mode;
    private final List<ComponentRole> roles;
    private final String baseUrl;
    private final String token;
    private final boolean enabled;
    private final String disabledReason;

    public RuntimeSource(RuntimeProperties.Source config, ServiceType serviceType, RuntimeMode mode,
                         List<ComponentRole> roles, String baseUrl, String token,
                         boolean enabled, String disabledReason) {
        this.config = config;
        this.serviceType = serviceType;
        this.mode = mode;
        this.roles = roles;
        this.baseUrl = baseUrl;
        this.token = token;
        this.enabled = enabled;
        this.disabledReason = disabledReason;
    }

    public RuntimeProperties.Source getConfig() {
        return config;
    }

    public String getId() {
        return config.getId();
    }

    public String getName() {
        return config.getName();
    }

    public String getCluster() {
        return config.getCluster() == null || config.getCluster().isBlank()
                ? "default" : config.getCluster();
    }

    public ServiceType getServiceType() {
        return serviceType;
    }

    public RuntimeMode getMode() {
        return mode;
    }

    public List<ComponentRole> getRoles() {
        return roles;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getToken() {
        return token;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getDisabledReason() {
        return disabledReason;
    }

    public boolean supportsRole(ComponentRole role) {
        return roles.isEmpty() || roles.contains(ComponentRole.GENERIC) || roles.contains(role);
    }
}
