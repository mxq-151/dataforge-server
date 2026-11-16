package com.bigdata.modules.telemetry.config.properties;


public class SparkShsRestConfig {
    private String id = "spark-shs-rest";
    private String baseUrl = "";
    /** history / live */
    private String mode = "history";
    private Auth auth = new Auth();
    private EngineLimits limits = new EngineLimits();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Auth getAuth() {
        return auth;
    }

    public void setAuth(Auth auth) {
        this.auth = auth;
    }

    public EngineLimits getLimits() {
        return limits;
    }

    public void setLimits(EngineLimits limits) {
        this.limits = limits;
    }
}
