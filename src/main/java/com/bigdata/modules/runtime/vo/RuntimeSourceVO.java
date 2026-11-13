package com.bigdata.modules.runtime.vo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 运行时数据源列表项；不返回地址与凭据 */
public class RuntimeSourceVO {

    private String id;
    private String name;
    private String serviceType;
    private String cluster;
    private String mode;
    private boolean enabled;
    private String disabledReason;
    private boolean baseUrlConfigured;
    private List<String> roles = new ArrayList<>();
    private List<String> supportedFamilies = new ArrayList<>();
    /** 该服务/版本不提供的指标族 -> 原因 */
    private Map<String, String> unavailableFamilies = new LinkedHashMap<>();
    private long cacheTtlMs;

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

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
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

    public List<String> getSupportedFamilies() {
        return supportedFamilies;
    }

    public void setSupportedFamilies(List<String> supportedFamilies) {
        this.supportedFamilies = supportedFamilies == null ? new ArrayList<>() : supportedFamilies;
    }

    public Map<String, String> getUnavailableFamilies() {
        return unavailableFamilies;
    }

    public void setUnavailableFamilies(Map<String, String> unavailableFamilies) {
        this.unavailableFamilies = unavailableFamilies == null
                ? new LinkedHashMap<>() : unavailableFamilies;
    }

    public long getCacheTtlMs() {
        return cacheTtlMs;
    }

    public void setCacheTtlMs(long cacheTtlMs) {
        this.cacheTtlMs = cacheTtlMs;
    }
}
