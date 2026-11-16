package com.bigdata.modules.telemetry.service.metadata.support;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.Requirement;
import com.bigdata.modules.telemetry.common.model.RequirementKind;
import com.bigdata.modules.telemetry.common.exception.BackendException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import com.bigdata.modules.telemetry.service.metadata.spi.MetadataSqlClient;

/**
 * Doris / StarRocks 的只读 JDBC 客户端。
 *
 * <p>硬约束：</p>
 * <ul>
 *   <li>独立连接池，不复用应用 Spring {@code DataSource}，也不读主库配置；</li>
 *   <li>池上限 2，取不到连接直接快速失败为 {@code UNAVAILABLE}；</li>
 *   <li>连接带 {@code connectTimeout} / {@code socketTimeout}，查询带 {@code queryTimeout}；</li>
 *   <li>只允许 {@link ReadOnlyStatement} 白名单语句，没有任何拼接 SQL 的入口，
 *       从类型上杜绝 DDL / DML；</li>
 *   <li>账号密码由调用方从 {@code SecretResolver} 解析后传入，本类不打印、不回传。</li>
 * </ul>
 */
public class JdbcMetadataClient implements MetadataSqlClient {

    /** 允许执行的只读语句白名单。 */
    public enum ReadOnlyStatement {
        SHOW_FRONTENDS("SHOW FRONTENDS"),
        SHOW_BACKENDS("SHOW BACKENDS"),
        ADMIN_SHOW_FRONTEND_CONFIG("ADMIN SHOW FRONTEND CONFIG"),
        SHOW_FRONTEND_CONFIG("SHOW FRONTEND CONFIG");

        private final String sql;

        ReadOnlyStatement(String sql) {
            this.sql = sql;
        }

        public String sql() {
            return sql;
        }
    }

    private static final int MAX_POOL_SIZE = 2;

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final int timeoutMs;

    private final Semaphore permits = new Semaphore(MAX_POOL_SIZE, true);
    private final Deque<Connection> idle = new ArrayDeque<>();

    public JdbcMetadataClient(String jdbcUrl, String username, String password, int timeoutMs) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.timeoutMs = Math.max(timeoutMs, 1000);
    }

    /** 执行白名单语句，返回列名 → 字符串值的行集。 */
    @Override
    public List<Map<String, String>> query(ReadOnlyStatement statement) {
        Connection connection = lease();
        boolean broken = false;
        try (Statement jdbc = connection.createStatement()) {
            jdbc.setQueryTimeout(Math.max(timeoutMs / 1000, 1));
            // ADMIN 开头的语句会被 Connector/J 判定为“不产生结果集”，
            // 因此统一用 execute() + getResultSet()，而不是 executeQuery()。
            if (!jdbc.execute(statement.sql())) {
                broken = true;
                throw new BackendException(CapabilityCode.PARSE_ERROR,
                        "语句未返回结果集: " + statement.sql());
            }
            try (ResultSet resultSet = jdbc.getResultSet()) {
                return read(resultSet);
            }
        } catch (SQLException e) {
            broken = true;
            throw map(e);
        } finally {
            release(connection, broken);
        }
    }

    @Override
    public void close() {
        synchronized (idle) {
            while (!idle.isEmpty()) {
                closeQuietly(idle.pop());
            }
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
                row.put(meta.getColumnLabel(i), value == null ? null : String.valueOf(value));
            }
            rows.add(row);
        }
        return rows;
    }

    private Connection lease() {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackendException(CapabilityCode.BACKEND_UNAVAILABLE, "等待 JDBC 连接被中断");
        }
        if (!acquired) {
            throw new BackendException(CapabilityCode.BACKEND_UNAVAILABLE,
                    "JDBC 连接池已满（上限 " + MAX_POOL_SIZE + "）");
        }
        Connection cached;
        synchronized (idle) {
            cached = idle.poll();
        }
        if (cached != null) {
            try {
                if (cached.isValid(1)) {
                    return cached;
                }
            } catch (SQLException ignored) {
                // 失效连接直接丢弃并重建
            }
            closeQuietly(cached);
        }
        try {
            return open();
        } catch (RuntimeException e) {
            permits.release();
            throw e;
        }
    }

    private Connection open() {
        Properties props = new Properties();
        props.setProperty("user", username == null ? "" : username);
        props.setProperty("password", password == null ? "" : password);
        // MySQL 协议：毫秒；连接与读各一个超时，避免卡死线程
        props.setProperty("connectTimeout", String.valueOf(timeoutMs));
        props.setProperty("socketTimeout", String.valueOf(timeoutMs));
        try {
            return DriverManager.getConnection(jdbcUrl, props);
        } catch (SQLException e) {
            throw map(e);
        }
    }

    private void release(Connection connection, boolean broken) {
        if (connection == null) {
            permits.release();
            return;
        }
        if (broken) {
            closeQuietly(connection);
        } else {
            synchronized (idle) {
                idle.push(connection);
            }
        }
        permits.release();
    }

    private void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 关闭失败不影响结果
        }
    }

    private BackendException map(SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage();
        String lower = message.toLowerCase(Locale.ROOT);
        if ("28000".equals(state) || "42000".equals(state) || lower.contains("access denied")) {
            return new BackendException(CapabilityCode.UPSTREAM_UNAUTHORIZED,
                    "认证失败: " + sanitize(message),
                    List.of(new Requirement(RequirementKind.CREDENTIAL,
                            "telemetry.metadata.providers.<id>.password-ref",
                            "检查数据库账号密码")));
        }
        if ("08S01".equals(state) || lower.contains("timeout")
                || lower.contains("communications link failure")) {
            return new BackendException(CapabilityCode.UPSTREAM_TIMEOUT,
                    "连接超时: " + sanitize(message));
        }
        return new BackendException(CapabilityCode.IO_ERROR, "JDBC 调用失败: " + sanitize(message));
    }

    /** 驱动消息可能包含连接串，截断并去掉可能的内嵌密码形式。 */
    private String sanitize(String message) {
        String cleaned = message.replaceAll("(?i)(password|pwd)\\s*=\\s*[^;,\\s]*", "$1=***");
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }
}
