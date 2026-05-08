package com.bigdata.modules.devops.service;

import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.devops.mapper.DatasourceConfigMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DatasourceConnectionPoolManager implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DatasourceConnectionPoolManager.class);

    private static final String KYUUBI_DRIVER = "org.apache.kyuubi.jdbc.KyuubiHiveDriver";
    private static final String MYSQL_DRIVER = "com.mysql.cj.jdbc.Driver";
    private static final String CLICKHOUSE_DRIVER = "com.clickhouse.jdbc.ClickHouseDriver";

    private final ConcurrentHashMap<Long, HikariDataSource> poolMap = new ConcurrentHashMap<>();

    @Autowired
    private DatasourceConfigMapper datasourceConfigMapper;

    static {
        loadDriverSilently(KYUUBI_DRIVER);
        loadDriverSilently(MYSQL_DRIVER);
        loadDriverSilently(CLICKHOUSE_DRIVER);
    }

    private static void loadDriverSilently(String driverClass) {
        try {
            Class.forName(driverClass);
        } catch (ClassNotFoundException ignored) {
        }
    }

    public Connection getConnection(Long datasourceId) throws SQLException {
        HikariDataSource pool = getOrCreatePool(datasourceId);
        return pool.getConnection();
    }

    public Connection getConnection(Long datasourceId, String databaseName) throws SQLException {
        Connection conn = getConnection(datasourceId);
        if (databaseName != null && !databaseName.isEmpty()) {
            try {
                DatasourceConfig config = getDatasourceConfig(datasourceId);
                DatasourceType type = config.getDatasourceType();
                if (type == DatasourceType.MYSQL || type == DatasourceType.DORIS || type == DatasourceType.STARROCKS) {
                    conn.setCatalog(databaseName);
                } else {
                    conn.setSchema(databaseName);
                }
            } catch (SQLException e) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                }
                throw e;
            }
        }
        return conn;
    }

    public boolean testConnection(Long datasourceId) {
        try (Connection conn = getConnection(datasourceId)) {
            return conn != null && !conn.isClosed() && conn.isValid(30);
        } catch (Exception e) {
            log.warn("测试连接失败: datasourceId={}, error={}", datasourceId, e.getMessage());
            return false;
        }
    }

    public HikariDataSource createTempPool(DatasourceConfig config) {
        HikariConfig hc = buildHikariConfig(config);
        hc.setPoolName("temp-ds-" + System.currentTimeMillis());
        HikariDataSource pool = new HikariDataSource(hc);
        log.info("创建临时连接池: type={}, url={}", config.getDatasourceType(), config.getUrl());
        return pool;
    }

    public boolean testPoolConnection(HikariDataSource pool) {
        try (Connection conn = pool.getConnection()) {
            return conn != null && !conn.isClosed() && conn.isValid(30);
        } catch (Exception e) {
            log.warn("测试临时连接池失败: error={}", e.getMessage());
            return false;
        }
    }

    public HikariDataSource getOrCreatePool(Long datasourceId) {
        return poolMap.computeIfAbsent(datasourceId, this::createPool);
    }

    public void refreshPool(Long datasourceId) {
        HikariDataSource oldPool = poolMap.remove(datasourceId);
        if (oldPool != null && !oldPool.isClosed()) {
            oldPool.close();
            log.info("已关闭旧连接池: datasourceId={}", datasourceId);
        }
        getOrCreatePool(datasourceId);
        log.info("已刷新连接池: datasourceId={}", datasourceId);
    }

    public void closePool(Long datasourceId) {
        HikariDataSource pool = poolMap.remove(datasourceId);
        if (pool != null && !pool.isClosed()) {
            pool.close();
            log.info("已关闭连接池: datasourceId={}", datasourceId);
        }
    }

    public void closeAllPools() {
        List<Long> ids = new ArrayList<>(poolMap.keySet());
        for (Long id : ids) {
            closePool(id);
        }
        log.info("已关闭所有连接池, 共{}个", ids.size());
    }

    public PoolStatus getPoolStatus(Long datasourceId) {
        HikariDataSource pool = poolMap.get(datasourceId);
        if (pool == null) {
            return new PoolStatus(datasourceId, false, 0, 0, 0, 0, 0);
        }
        return new PoolStatus(
                datasourceId,
                !pool.isClosed(),
                pool.getMaximumPoolSize(),
                pool.getMinimumIdle(),
                pool.getHikariPoolMXBean().getActiveConnections(),
                pool.getHikariPoolMXBean().getIdleConnections(),
                pool.getHikariPoolMXBean().getTotalConnections()
        );
    }

    public List<PoolStatus> getAllPoolStatus() {
        List<PoolStatus> statusList = new ArrayList<>();
        for (Long id : poolMap.keySet()) {
            statusList.add(getPoolStatus(id));
        }
        return statusList;
    }

    public boolean hasPool(Long datasourceId) {
        return poolMap.containsKey(datasourceId);
    }

    public int getPoolCount() {
        return poolMap.size();
    }

    private HikariDataSource createPool(Long datasourceId) {
        DatasourceConfig config = getDatasourceConfig(datasourceId);
        if (config == null) {
            throw new RuntimeException("数据源不存在: id=" + datasourceId);
        }

        HikariConfig hc = buildHikariConfig(config);
        HikariDataSource pool = new HikariDataSource(hc);
        log.info("创建连接池: datasourceId={}, type={}, url={}", datasourceId, config.getDatasourceType(), config.getUrl());
        return pool;
    }

    private HikariConfig buildHikariConfig(DatasourceConfig config) {
        HikariConfig hc = new HikariConfig();
        DatasourceType type = config.getDatasourceType();

        hc.setPoolName("ds-" + config.getId());
        hc.setJdbcUrl(normalizeUrl(config.getUrl(), type));
        hc.setUsername(config.getUsername());
        hc.setPassword(config.getPassword());

        configureDriver(hc, type);
        configurePoolSize(hc, type);
        configureTimeout(hc, type);
        configureTestQuery(hc, type);

        if (config.getExtraParams() != null && !config.getExtraParams().isEmpty()) {
            hc.setConnectionInitSql(config.getExtraParams());
        }

        return hc;
    }

    private String normalizeUrl(String url, DatasourceType type) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        switch (type) {
            case KYUUBI:
            case HIVE:
                if (!url.startsWith("jdbc:kyuubi:") && !url.startsWith("jdbc:hive2:")) {
                    url = "jdbc:hive2://" + url;
                }
                if (!url.contains("useUnicode=")) {
                    String separator = url.contains("?") ? "&" : "?";
                    url = url + separator + "useUnicode=true&characterEncoding=UTF-8";
                }
                return url;
            case CLICKHOUSE:
                if (!url.contains("jdbc:")) {
                    return "jdbc:clickhouse://" + url;
                }
                return url;
            default:
                if (!url.contains("useUnicode=") && (type == DatasourceType.MYSQL || type == DatasourceType.DORIS || type == DatasourceType.STARROCKS)) {
                    String separator = url.contains("?") ? "&" : "?";
                    url = url + separator + "useUnicode=true&characterEncoding=UTF-8";
                }
                return url;
        }
    }

    private void configureDriver(HikariConfig hc, DatasourceType type) {
        switch (type) {
            case KYUUBI:
            case HIVE:
                hc.setDriverClassName(KYUUBI_DRIVER);
                break;
            case CLICKHOUSE:
                hc.setDriverClassName(CLICKHOUSE_DRIVER);
                break;
            default:
                break;
        }
    }

    private void configurePoolSize(HikariConfig hc, DatasourceType type) {
        switch (type) {
            case KYUUBI:
            case HIVE:
                hc.setMaximumPoolSize(3);
                hc.setMinimumIdle(1);
                break;
            default:
                hc.setMaximumPoolSize(5);
                hc.setMinimumIdle(1);
                break;
        }
    }

    private void configureTimeout(HikariConfig hc, DatasourceType type) {
        switch (type) {
            case KYUUBI:
            case HIVE:
                hc.setConnectionTimeout(15000);
                hc.setIdleTimeout(600000);
                hc.setMaxLifetime(1800000);
                hc.setValidationTimeout(10000);
                hc.setInitializationFailTimeout(-1);
                break;
            default:
                hc.setConnectionTimeout(30000);
                hc.setIdleTimeout(600000);
                hc.setMaxLifetime(1800000);
                hc.setValidationTimeout(5000);
                break;
        }
    }

    private void configureTestQuery(HikariConfig hc, DatasourceType type) {
        switch (type) {
            case KYUUBI:
            case HIVE:
                hc.setConnectionTestQuery("SELECT 1");
                break;
            case MYSQL:
            case DORIS:
            case STARROCKS:
                hc.setConnectionTestQuery("SELECT 1");
                break;
            case CLICKHOUSE:
                hc.setConnectionTestQuery("SELECT 1");
                break;
            default:
                break;
        }
    }

    private DatasourceConfig getDatasourceConfig(Long datasourceId) {
        DatasourceConfig config = datasourceConfigMapper.selectById(datasourceId);
        if (config == null) {
            throw new RuntimeException("数据源不存在: id=" + datasourceId);
        }
        return config;
    }

    @Override
    public void destroy() {
        closeAllPools();
    }

    public static class PoolStatus {
        private Long datasourceId;
        private boolean active;
        private int maxPoolSize;
        private int minIdle;
        private int activeConnections;
        private int idleConnections;
        private int totalConnections;

        public PoolStatus(Long datasourceId, boolean active, int maxPoolSize, int minIdle,
                          int activeConnections, int idleConnections, int totalConnections) {
            this.datasourceId = datasourceId;
            this.active = active;
            this.maxPoolSize = maxPoolSize;
            this.minIdle = minIdle;
            this.activeConnections = activeConnections;
            this.idleConnections = idleConnections;
            this.totalConnections = totalConnections;
        }

        public Long getDatasourceId() { return datasourceId; }
        public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }
        public int getMinIdle() { return minIdle; }
        public void setMinIdle(int minIdle) { this.minIdle = minIdle; }
        public int getActiveConnections() { return activeConnections; }
        public void setActiveConnections(int activeConnections) { this.activeConnections = activeConnections; }
        public int getIdleConnections() { return idleConnections; }
        public void setIdleConnections(int idleConnections) { this.idleConnections = idleConnections; }
        public int getTotalConnections() { return totalConnections; }
        public void setTotalConnections(int totalConnections) { this.totalConnections = totalConnections; }
    }
}
