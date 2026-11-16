package com.bigdata.modules.telemetry.config.properties;


/**
 * Doris 审计日志配置：只承载 {@code log.service} 的 {@code slowquery}。
 *
 * <p>走固定只读 SQL 查询 {@code __internal_schema.audit_log}（需 FE
 * {@code enable_audit_plugin=true}），没有任意 SQL 入口；条件、排序列与 LIMIT 都是模板的一部分。
 * 表不存在或审计未开启时本后端不可用，由目录声明的 {@code local-disk} 退路去解析
 * FE 的 {@code fe.audit.log}。</p>
 */
public class DorisAuditConfig {
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
