package com.bigdata.modules.telemetry.service.logdata.server;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.Requirement;
import com.bigdata.modules.telemetry.common.model.RequirementKind;
import com.bigdata.modules.telemetry.common.model.BackendType;
import com.bigdata.modules.telemetry.common.model.BackendWarning;
import com.bigdata.modules.telemetry.service.logdata.model.LogDataType;
import com.bigdata.modules.telemetry.service.logdata.model.LogEntry;
import com.bigdata.modules.telemetry.common.model.TimeRange;
import com.bigdata.modules.telemetry.common.model.BackendCapability;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.bigdata.modules.telemetry.service.logdata.spi.LogBackend;
import com.bigdata.modules.telemetry.service.logdata.model.LogQueryResult;
import com.bigdata.modules.telemetry.service.logdata.model.LogQuerySpec;
import com.bigdata.modules.telemetry.service.logdata.support.LogFilterEngine;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import com.bigdata.modules.telemetry.config.properties.DorisAuditConfig;
import com.bigdata.modules.telemetry.util.Strings;

/**
 * Doris 慢查询日志后端：只承载 {@code log.service} 的 {@code slowquery}。
 *
 * <p>数据来自 FE 审计表 {@code __internal_schema.audit_log}（需
 * {@code enable_audit_plugin=true}）。语句是固定模板，时间窗、阈值与行数都是参数，
 * 没有任何拼接 SQL 或任意 SQL 入口：</p>
 *
 * <pre>
 * SELECT time, query_time, client_ip, user, db, state, error_code, error_message,
 *        query_id, sql_hash, stmt
 *   FROM __internal_schema.audit_log
 *  WHERE time &gt;= ? AND time &lt;= ? AND query_time &gt;= ?
 *  ORDER BY time DESC
 *  LIMIT n
 * </pre>
 *
 * <p>{@code audit_log.time} 是 FE 进程时区的 DATETIME，绑定时按配置 {@code zone} 转成同一时区的
 * 字符串，避免 JDBC 驱动二次时区换算；{@code zone} 与 FE 不一致时整个时间窗会偏移。</p>
 *
 * <p>表不存在（审计未开启或版本不支持）时返回 {@code BACKEND_UNAVAILABLE} + requirements，
 * 让路由按目录声明回退到 {@code local-disk} 解析 {@code fe.audit.log}；不伪造空结果。</p>
 */
public final class DorisAuditLogBackend implements LogBackend {

    static final String SQL = "SELECT `time`, query_time, client_ip, `user`, `db`, state, "
            + "error_code, error_message, query_id, sql_hash, stmt "
            + "FROM __internal_schema.audit_log "
            + "WHERE `time` >= ? AND `time` <= ? AND query_time >= ? "
            + "ORDER BY `time` DESC LIMIT ";

    private static final int MAX_ROWS = 5000;
    private static final DateTimeFormatter BIND_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter[] READ_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")};

    private final String backendId;
    private final DorisAuditConfig config;

    public DorisAuditLogBackend(String backendId, DorisAuditConfig config) {
        this.backendId = backendId;
        this.config = config;
    }

    @Override
    public String backendId() {
        return backendId;
    }

    @Override
    public BackendType backendType() {
        return BackendType.DORIS_AUDIT;
    }

    @Override
    public BackendCapability evaluate(LogQuerySpec spec) {
        if (spec.dataType() != LogDataType.SERVICE) {
            return BackendCapability.unavailable(CapabilityCode.LOG_DATA_TYPE_MISMATCH,
                    "doris-audit 只承载 log.service 的 slowquery，任务日志请用 yarn", List.of());
        }
        if (!"slowquery".equals(spec.stream())) {
            return BackendCapability.unavailable(CapabilityCode.STREAM_NOT_SUPPORTED,
                    "doris-audit 只提供 slowquery，不支持 stream=" + spec.stream(), List.of());
        }
        if (Strings.isBlank(config.getJdbcUrl())) {
            return BackendCapability.unavailable(CapabilityCode.NOT_CONFIGURED,
                    "未配置 Doris FE JDBC 地址",
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "telemetry.backends.doris-audit[].jdbc-url",
                            "配置 FE MySQL 协议地址，如 jdbc:mysql://doris-fe:9030/__internal_schema")));
        }
        try {
            zone();
        } catch (RuntimeException e) {
            return BackendCapability.unavailable(CapabilityCode.NOT_CONFIGURED,
                    "zone 非法: " + config.getZone(), List.of(new Requirement(RequirementKind.CONFIG,
                    "telemetry.backends.doris-audit[].zone", "配置与 FE 一致的时区，如 UTC / Asia/Shanghai")));
        }
        return BackendCapability.available();
    }

    @Override
    public LogQueryResult fetch(LogQuerySpec spec) {
        LogFilterEngine filter;
        try {
            filter = LogFilterEngine.compile(spec.filter());
        } catch (IllegalArgumentException e) {
            throw new BackendException(CapabilityCode.INVALID_ARGUMENT, e.getMessage());
        }

        ZoneId zone = zone();
        List<BackendWarning> warnings = new ArrayList<>();
        Instant[] window = resolveWindow(spec.timeRange(), zone, warnings);
        int limit = limit(spec.limit());
        long maxBytes = maxBytes(spec.maxBytes());
        long threshold = config.getSlowQueryMs();

        List<Map<String, String>> rows = query(window[0], window[1], threshold, limit + 1, zone);
        boolean truncated = rows.size() > limit;
        if (truncated) {
            rows = rows.subList(0, limit);
            warnings.add(BackendWarning.of(CapabilityCode.RESULT_TRUNCATED.name(),
                    "结果达到条数上限 " + limit + "，仅返回最近的慢查询"));
        }

        List<LogEntry> entries = new ArrayList<>();
        long payloadBytes = 0;
        boolean filterDropped = false;
        for (Map<String, String> row : rows) {
            LogEntry entry = toEntry(row, spec, zone);
            if (!filter.matches(entry.message())) {
                filterDropped = true;
                continue;
            }
            long size = entry.message().getBytes(StandardCharsets.UTF_8).length + 1L;
            if (payloadBytes + size > maxBytes) {
                truncated = true;
                warnings.add(BackendWarning.of(CapabilityCode.RESULT_TRUNCATED.name(),
                        "结果达到字节上限 " + maxBytes + "，剩余慢查询未返回"));
                break;
            }
            entries.add(entry);
            payloadBytes += size;
        }
        if (filterDropped) {
            warnings.add(BackendWarning.of("FILTER_APPLIED_IN_MEMORY",
                    "关键词过滤作用在最近 " + rows.size() + " 条审计记录上，更早的匹配项不会补取"));
        }
        if (entries.isEmpty()) {
            warnings.add(BackendWarning.of("EMPTY_AUDIT_RESULT",
                    "审计表可查，但时间窗内没有 query_time >= " + threshold + "ms 的记录"
                            + (filterDropped ? "（或全部被关键词过滤）" : "")));
        }

        LogQueryResult.ScanStats scan = new LogQueryResult.ScanStats(
                0, payloadBytes, rows.size(), entries.size());
        return new LogQueryResult(entries, null, truncated, scan, warnings);
    }

    // ------------------------------------------------------------------ 查询

    private List<Map<String, String>> query(Instant start, Instant end, long threshold, int rows,
                                            ZoneId zone) {
        String sql = SQL + rows;
        Properties props = new Properties();
        props.setProperty("user", config.getUsername() == null ? "" : config.getUsername());
        props.setProperty("password", config.getPassword() == null ? "" : config.getPassword());
        props.setProperty("connectTimeout", String.valueOf(config.getLimits().getTimeoutMs()));
        props.setProperty("socketTimeout", String.valueOf(config.getLimits().getTimeoutMs()));
        try (Connection connection = DriverManager.getConnection(config.getJdbcUrl(), props);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(Math.max(config.getLimits().getTimeoutMs() / 1000, 1));
            statement.setString(1, BIND_FORMAT.format(LocalDateTime.ofInstant(start, zone)));
            statement.setString(2, BIND_FORMAT.format(LocalDateTime.ofInstant(end, zone)));
            statement.setLong(3, threshold);
            try (ResultSet resultSet = statement.executeQuery()) {
                return read(resultSet);
            }
        } catch (SQLException e) {
            throw map(e);
        }
    }

    private List<Map<String, String>> read(ResultSet resultSet) throws SQLException {
        List<Map<String, String>> rows = new ArrayList<>();
        ResultSetMetaData meta = resultSet.getMetaData();
        int columns = meta.getColumnCount();
        while (resultSet.next()) {
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 1; i <= columns; i++) {
                Object value = resultSet.getObject(i);
                row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT),
                        value == null ? null : String.valueOf(value));
            }
            rows.add(row);
        }
        return rows;
    }

    LogEntry toEntry(Map<String, String> row, LogQuerySpec spec, ZoneId zone) {
        String stmt = row.get("stmt");
        String message = stmt == null || stmt.isBlank() ? synthesize(row) : stmt;
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("queryTimeMs", row.get("query_time"));
        fields.put("user", row.get("user"));
        fields.put("db", row.get("db"));
        fields.put("state", row.get("state"));
        fields.put("errorCode", row.get("error_code"));
        fields.put("clientIp", row.get("client_ip"));
        fields.put("sqlHash", row.get("sql_hash"));
        fields.put("queryId", row.get("query_id"));
        fields.values().removeIf(value -> value == null);
        return new LogEntry(parseInstant(row.get("time"), zone), spec.stream(),
                spec.target().hostId(), message, fields);
    }

    private static String synthesize(Map<String, String> row) {
        return "query_id=" + row.getOrDefault("query_id", "")
                + " state=" + row.getOrDefault("state", "")
                + " query_time=" + row.getOrDefault("query_time", "") + "ms";
    }

    static Instant parseInstant(String value, ZoneId zone) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replace('T', ' ');
        if (normalized.endsWith("Z")) {
            return Instant.parse(value);
        }
        // getObject 对 DATETIME 可能返回带纳秒的字符串，统一截到毫秒再解析。
        for (DateTimeFormatter format : READ_FORMATS) {
            try {
                return LocalDateTime.parse(normalized, format).atZone(zone).toInstant();
            } catch (DateTimeParseException ignored) {
                // 尝试下一种精度
            }
        }
        try {
            return LocalDateTime.parse(normalized).atZone(zone).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 时间窗缺省时用配置的回看窗口兜底，并在响应里显式告警，不做静默全表扫描。 */
    private Instant[] resolveWindow(TimeRange range, ZoneId zone, List<BackendWarning> warnings) {
        Instant now = Instant.now();
        Instant start = range == null ? null : range.start();
        Instant end = range == null ? null : range.end();
        boolean defaulted = false;
        if (start == null) {
            Instant base = end == null ? now : end;
            start = base.minus(Math.max(config.getDefaultLookbackHours(), 1), ChronoUnit.HOURS);
            defaulted = true;
        }
        if (end == null) {
            end = now;
            defaulted = true;
        }
        if (start.isAfter(end)) {
            throw new BackendException(CapabilityCode.INVALID_ARGUMENT,
                    "时间范围 start 不能晚于 end");
        }
        if (defaulted) {
            warnings.add(BackendWarning.of("DEFAULT_TIME_WINDOW",
                    "未提供完整时间范围，按最近 " + Math.max(config.getDefaultLookbackHours(), 1)
                            + " 小时查询（FE 时区 " + zone + "）"));
        }
        return new Instant[]{start, end};
    }

    private int limit(int requested) {
        int configured = config.getLimits().getMaxRows() <= 0 ? 500 : config.getLimits().getMaxRows();
        int value = requested <= 0 ? configured : Math.min(requested, configured);
        return Math.max(1, Math.min(value, MAX_ROWS));
    }

    private long maxBytes(long requested) {
        long configured = config.getLimits().getMaxBytes() <= 0 ? 524288L : config.getLimits().getMaxBytes();
        return requested <= 0 ? configured : Math.min(requested, configured);
    }

    private ZoneId zone() {
        return Strings.isBlank(config.getZone()) ? ZoneId.systemDefault() : ZoneId.of(config.getZone());
    }

    BackendException map(SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage();
        String lower = message.toLowerCase(Locale.ROOT);
        String sanitized = sanitize(message);
        if (lower.contains("unknown table") || lower.contains("does not exist")
                || lower.contains("unknown database") || "42S02".equals(state)) {
            return new BackendException(CapabilityCode.BACKEND_UNAVAILABLE,
                    "Doris 审计表不可用（审计插件未开启或版本不支持）: " + sanitized,
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "telemetry.backends.doris-audit[].jdbc-url",
                            "在 FE 开启 enable_audit_plugin=true 并确认 __internal_schema.audit_log 存在；"
                                    + "否则改用 local-disk 解析 fe.audit.log")));
        }
        if ("28000".equals(state) || "42000".equals(state) || lower.contains("access denied")) {
            return new BackendException(CapabilityCode.UPSTREAM_UNAUTHORIZED,
                    "Doris 认证失败: " + sanitized,
                    List.of(new Requirement(RequirementKind.CREDENTIAL,
                            "telemetry.backends.doris-audit[].password-ref",
                            "检查可读取 __internal_schema 的账号密码")));
        }
        if ("08S01".equals(state) || lower.contains("timeout")
                || lower.contains("communications link failure")) {
            return new BackendException(CapabilityCode.UPSTREAM_TIMEOUT,
                    "Doris FE 连接超时: " + sanitized);
        }
        return new BackendException(CapabilityCode.IO_ERROR, "Doris 审计查询失败: " + sanitized);
    }

    private static String sanitize(String message) {
        String cleaned = message.replaceAll("(?i)(password|pwd)\\s*=\\s*[^;,\\s]*", "$1=***");
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }

}
