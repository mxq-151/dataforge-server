package com.bigdata.modules.telemetry.config.properties;


public class FlinkRestConfig {
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
