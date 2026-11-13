package com.bigdata.modules.runtime.vo;

/** 指标对照表的一行（供前端/智能体查询指标名与单位） */
public class RuntimeMetricDefinitionVO {

    private String serviceType;
    private String family;
    private String name;
    private String rawMetric;
    private String scope;
    private String endpoint;
    private String unit;
    private boolean verified;
    private String note;

    public String getServiceType() {
        return serviceType;
    }

    public void setServiceType(String serviceType) {
        this.serviceType = serviceType;
    }

    public String getFamily() {
        return family;
    }

    public void setFamily(String family) {
        this.family = family;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRawMetric() {
        return rawMetric;
    }

    public void setRawMetric(String rawMetric) {
        this.rawMetric = rawMetric;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public boolean isVerified() {
        return verified;
    }

    public void setVerified(boolean verified) {
        this.verified = verified;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
