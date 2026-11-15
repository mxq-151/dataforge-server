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
    private List<DsConfig> dsApi = new ArrayList<>();
    private List<DorisAuditConfig> dorisAudit = new ArrayList<>();

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

    public List<DsConfig> getDsApi() {
        return dsApi;
    }

    public void setDsApi(List<DsConfig> dsApi) {
        this.dsApi = dsApi;
    }

    public List<DorisAuditConfig> getDorisAudit() {
        return dorisAudit;
    }

    public void setDorisAudit(List<DorisAuditConfig> dorisAudit) {
        this.dorisAudit = dorisAudit;
    }

    /**
     * DolphinScheduler 任务日志配置：登录后按 taskInstanceId 读取 {@code /log/detail}，
     * 只承载 {@code log.task}，不用于服务自身日志。
     */
    public static class DsConfig {
        private String id = "ds-api";
        /** DS API 根地址，需包含上下文，如 http://ds:12345/dolphinscheduler。 */
        private String baseUrl = "";
        /** 登录凭据：username / password 或对应的 secret ref。 */
        private Auth auth = new Auth();
        /** 可选的项目范围；留空时遍历当前账号的授权项目。 */
        private List<Long> projectCodes = new ArrayList<>();
        private DsLimits limits = new DsLimits();

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

        public List<Long> getProjectCodes() {
            return projectCodes;
        }

        public void setProjectCodes(List<Long> projectCodes) {
            this.projectCodes = projectCodes;
        }

        public DsLimits getLimits() {
            return limits;
        }

        public void setLimits(DsLimits limits) {
            this.limits = limits;
        }
    }

    public static class DsLimits {
        private int timeoutMs = 10000;
        private long maxBytes = 1048576L;
        private int maxLines = 5000;
        /** 单页请求的行数上限。 */
        private int pageSize = 1000;
        /** 最多翻页次数，防止异常场景无限分页。 */
        private int maxPages = 20;

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

        public int getMaxLines() {
            return maxLines;
        }

        public void setMaxLines(int maxLines) {
            this.maxLines = maxLines;
        }

        public int getPageSize() {
            return pageSize;
        }

        public void setPageSize(int pageSize) {
            this.pageSize = pageSize;
        }

        public int getMaxPages() {
            return maxPages;
        }

        public void setMaxPages(int maxPages) {
            this.maxPages = maxPages;
        }
    }

    /**
     * YARN 容器日志配置：已聚合时走 {@code yarn logs} CLI；聚合未完成时回退到
     * NM 容器本地日志目录（RM {@code /ws/v1/cluster/nodes} → NM {@code /ws/v1/node/containers}
     * 定位 containerId，再按 {@code node-log-root/appId/containerId/stream} 直读）。
     *
     * <p>RM REST 同时用于把 {@code ref.jobName} 解析成 applicationId。</p>
     */
    public static class YarnConfig {
        private String id = "yarn";
        /** ResourceManager REST 根地址；只提供 jobName 或需要 node-local 回退时必填。 */
        private String rmBaseUrl = "";
        /** yarn 可执行文件（绝对路径或 PATH 中的命令名）。 */
        private String command = "yarn";
        /** 追加在 command 之后的固定参数（如 -D 配置）。 */
        private List<String> extraArgs = new ArrayList<>();
        /**
         * 本进程可见的 NM 容器日志根目录（如挂载到宿主后的 {@code /var/lib/dataforge-yarn/nm-logs}）。
         * 留空则 node-local 路径不可用；未挂载时即使配置了也读不到文件。
         */
        private String nodeLogRoot = "";
        /**
         * NM REST 根地址覆盖，单节点/端口映射场景使用（如 {@code http://127.0.0.1:8042}）。
         * 留空时按 RM 返回的 {@code nodeHTTPAddress} 逐个拼接。
         */
        private String nmBaseUrl = "";
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

        public String getNodeLogRoot() {
            return nodeLogRoot;
        }

        public void setNodeLogRoot(String nodeLogRoot) {
            this.nodeLogRoot = nodeLogRoot;
        }

        public String getNmBaseUrl() {
            return nmBaseUrl;
        }

        public void setNmBaseUrl(String nmBaseUrl) {
            this.nmBaseUrl = nmBaseUrl;
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
        /** node-local 回退时最多扫描的 NM 节点数，避免大集群逐个探测。 */
        private int maxNodes = 50;

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

        public int getMaxNodes() {
            return maxNodes;
        }

        public void setMaxNodes(int maxNodes) {
            this.maxNodes = maxNodes;
        }
    }

    /** Flink JobManager / REST 网关配置。 */
    public static class FlinkRestConfig {
        private String id = "flink-rest";
        private String baseUrl = "";
        private Auth auth = new Auth();
        private EngineLimits limits = new EngineLimits();
        /** 异常摘要最多返回条数；Flink 2.2 自身默认上限也是 20。 */
        private int maxExceptions = 20;
        /** 每条根异常最多保留的并发异常条数，超出只计数并标记 truncated。 */
        private int maxConcurrentExceptions = 20;
        /** 单条 stacktrace 输出的字符上限，超出客户端截断并标记。 */
        private int maxStackTraceChars = 20000;

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

        public int getMaxExceptions() {
            return maxExceptions;
        }

        public void setMaxExceptions(int maxExceptions) {
            this.maxExceptions = maxExceptions;
        }

        public int getMaxConcurrentExceptions() {
            return maxConcurrentExceptions;
        }

        public void setMaxConcurrentExceptions(int maxConcurrentExceptions) {
            this.maxConcurrentExceptions = maxConcurrentExceptions;
        }

        public int getMaxStackTraceChars() {
            return maxStackTraceChars;
        }

        public void setMaxStackTraceChars(int maxStackTraceChars) {
            this.maxStackTraceChars = maxStackTraceChars;
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

    /**
     * Doris 审计日志配置：只承载 {@code log.service} 的 {@code slowquery}。
     *
     * <p>走固定只读 SQL 查询 {@code __internal_schema.audit_log}（需 FE
     * {@code enable_audit_plugin=true}），没有任意 SQL 入口；条件、排序列与 LIMIT 都是模板的一部分。
     * 表不存在或审计未开启时本后端不可用，由目录声明的 {@code local-disk} 退路去解析
     * FE 的 {@code fe.audit.log}。</p>
     */
    public static class DorisAuditConfig {
        private String id = "doris-audit";
        /** FE MySQL 协议地址，需指向能访问 __internal_schema 的 FE。 */
        private String jdbcUrl = "jdbc:mysql://127.0.0.1:9030/__internal_schema";
        private String username = "root";
        private String password = "";
        private String usernameRef = "";
        private String passwordRef = "";
        /** 慢查询阈值（毫秒），对应 audit_log.query_time。Doris 自身 qe_slow_log_ms 默认 5000。 */
        private long slowQueryMs = 5000L;
        /**
         * audit_log.time 所在时区（FE 进程时区）。留空用 JVM 默认时区；
         * 两者不一致时时间窗会整体偏移，必须按实际 FE 时区配置。
         */
        private String zone = "";
        /** 请求未带时间范围时的默认回看窗口（小时），避免全表扫描。 */
        private int defaultLookbackHours = 24;
        private DorisAuditLimits limits = new DorisAuditLimits();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getJdbcUrl() {
            return jdbcUrl;
        }

        public void setJdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getUsernameRef() {
            return usernameRef;
        }

        public void setUsernameRef(String usernameRef) {
            this.usernameRef = usernameRef;
        }

        public String getPasswordRef() {
            return passwordRef;
        }

        public void setPasswordRef(String passwordRef) {
            this.passwordRef = passwordRef;
        }

        public long getSlowQueryMs() {
            return slowQueryMs;
        }

        public void setSlowQueryMs(long slowQueryMs) {
            this.slowQueryMs = slowQueryMs;
        }

        public String getZone() {
            return zone;
        }

        public void setZone(String zone) {
            this.zone = zone;
        }

        public int getDefaultLookbackHours() {
            return defaultLookbackHours;
        }

        public void setDefaultLookbackHours(int defaultLookbackHours) {
            this.defaultLookbackHours = defaultLookbackHours;
        }

        public DorisAuditLimits getLimits() {
            return limits;
        }

        public void setLimits(DorisAuditLimits limits) {
            this.limits = limits;
        }
    }

    public static class DorisAuditLimits {
        private int timeoutMs = 10000;
        private int maxRows = 500;
        private long maxBytes = 524288L;

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getMaxRows() {
            return maxRows;
        }

        public void setMaxRows(int maxRows) {
            this.maxRows = maxRows;
        }

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
        }
    }
}
