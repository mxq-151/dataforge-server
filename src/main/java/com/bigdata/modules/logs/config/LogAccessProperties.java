package com.bigdata.modules.logs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一日志访问层配置（application.yml 中 {@code logs.*} 前缀）。
 *
 * <p>所有定位信息都来自配置，代码里不出现主机、端口、日志目录；新增服务只需增加配置项与适配器。</p>
 */
@Component
@ConfigurationProperties(prefix = "logs")
public class LogAccessProperties {

    /** 日志访问层总开关；关闭后所有接口返回「未启用」错误 */
    private boolean enabled = true;
    private Query query = new Query();
    private Chain chain = new Chain();
    private Cache cache = new Cache();
    private Masking masking = new Masking();
    private Rbac rbac = new Rbac();
    private Observability observability = new Observability();
    private Executor executor = new Executor();
    private Stream stream = new Stream();
    /** 日志源清单；每项对应一种服务在某个集群下的接入配置 */
    private List<Source> sources = new ArrayList<>();

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

    public Chain getChain() {
        return chain;
    }

    public void setChain(Chain chain) {
        this.chain = chain;
    }

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache;
    }

    public Masking getMasking() {
        return masking;
    }

    public void setMasking(Masking masking) {
        this.masking = masking;
    }

    public Rbac getRbac() {
        return rbac;
    }

    public void setRbac(Rbac rbac) {
        this.rbac = rbac;
    }

    public Observability getObservability() {
        return observability;
    }

    public void setObservability(Observability observability) {
        this.observability = observability;
    }

    public Executor getExecutor() {
        return executor;
    }

    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    public Stream getStream() {
        return stream;
    }

    public void setStream(Stream stream) {
        this.stream = stream;
    }

    public List<Source> getSources() {
        return sources;
    }

    public void setSources(List<Source> sources) {
        this.sources = sources;
    }

    /** 单次查询上限 */
    public static class Query {
        private int maxLines = 2000;
        private int maxBytes = 262144;
        private long timeoutMs = 20000L;
        private int maxKeywordLength = 200;

        public int getMaxLines() {
            return maxLines;
        }

        public void setMaxLines(int maxLines) {
            this.maxLines = maxLines;
        }

        public int getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getMaxKeywordLength() {
            return maxKeywordLength;
        }

        public void setMaxKeywordLength(int maxKeywordLength) {
            this.maxKeywordLength = maxKeywordLength;
        }
    }

    /** 跨服务串联预算 */
    public static class Chain {
        private int maxSegments = 3;
        private int segmentMaxBytes = 204800;
        private int totalMaxBytes = 614400;

        public int getMaxSegments() {
            return maxSegments;
        }

        public void setMaxSegments(int maxSegments) {
            this.maxSegments = maxSegments;
        }

        public int getSegmentMaxBytes() {
            return segmentMaxBytes;
        }

        public void setSegmentMaxBytes(int segmentMaxBytes) {
            this.segmentMaxBytes = segmentMaxBytes;
        }

        public int getTotalMaxBytes() {
            return totalMaxBytes;
        }

        public void setTotalMaxBytes(int totalMaxBytes) {
            this.totalMaxBytes = totalMaxBytes;
        }
    }

    /** 短 TTL 结果缓存 */
    public static class Cache {
        private boolean enabled = true;
        private long ttlMs = 5000L;
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

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }
    }

    /** 出口脱敏 */
    public static class Masking {
        private boolean enabled = true;
        /** 额外规则，格式 {@code 正则=>替换串} */
        private List<String> extraPatterns = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getExtraPatterns() {
            return extraPatterns;
        }

        public void setExtraPatterns(List<String> extraPatterns) {
            this.extraPatterns = extraPatterns;
        }
    }

    /** 配置化 RBAC */
    public static class Rbac {
        private boolean adminOnly = false;
        private List<String> defaultAllowedServices = new ArrayList<>(List.of("ds", "yarn", "spark"));
        private List<Rule> rules = new ArrayList<>();

        public boolean isAdminOnly() {
            return adminOnly;
        }

        public void setAdminOnly(boolean adminOnly) {
            this.adminOnly = adminOnly;
        }

        public List<String> getDefaultAllowedServices() {
            return defaultAllowedServices;
        }

        public void setDefaultAllowedServices(List<String> defaultAllowedServices) {
            this.defaultAllowedServices = defaultAllowedServices;
        }

        public List<Rule> getRules() {
            return rules;
        }

        public void setRules(List<Rule> rules) {
            this.rules = rules;
        }
    }

    /** 一条 RBAC 规则：空列表表示该维度不限制 */
    public static class Rule {
        private List<String> roles = new ArrayList<>();
        private List<String> services = new ArrayList<>();
        private List<String> clusters = new ArrayList<>();

        public List<String> getRoles() {
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles;
        }

        public List<String> getServices() {
            return services;
        }

        public void setServices(List<String> services) {
            this.services = services;
        }

        public List<String> getClusters() {
            return clusters;
        }

        public void setClusters(List<String> clusters) {
            this.clusters = clusters;
        }
    }

    /** 可观测性 */
    public static class Observability {
        private int slowQueryMs = 3000;

        public int getSlowQueryMs() {
            return slowQueryMs;
        }

        public void setSlowQueryMs(int slowQueryMs) {
            this.slowQueryMs = slowQueryMs;
        }
    }

    /** 按日志源隔离的线程池参数 */
    public static class Executor {
        private int corePoolSize = 2;
        private int maxPoolSize = 8;
        private int queueCapacity = 64;
        private int keepAliveSeconds = 60;

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public int getKeepAliveSeconds() {
            return keepAliveSeconds;
        }

        public void setKeepAliveSeconds(int keepAliveSeconds) {
            this.keepAliveSeconds = keepAliveSeconds;
        }
    }

    /** 流式推送参数 */
    public static class Stream {
        private int maxPages = 5;
        private long timeoutMs = 25000L;

        public int getMaxPages() {
            return maxPages;
        }

        public void setMaxPages(int maxPages) {
            this.maxPages = maxPages;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    /** 日志源配置 */
    public static class Source {
        private String id = "";
        private String name = "";
        /** 服务类型 wire 名：ds / yarn / spark / file */
        private String serviceType = "";
        private String cluster = "default";
        private boolean enabled = true;
        /** HTTP 类服务基础地址；DS 留空时回退 scheduler.dolphinscheduler.base-url */
        private String baseUrl = "";
        /** 主机 -> 该主机上服务的基础地址，用于 DS worker 路由 / YARN NM 定位 */
        private Map<String, String> workerEndpoints = new LinkedHashMap<>();
        private List<String> roles = new ArrayList<>();
        private List<String> logKinds = new ArrayList<>();
        private Auth auth = new Auth();
        private Options options = new Options();
        private Limits limits = new Limits();

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

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Map<String, String> getWorkerEndpoints() {
            return workerEndpoints;
        }

        public void setWorkerEndpoints(Map<String, String> workerEndpoints) {
            this.workerEndpoints = workerEndpoints;
        }

        public List<String> getRoles() {
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles;
        }

        public List<String> getLogKinds() {
            return logKinds;
        }

        public void setLogKinds(List<String> logKinds) {
            this.logKinds = logKinds;
        }

        public Auth getAuth() {
            return auth;
        }

        public void setAuth(Auth auth) {
            this.auth = auth;
        }

        public Options getOptions() {
            return options;
        }

        public void setOptions(Options options) {
            this.options = options;
        }

        public Limits getLimits() {
            return limits;
        }

        public void setLimits(Limits limits) {
            this.limits = limits;
        }
    }

    /** 凭据配置：tokenRef 支持 env: / file: / config: 引用，优先于 token */
    public static class Auth {
        private String token = "";
        private String tokenRef = "";
        private String username = "";
        private String usernameRef = "";

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
    }

    /** 适配器专属选项 */
    public static class Options {
        private List<String> rootDirs = new ArrayList<>();
        private String command = "yarn";
        private List<String> commandArgs = new ArrayList<>(List.of("logs", "-applicationId", "{appId}"));
        private int commandTimeoutMs = 60000;
        private String logsPathTemplate = "/api/v1/applications/{appId}/logs";
        private String defaultUser = "yarn";
        private boolean allowDownload = false;
        private long maxDownloadBytes = 5242880L;
        private long fetchBytes = 262144L;

        // ---------------- Flink ----------------
        /** Flink REST 单次请求超时（毫秒），用于限制对 JobManager 的压力 */
        private int restTimeoutMs = 3000;
        /** JobManager 日志根目录（本地/共享挂载） */
        private List<String> jobManagerLogDirs = new ArrayList<>();
        /** TaskManager 日志根目录（多个 TM 时全部列上，检索时合并） */
        private List<String> taskManagerLogDirs = new ArrayList<>();
        private String jobManagerLogPattern = "flink-*-jobmanager-*.log";
        private String taskManagerLogPattern = "flink-*-taskmanager-*.log";
        private String jobManagerStdoutPattern = "flink-*-jobmanager-*.out";
        private String taskManagerStdoutPattern = "flink-*-taskmanager-*.out";
        /** 单个日志文件最多读取的字节数（超出时只读末尾，避免日志文件过大） */
        private long maxFileBytes = 8388608L;
        /** K8s pod 标签选择器；P1 才提供容器日志适配器，P0 仅用于在 /sources 标注不可用 */
        private String podSelector = "";
        /** 对象存储日志路径；P1 才提供适配器 */
        private String objectStorePath = "";

        public List<String> getRootDirs() {
            return rootDirs;
        }

        public void setRootDirs(List<String> rootDirs) {
            this.rootDirs = rootDirs;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public List<String> getCommandArgs() {
            return commandArgs;
        }

        public void setCommandArgs(List<String> commandArgs) {
            this.commandArgs = commandArgs;
        }

        public int getCommandTimeoutMs() {
            return commandTimeoutMs;
        }

        public void setCommandTimeoutMs(int commandTimeoutMs) {
            this.commandTimeoutMs = commandTimeoutMs;
        }

        public String getLogsPathTemplate() {
            return logsPathTemplate;
        }

        public void setLogsPathTemplate(String logsPathTemplate) {
            this.logsPathTemplate = logsPathTemplate;
        }

        public String getDefaultUser() {
            return defaultUser;
        }

        public void setDefaultUser(String defaultUser) {
            this.defaultUser = defaultUser;
        }

        public boolean isAllowDownload() {
            return allowDownload;
        }

        public void setAllowDownload(boolean allowDownload) {
            this.allowDownload = allowDownload;
        }

        public long getMaxDownloadBytes() {
            return maxDownloadBytes;
        }

        public void setMaxDownloadBytes(long maxDownloadBytes) {
            this.maxDownloadBytes = maxDownloadBytes;
        }

        public long getFetchBytes() {
            return fetchBytes;
        }

        public void setFetchBytes(long fetchBytes) {
            this.fetchBytes = fetchBytes;
        }

        public int getRestTimeoutMs() {
            return restTimeoutMs;
        }

        public void setRestTimeoutMs(int restTimeoutMs) {
            this.restTimeoutMs = restTimeoutMs;
        }

        public List<String> getJobManagerLogDirs() {
            return jobManagerLogDirs;
        }

        public void setJobManagerLogDirs(List<String> jobManagerLogDirs) {
            this.jobManagerLogDirs = jobManagerLogDirs;
        }

        public List<String> getTaskManagerLogDirs() {
            return taskManagerLogDirs;
        }

        public void setTaskManagerLogDirs(List<String> taskManagerLogDirs) {
            this.taskManagerLogDirs = taskManagerLogDirs;
        }

        public String getJobManagerLogPattern() {
            return jobManagerLogPattern;
        }

        public void setJobManagerLogPattern(String jobManagerLogPattern) {
            this.jobManagerLogPattern = jobManagerLogPattern;
        }

        public String getTaskManagerLogPattern() {
            return taskManagerLogPattern;
        }

        public void setTaskManagerLogPattern(String taskManagerLogPattern) {
            this.taskManagerLogPattern = taskManagerLogPattern;
        }

        public String getJobManagerStdoutPattern() {
            return jobManagerStdoutPattern;
        }

        public void setJobManagerStdoutPattern(String jobManagerStdoutPattern) {
            this.jobManagerStdoutPattern = jobManagerStdoutPattern;
        }

        public String getTaskManagerStdoutPattern() {
            return taskManagerStdoutPattern;
        }

        public void setTaskManagerStdoutPattern(String taskManagerStdoutPattern) {
            this.taskManagerStdoutPattern = taskManagerStdoutPattern;
        }

        public long getMaxFileBytes() {
            return maxFileBytes;
        }

        public void setMaxFileBytes(long maxFileBytes) {
            this.maxFileBytes = maxFileBytes;
        }

        public String getPodSelector() {
            return podSelector;
        }

        public void setPodSelector(String podSelector) {
            this.podSelector = podSelector;
        }

        public String getObjectStorePath() {
            return objectStorePath;
        }

        public void setObjectStorePath(String objectStorePath) {
            this.objectStorePath = objectStorePath;
        }
    }

    /** 单源并发与熔断参数 */
    public static class Limits {
        private int maxConcurrency = 4;
        private int failureThreshold = 5;
        private long openMs = 30000L;

        public int getMaxConcurrency() {
            return maxConcurrency;
        }

        public void setMaxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
        }

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public long getOpenMs() {
            return openMs;
        }

        public void setOpenMs(long openMs) {
            this.openMs = openMs;
        }
    }
}
