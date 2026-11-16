package com.bigdata.modules.telemetry.config.properties;

import java.util.ArrayList;
import java.util.List;

public class EsConfig {
    private String id = "es-logs";
    private List<String> urls = new ArrayList<>();
    private String index = "dataforge-logs-*";
    private Auth auth = new Auth();
    private EsLimits limits = new EsLimits();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public List<String> getUrls() {
        return urls;
    }

    public void setUrls(List<String> urls) {
        this.urls = urls;
    }

    public String getIndex() {
        return index;
    }

    public void setIndex(String index) {
        this.index = index;
    }

    public Auth getAuth() {
        return auth;
    }

    public void setAuth(Auth auth) {
        this.auth = auth;
    }

    public EsLimits getLimits() {
        return limits;
    }

    public void setLimits(EsLimits limits) {
        this.limits = limits;
    }
}
