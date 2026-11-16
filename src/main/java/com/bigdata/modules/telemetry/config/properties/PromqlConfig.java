package com.bigdata.modules.telemetry.config.properties;

import java.util.ArrayList;
import java.util.List;

public class PromqlConfig {
    private String id = "";
    private String baseUrl = "";
    private String pathPrefix = "/api/v1";
    private Auth auth = new Auth();
    private QueryOptions query = new QueryOptions();
    private Limits limits = new Limits();
    /**
     * 允许通过 label/__name__/values 发现的 serviceType。
     * 默认覆盖本环境实际抓取的 kafka / yarn，以及 SHS jmx_exporter 的 spark；
     * 只有加入 VM 抓取的服务才会出现在发现结果里。
     */
    private List<String> discoveryServiceTypes = new ArrayList<>(List.of("kafka", "yarn", "spark"));
    /**
     * 当指标存储的 /api/v1/metadata 为空时，可选的抓取文本端点（Prometheus 文本格式）。
     * 用于从 # TYPE / # HELP 行补全类型与单位，例如 Kafka JMX exporter、YARN exporter。
     */
    private List<String> metricMetadataUrls = new ArrayList<>();

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

    public String getPathPrefix() {
        return pathPrefix;
    }

    public void setPathPrefix(String pathPrefix) {
        this.pathPrefix = pathPrefix;
    }

    public Auth getAuth() {
        return auth;
    }

    public void setAuth(Auth auth) {
        this.auth = auth;
    }

    public QueryOptions getQuery() {
        return query;
    }

    public void setQuery(QueryOptions query) {
        this.query = query;
    }

    public Limits getLimits() {
        return limits;
    }

    public void setLimits(Limits limits) {
        this.limits = limits;
    }

    public List<String> getDiscoveryServiceTypes() {
        return discoveryServiceTypes;
    }

    public void setDiscoveryServiceTypes(List<String> discoveryServiceTypes) {
        this.discoveryServiceTypes = discoveryServiceTypes == null
                ? new ArrayList<>() : discoveryServiceTypes;
    }

    public List<String> getMetricMetadataUrls() {
        return metricMetadataUrls;
    }

    public void setMetricMetadataUrls(List<String> metricMetadataUrls) {
        this.metricMetadataUrls = metricMetadataUrls == null
                ? new ArrayList<>() : metricMetadataUrls;
    }
}
