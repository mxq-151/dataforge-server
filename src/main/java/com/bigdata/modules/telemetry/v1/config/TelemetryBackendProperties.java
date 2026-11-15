package com.bigdata.modules.telemetry.v1.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 存储后端实例配置（{@code telemetry.backends.*}）。
 *
 * <p>实例 id 由配置给出；目录里的逻辑后端名会展开为同类型的全部实例。未配置实例不会伪造数据，
 * 查询返回 NOT_CONFIGURED + 具体配置键。</p>
 */
@Component
@ConfigurationProperties(prefix = "telemetry.backends")
public class TelemetryBackendProperties {

    private List<PromqlConfig> prometheus = new ArrayList<>();
    private List<PromqlConfig> victoriametrics = new ArrayList<>();
    private List<LocalDiskConfig> localDisk = new ArrayList<>();
    private List<EsConfig> elasticsearch = new ArrayList<>();
    private List<FlinkRestConfig> flinkRest = new ArrayList<>();
    private List<SparkRestConfig> sparkRest = new ArrayList<>();
    private List<YarnConfig> yarn = new ArrayList<>();

    public List<PromqlConfig> getPrometheus() {
        return prometheus;
    }

    public void setPrometheus(List<PromqlConfig> prometheus) {
        this.prometheus = prometheus;
    }

    public List<PromqlConfig> getVictoriametrics() {
        return victoriametrics;
    }

    public void setVictoriametrics(List<PromqlConfig> victoriametrics) {
        this.victoriametrics = victoriametrics;
    }

    public List<LocalDiskConfig> getLocalDisk() {
        return localDisk;
    }

    public void setLocalDisk(List<LocalDiskConfig> localDisk) {
        this.localDisk = localDisk;
    }

    public List<EsConfig> getElasticsearch() {
        return elasticsearch;
    }

    public void setElasticsearch(List<EsConfig> elasticsearch) {
        this.elasticsearch = elasticsearch;
    }

    public List<FlinkRestConfig> getFlinkRest() {
        return flinkRest;
    }

    public void setFlinkRest(List<FlinkRestConfig> flinkRest) {
        this.flinkRest = flinkRest;
    }

    public List<SparkRestConfig> getSparkRest() {
        return sparkRest;
    }

    public void setSparkRest(List<SparkRestConfig> sparkRest) {
        this.sparkRest = sparkRest;
    }

    public List<YarnConfig> getYarn() {
        return yarn;
    }

    public void setYarn(List<YarnConfig> yarn) {
        this.yarn = yarn;
    }

    /**
     * YARN 聚合日志配置：走 {@code yarn logs} CLI 读取已聚合日志，
     * RM REST 仅用于把 {@code ref.jobName} 解析成 applicationId。
     */
    public static class YarnConfig {
        private String id = "yarn";
        /** ResourceManager REST 根地址；只提供 jobName 时必填。 */
        private String rmBaseUrl = "";
        /** yarn 可执行文件（绝对路径或 PATH 中的命令名）。 */
        private String command = "yarn";
        /** 追加在 command 之后的固定参数（如 -D 配置）。 */
        private List<String> extraArgs = new ArrayList<>();
        private Auth auth = new Auth();
        private YarnLimits limits = new YarnLimits();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getRmBaseUrl() {
            return rmBaseUrl;
        }

        public void setRmBaseUrl(String rmBaseUrl) {
            this.rmBaseUrl = rmBaseUrl;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public List<String> getExtraArgs() {
            return extraArgs;
        }

        public void setExtraArgs(List<String> extraArgs) {
            this.extraArgs = extraArgs;
        }

        public Auth getAuth() {
            return auth;
        }

        public void setAuth(Auth auth) {
            this.auth = auth;
        }

        public YarnLimits getLimits() {
            return limits;
        }

        public void setLimits(YarnLimits limits) {
            this.limits = limits;
        }
    }

    public static class YarnLimits {
        /** 单次 CLI 调用超时。 */
        private int timeoutMs = 30000;
        /** 最多捕获的 stdout 字节数，超出即截断。 */
        private long maxBytes = 1048576L;
        /** 同时运行的 yarn logs 子进程上限。 */
        private int maxConcurrent = 2;
        /** 等待并发槽位的最长时间，超时返回 RATE_LIMITED。 */
        private long acquireTimeoutMs = 2000L;

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        public int getMaxConcurrent() {
            return maxConcurrent;
        }

        public void setMaxConcurrent(int maxConcurrent) {
            this.maxConcurrent = maxConcurrent;
        }

        public long getAcquireTimeoutMs() {
            return acquireTimeoutMs;
        }

        public void setAcquireTimeoutMs(long acquireTimeoutMs) {
            this.acquireTimeoutMs = acquireTimeoutMs;
        }
    }

    /** Flink JobManager / REST 网关配置。 */
    public static class FlinkRestConfig {
        private String id = "flink-rest";
        private String baseUrl = "";
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

    /** Spark History Server（mode=history）或 live UI（mode=live）配置。 */
    public static class SparkRestConfig {
        private String id = "spark-rest";
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

    public static class EngineLimits {
        private int timeoutMs = 10000;
        private long maxResponseBytes = 8388608L;
        private int maxSeries = 2000;

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public long getMaxResponseBytes() {
            return maxResponseBytes;
        }

        public void setMaxResponseBytes(long maxResponseBytes) {
            this.maxResponseBytes = maxResponseBytes;
        }

        public int getMaxSeries() {
            return maxSeries;
        }

        public void setMaxSeries(int maxSeries) {
            this.maxSeries = maxSeries;
        }
    }

    /** Prometheus / VictoriaMetrics 共用配置，差异只在 base-url、path-prefix 与 query 选项。 */
    public static class PromqlConfig {
        private String id = "";
        private String baseUrl = "";
        private String pathPrefix = "/api/v1";
        private Auth auth = new Auth();
        private QueryOptions query = new QueryOptions();
        private Limits limits = new Limits();

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
    }

    public static class Auth {
        /** none / basic / bearer */
        private String type = "none";
        private String username = "";
        private String usernameRef = "";
        private String password = "";
        private String passwordRef = "";
        private String token = "";
        private String tokenRef = "";

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getUsernameRef() {
            return usernameRef;
        }

        public void setUsernameRef(String usernameRef) {
            this.usernameRef = usernameRef;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getPasswordRef() {
            return passwordRef;
        }

        public void setPasswordRef(String passwordRef) {
            this.passwordRef = passwordRef;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getTokenRef() {
            return tokenRef;
        }

        public void setTokenRef(String tokenRef) {
            this.tokenRef = tokenRef;
        }
    }

    public static class QueryOptions {
        /** VM 专用：是否允许返回部分结果；默认 false，避免把部分结果当完整。 */
        private boolean partialResponse = false;
        private int maxPointsPerTimeseries = 11000;

        public boolean isPartialResponse() {
            return partialResponse;
        }

        public void setPartialResponse(boolean partialResponse) {
            this.partialResponse = partialResponse;
        }

        public int getMaxPointsPerTimeseries() {
            return maxPointsPerTimeseries;
        }

        public void setMaxPointsPerTimeseries(int maxPointsPerTimeseries) {
            this.maxPointsPerTimeseries = maxPointsPerTimeseries;
        }
    }

    public static class Limits {
        private int maxSeries = 5000;
        private int maxPointsPerSeries = 11000;
        private long maxResponseBytes = 8388608L;
        private int timeoutMs = 15000;

        public int getMaxSeries() {
            return maxSeries;
        }

        public void setMaxSeries(int maxSeries) {
            this.maxSeries = maxSeries;
        }

        public int getMaxPointsPerSeries() {
            return maxPointsPerSeries;
        }

        public void setMaxPointsPerSeries(int maxPointsPerSeries) {
            this.maxPointsPerSeries = maxPointsPerSeries;
        }

        public long getMaxResponseBytes() {
            return maxResponseBytes;
        }

        public void setMaxResponseBytes(long maxResponseBytes) {
            this.maxResponseBytes = maxResponseBytes;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    public static class LocalDiskConfig {
        private String id = "local-disk";
        private List<String> allowRootDirs = new ArrayList<>();
        private List<LogSourceConfig> sources = new ArrayList<>();
        private LogLimits limits = new LogLimits();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public List<String> getAllowRootDirs() {
            return allowRootDirs;
        }

        public void setAllowRootDirs(List<String> allowRootDirs) {
            this.allowRootDirs = allowRootDirs;
        }

        public List<LogSourceConfig> getSources() {
            return sources;
        }

        public void setSources(List<LogSourceConfig> sources) {
            this.sources = sources;
        }

        public LogLimits getLimits() {
            return limits;
        }

        public void setLimits(LogLimits limits) {
            this.limits = limits;
        }
    }

    /** 一条「服务 + 组件 + host + stream → 文件」的定位配置。 */
    public static class LogSourceConfig {
        private String serviceType = "";
        private String componentId = "";
        private String hostId = "cluster";
        private String stream = "";
        /** 单文件路径、通配符路径或目录。 */
        private String path = "";
        /** 行首时间戳格式（DateTimeFormatter 模式）；留空表示不支持时间范围过滤。 */
        private String timestampPattern = "";
        /** 时间戳时区，默认系统时区。 */
        private String zone = "";

        public String getServiceType() {
            return serviceType;
        }

        public void setServiceType(String serviceType) {
            this.serviceType = serviceType;
        }

        public String getComponentId() {
            return componentId;
        }

        public void setComponentId(String componentId) {
            this.componentId = componentId;
        }

        public String getHostId() {
            return hostId;
        }

        public void setHostId(String hostId) {
            this.hostId = hostId;
        }

        public String getStream() {
            return stream;
        }

        public void setStream(String stream) {
            this.stream = stream;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getTimestampPattern() {
            return timestampPattern;
        }

        public void setTimestampPattern(String timestampPattern) {
            this.timestampPattern = timestampPattern;
        }

        public String getZone() {
            return zone;
        }

        public void setZone(String zone) {
            this.zone = zone;
        }
    }

    public static class LogLimits {
        private int maxLines = 2000;
        private long maxBytes = 262144L;
        private long maxScanBytes = 67108864L;
        private int timeoutMs = 20000;

        public int getMaxLines() {
            return maxLines;
        }

        public void setMaxLines(int maxLines) {
            this.maxLines = maxLines;
        }

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        public long getMaxScanBytes() {
            return maxScanBytes;
        }

        public void setMaxScanBytes(long maxScanBytes) {
            this.maxScanBytes = maxScanBytes;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    public static class EsConfig {
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

    public static class EsLimits {
        private int timeoutMs = 15000;
        private long maxResponseBytes = 8388608L;
        private long mappingCacheTtlMs = 60000L;
        private int pitKeepAliveSeconds = 120;

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public long getMaxResponseBytes() {
            return maxResponseBytes;
        }

        public void setMaxResponseBytes(long maxResponseBytes) {
            this.maxResponseBytes = maxResponseBytes;
        }

        public long getMappingCacheTtlMs() {
            return mappingCacheTtlMs;
        }

        public void setMappingCacheTtlMs(long mappingCacheTtlMs) {
            this.mappingCacheTtlMs = mappingCacheTtlMs;
        }

        public int getPitKeepAliveSeconds() {
            return pitKeepAliveSeconds;
        }

        public void setPitKeepAliveSeconds(int pitKeepAliveSeconds) {
            this.pitKeepAliveSeconds = pitKeepAliveSeconds;
        }
    }
}
