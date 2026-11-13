package com.bigdata.modules.logs.model;

import com.bigdata.modules.logs.config.LogAccessProperties;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 运行期的日志源描述：配置项 + 解析后的元数据 + 启用状态。
 *
 * <p>由 {@code LogSourceCatalog} 生成，适配器只读该对象，不再直接读配置文件。</p>
 */
public class LogSource {

    private final LogAccessProperties.Source config;
    private final ServiceType serviceType;
    private final List<ComponentRole> roles;
    private final List<LogKind> logKinds;
    private final String baseUrl;
    private final String token;
    private final boolean enabled;
    private final String disabledReason;
    /** 已声明但当前配置下不可用的日志种类 -> 原因（用于 /sources 如实标注） */
    private Map<LogKind, String> logKindIssues = Map.of();

    public LogSource(LogAccessProperties.Source config,
                     ServiceType serviceType,
                     List<ComponentRole> roles,
                     List<LogKind> logKinds,
                     String baseUrl,
                     String token,
                     boolean enabled,
                     String disabledReason) {
        this.config = config;
        this.serviceType = serviceType;
        this.roles = roles;
        this.logKinds = logKinds;
        this.baseUrl = baseUrl;
        this.token = token;
        this.enabled = enabled;
        this.disabledReason = disabledReason;
    }

    public LogAccessProperties.Source getConfig() {
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

    public List<ComponentRole> getRoles() {
        return roles;
    }

    public List<LogKind> getLogKinds() {
        return logKinds;
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

    public Map<LogKind, String> getLogKindIssues() {
        return logKindIssues;
    }

    public void setLogKindIssues(Map<LogKind, String> logKindIssues) {
        this.logKindIssues = logKindIssues == null ? Map.of() : Map.copyOf(logKindIssues);
    }

    /** 返回该日志种类不可用的原因；可用时返回 null */
    public String issueOf(LogKind kind) {
        return logKindIssues.get(kind);
    }

    public boolean supportsRole(ComponentRole role) {
        return roles.isEmpty() || roles.contains(ComponentRole.GENERIC) || roles.contains(role);
    }

    public boolean supportsLogKind(LogKind kind) {
        return logKinds.isEmpty() || logKinds.contains(kind);
    }

    /**
     * 按主机名查找该主机上服务的访问地址。
     * <p>同时尝试原始 host、去掉端口的 host、以及 host:port 组合，配置里不必穷举写法。</p>
     *
     * @return 未配置时返回 null（由适配器决定降级或报错）
     */
    public String workerEndpoint(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        Map<String, String> endpoints = config.getWorkerEndpoints();
        if (endpoints == null || endpoints.isEmpty()) {
            return null;
        }
        String trimmed = host.trim();
        String noPort = stripPort(trimmed);
        String[] candidates = {trimmed, noPort, trimmed.toLowerCase(Locale.ROOT), noPort.toLowerCase(Locale.ROOT)};
        for (String candidate : candidates) {
            String value = endpoints.get(candidate);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        // 配置里可能写成完整 URL，按主机名反查
        for (Map.Entry<String, String> entry : endpoints.entrySet()) {
            String key = entry.getKey();
            if (key != null && stripPort(key).equalsIgnoreCase(noPort)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String stripPort(String value) {
        int idx = value.lastIndexOf(':');
        if (idx > 0 && value.indexOf(':') == idx) {
            return value.substring(0, idx);
        }
        return value;
    }
}
