package com.bigdata.modules.logs.vo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志源列表项。
 *
 * <p>刻意不返回 base-url 与任何凭据，只返回「是否已配置地址」；地址与 token 属于服务端配置，
 * 不应经此接口外泄。</p>
 */
public class LogSourceVO {

    private String id;
    private String name;
    private String serviceType;
    private String cluster;
    private boolean enabled;
    private String disabledReason;
    private boolean baseUrlConfigured;
    private List<String> roles = new ArrayList<>();
    private List<String> logKinds = new ArrayList<>();
    /** 已声明但当前配置下不可用的日志种类 -> 原因 */
    private Map<String, String> unavailableLogKinds = new LinkedHashMap<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getServiceType() {
        return serviceType;
    }

    public void setServiceType(String serviceType) {
        this.serviceType = serviceType;
    }

    public String getCluster() {
        return cluster;
    }

    public void setCluster(String cluster) {
        this.cluster = cluster;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDisabledReason() {
        return disabledReason;
    }

    public void setDisabledReason(String disabledReason) {
        this.disabledReason = disabledReason;
    }

    public boolean isBaseUrlConfigured() {
        return baseUrlConfigured;
    }

    public void setBaseUrlConfigured(boolean baseUrlConfigured) {
        this.baseUrlConfigured = baseUrlConfigured;
    }

    public List<String> getRoles() {
        return roles;
    }

    public void setRoles(List<String> roles) {
        this.roles = roles == null ? new ArrayList<>() : roles;
    }

    public List<String> getLogKinds() {
        return logKinds;
    }

    public void setLogKinds(List<String> logKinds) {
        this.logKinds = logKinds == null ? new ArrayList<>() : logKinds;
    }

    public Map<String, String> getUnavailableLogKinds() {
        return unavailableLogKinds;
    }

    public void setUnavailableLogKinds(Map<String, String> unavailableLogKinds) {
        this.unavailableLogKinds = unavailableLogKinds == null
                ? new LinkedHashMap<>() : unavailableLogKinds;
    }
}
