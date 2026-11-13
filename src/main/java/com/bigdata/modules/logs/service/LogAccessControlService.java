package com.bigdata.modules.logs.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.utils.SecurityUtils;
import com.bigdata.modules.logs.config.LogAccessProperties;
import com.bigdata.modules.logs.model.LogSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 日志访问 RBAC（服务 + 集群维度）。
 *
 * <p>判定顺序：超级管理员放行 → {@code admin-only} 拒绝 → 角色规则匹配 →
 * 未命中时看 {@code default-allowed-services}。规则维度为空表示该维度不限制。</p>
 *
 * <p>MVP 用配置承载，避免改表结构与权限管理界面；后续若需要按租户持久化，
 * 只需替换本类的数据来源，接口保持不变。</p>
 */
@Service
public class LogAccessControlService {

    private final LogAccessProperties properties;

    public LogAccessControlService(LogAccessProperties properties) {
        this.properties = properties;
    }

    public void check(LogSource source) {
        if (!canRead(source)) {
            throw new BusinessException(403,
                    "无权查看服务 " + source.getServiceType().wireName()
                            + "（集群 " + source.getCluster() + "）的日志");
        }
    }

    /** 通用校验：供 runtime 等模块复用同一套判定逻辑，策略（Rbac）由调用方传入 */
    public void check(String service, String cluster, LogAccessProperties.Rbac rbac, String action) {
        if (!canRead(service, cluster, rbac)) {
            throw new BusinessException(403,
                    "无权查看服务 " + service + "（集群 " + cluster + "）的" + action);
        }
    }

    public boolean canRead(LogSource source) {
        if (source == null || source.getServiceType() == null) {
            return false;
        }
        return canRead(source.getServiceType().wireName(), source.getCluster(), properties.getRbac());
    }

    /** 通用判定：服务 + 集群维度，角色规则与默认白名单逻辑与日志完全一致 */
    public boolean canRead(String service, String cluster, LogAccessProperties.Rbac rbac) {
        if (service == null || service.isBlank()) {
            return false;
        }
        if (SecurityUtils.isAdmin()) {
            return true;
        }
        if (rbac == null || rbac.isAdminOnly()) {
            return false;
        }
        String clusterName = cluster == null || cluster.isBlank() ? "default" : cluster;
        List<String> userRoles = SecurityUtils.getCurrentUserRoles();

        if (rbac.getRules() != null) {
            for (LogAccessProperties.Rule rule : rbac.getRules()) {
                if (!rolesMatch(rule.getRoles(), userRoles)) {
                    continue;
                }
                if (!valueMatches(rule.getServices(), service)) {
                    continue;
                }
                if (!valueMatches(rule.getClusters(), clusterName)) {
                    continue;
                }
                return true;
            }
        }
        return containsIgnoreCase(rbac.getDefaultAllowedServices(), service);
    }

    private boolean rolesMatch(List<String> ruleRoles, List<String> userRoles) {
        if (ruleRoles == null || ruleRoles.isEmpty()) {
            return true;
        }
        if (userRoles == null || userRoles.isEmpty()) {
            return false;
        }
        for (String role : userRoles) {
            if (containsIgnoreCase(ruleRoles, role)) {
                return true;
            }
        }
        return false;
    }

    private boolean valueMatches(List<String> allowed, String value) {
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        return containsIgnoreCase(allowed, value);
    }

    private boolean containsIgnoreCase(List<String> values, String target) {
        if (values == null || target == null) {
            return false;
        }
        String wanted = target.trim().toLowerCase(Locale.ROOT);
        for (String value : values) {
            if (value != null && value.trim().toLowerCase(Locale.ROOT).equals(wanted)) {
                return true;
            }
        }
        return false;
    }
}
