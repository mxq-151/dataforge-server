package com.bigdata.modules.runtime.config;

import com.bigdata.modules.logs.config.LogAccessProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行时指标快照配置（application.yml 中 {@code runtime.*} 前缀）。
 *
 * <p>复用日志模块的源配置模型（{@link LogAccessProperties.Auth} / {@link LogAccessProperties.Limits}）
 * 与凭据、脱敏、RBAC、审计、熔断、线程池实现；但指标是有结构的快照，不使用日志的游标语义。</p>
 */
@Component
@ConfigurationProperties(prefix = "runtime")
public class RuntimeProperties {

    /** 总开关；关闭后所有 runtime 接口返回「未启用」 */
    private boolean enabled = true;
    private Query query = new Query();
    private Cache cache = new Cache();
    private LogAccessProperties.Rbac rbac = defaultRbac();
    private List<Source> sources = new ArrayList<>();

    private static LogAccessProperties.Rbac defaultRbac() {
        LogAccessProperties.Rbac rbac = new LogAccessProperties.Rbac();
        rbac.setDefaultAllowedServices(new ArrayList<>(List.of("spark", "flink")));
        return rbac;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Query getQuery() {
        return query;
    }

    public void setQuery(Query query) {
        this.query = query;
    }

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache;
    }

    public LogAccessProperties.Rbac getRbac() {
        return rbac;
    }

    public void setRbac(LogAccessProperties.Rbac rbac) {
        this.rbac = rbac;
    }

    public List<Source> getSources() {
        return sources;
    }

    public void setSources(List<Source> sources) {
        this.sources = sources;
    }

    /** 单次快照的全局预算，避免一次调用把集群打满 */
    public static class Query {
        /** 单次接口总超时（毫秒） */
        private long timeoutMs = 12000L;
        /** 单次快照最多返回的组件数（TM / executor） */
        private int maxComponents = 100;
        /** 单次快照最多展开的 vertex 数（Flink） */
        private int maxVertices = 20;
        /** checkpoint 历史最多返回条数 */
        private int checkpointHistoryLimit = 5;
        /** 显式指标名最多数量 */
        private int maxMetricNames = 200;

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getMaxComponents() {
            return maxComponents;
        }

        public void setMaxComponents(int maxComponents) {
            this.maxComponents = maxComponents;
        }

        public int getMaxVertices() {
            return maxVertices;
        }

        public void setMaxVertices(int maxVertices) {
            this.maxVertices = maxVertices;
        }

        public int getCheckpointHistoryLimit() {
            return checkpointHistoryLimit;
        }

        public void setCheckpointHistoryLimit(int checkpointHistoryLimit) {
            this.checkpointHistoryLimit = checkpointHistoryLimit;
        }

        public int getMaxMetricNames() {
            return maxMetricNames;
        }

        public void setMaxMetricNames(int maxMetricNames) {
            this.maxMetricNames = maxMetricNames;
        }
    }

    /** 短 TTL 缓存：指标是快照，缓存时间应短于日志；配置参数变化慢，单独给更长 TTL */
    public static class Cache {
        private boolean enabled = true;
        /** 作业列表 / 组件列表 / 快照 TTL */
        private long ttlMs = 10000L;
        /** 配置参数查询 TTL */
        private long configTtlMs = 60000L;
        private int maxEntries = 200;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getTtlMs() {
            return ttlMs;
        }

        public void setTtlMs(long ttlMs) {
            this.ttlMs = ttlMs;
        }

        public long getConfigTtlMs() {
            return configTtlMs;
        }

        public void setConfigTtlMs(long configTtlMs) {
            this.configTtlMs = configTtlMs;
        }

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }
    }

    /** 运行时源配置；字段与日志源同构，便于运维复用已有认知 */
    public static class Source {
        private String id = "";
        private String name = "";
        /** 服务类型：flink / spark */
        private String serviceType = "";
        private String cluster = "default";
        /** live（运行中 UI / JobManager REST）或 history（History Server） */
        private String mode = "live";
        private boolean enabled = true;
        private String baseUrl = "";
        private List<String> roles = new ArrayList<>();
        private LogAccessProperties.Auth auth = new LogAccessProperties.Auth();
        private Options options = new Options();
        private LogAccessProperties.Limits limits = new LogAccessProperties.Limits();

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

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public List<String> getRoles() {
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles;
        }

        public LogAccessProperties.Auth getAuth() {
            return auth;
        }

        public void setAuth(LogAccessProperties.Auth auth) {
            this.auth = auth;
        }

        public Options getOptions() {
            return options;
        }

        public void setOptions(Options options) {
            this.options = options;
        }

        public LogAccessProperties.Limits getLimits() {
            return limits;
        }

        public void setLimits(LogAccessProperties.Limits limits) {
            this.limits = limits;
        }
    }

    /** 适配器选项 */
    public static class Options {
        /** 单次 REST 请求超时（毫秒），仅收紧不放大全局预算 */
        private int restTimeoutMs = 3000;
        /** 未指定 families 时的默认指标族 */
        private List<String> defaultMetricFamilies = new ArrayList<>(List.of("jvm_gc", "checkpoint"));
        /** 单次快照最多返回的组件数覆盖值（<=0 用全局） */
        private int maxComponents = 0;
        /** 单次快照最多展开的 vertex 数覆盖值（<=0 用全局） */
        private int maxVertices = 0;
        /** checkpoint 历史最多返回条数覆盖值（<=0 用全局） */
        private int checkpointHistoryLimit = 0;
        /** Flink GC 具体 collector 名（如 G1 Young Generation）；留空用通配符 */
        private List<String> gcCollectors = new ArrayList<>();
        /** Spark live：YARN ResourceManager 地址，用于发现 appId 与 trackingUrl */
        private String yarnRmUrl = "";
        /** Spark live：默认 appId（单应用 UI 场景） */
        private String appId = "";
        /** Spark live：tracking URL 模板，如 http://rm:8088/proxy/{appId} */
        private String trackingUrlTemplate = "";
        /** Spark：stage 列表最多保留的 stage 数（按 attempt 去重后） */
        private int maxStages = 100;

        public int getRestTimeoutMs() {
            return restTimeoutMs;
        }

        public void setRestTimeoutMs(int restTimeoutMs) {
            this.restTimeoutMs = restTimeoutMs;
        }

        public List<String> getDefaultMetricFamilies() {
            return defaultMetricFamilies;
        }

        public void setDefaultMetricFamilies(List<String> defaultMetricFamilies) {
            this.defaultMetricFamilies = defaultMetricFamilies;
        }

        public int getMaxComponents() {
            return maxComponents;
        }

        public void setMaxComponents(int maxComponents) {
            this.maxComponents = maxComponents;
        }

        public int getMaxVertices() {
            return maxVertices;
        }

        public void setMaxVertices(int maxVertices) {
            this.maxVertices = maxVertices;
        }

        public int getCheckpointHistoryLimit() {
            return checkpointHistoryLimit;
        }

        public void setCheckpointHistoryLimit(int checkpointHistoryLimit) {
            this.checkpointHistoryLimit = checkpointHistoryLimit;
        }

        public List<String> getGcCollectors() {
            return gcCollectors;
        }

        public void setGcCollectors(List<String> gcCollectors) {
            this.gcCollectors = gcCollectors;
        }

        public String getYarnRmUrl() {
            return yarnRmUrl;
        }

        public void setYarnRmUrl(String yarnRmUrl) {
            this.yarnRmUrl = yarnRmUrl;
        }

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId;
        }

        public String getTrackingUrlTemplate() {
            return trackingUrlTemplate;
        }

        public void setTrackingUrlTemplate(String trackingUrlTemplate) {
            this.trackingUrlTemplate = trackingUrlTemplate;
        }

        public int getMaxStages() {
            return maxStages;
        }

        public void setMaxStages(int maxStages) {
            this.maxStages = maxStages;
        }
    }
}
