package com.bigdata.modules.devops.service;

import com.bigdata.common.utils.AesUtils;
import com.bigdata.common.utils.SecurityUtils;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.ConnectionType;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.devops.mapper.DatasourceConfigMapper;
import com.bigdata.modules.system.entity.SysTenant;
import com.bigdata.modules.system.service.SysTenantService;
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
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DatasourceConnectionPoolManager implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DatasourceConnectionPoolManager.class);

    private static final String KYUUBI_DRIVER = "org.apache.kyuubi.jdbc.KyuubiHiveDriver";
    private static final String MYSQL_DRIVER = "com.mysql.cj.jdbc.Driver";
    private static final String POSTGRESQL_DRIVER = "org.postgresql.Driver";
    private static final String CLICKHOUSE_DRIVER = "com.clickhouse.jdbc.ClickHouseDriver";

    private final ConcurrentHashMap<Long, HikariDataSource> poolMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, HikariDataSource> poolMapWithDb = new ConcurrentHashMap<>();

    @Autowired
    private DatasourceConfigMapper datasourceConfigMapper;

    @Autowired
    private SysTenantService sysTenantService;

    @Autowired
    private AesUtils aesUtils;

    static {
        loadDriverSilently(KYUUBI_DRIVER);
        loadDriverSilently(MYSQL_DRIVER);
        loadDriverSilently(POSTGRESQL_DRIVER);
        loadDriverSilently(CLICKHOUSE_DRIVER);
    }

    private static void loadDriverSilently(String driverClass) {
        try {
            Class.forName(driverClass);
        } catch (ClassNotFoundException ignored) {
        }
    }

    public Connection getConnection(Long datasourceId) throws SQLException {
        DatasourceConfig config = getDatasourceConfig(datasourceId);
        ensureJdbcType(config);
        HikariDataSource pool = getOrCreatePool(datasourceId);
        return pool.getConnection();
    }

    public Connection getConnection(Long datasourceId, String databaseName) throws SQLException {
        if (databaseName == null || databaseName.isEmpty()) {
            return getConnection(datasourceId);
        }

        DatasourceConfig config = getDatasourceConfig(datasourceId);
        ensureJdbcType(config);

        Long userId = SecurityUtils.getCurrentUserId();
        String poolKey = buildPoolKey(userId, datasourceId, databaseName);
        HikariDataSource pool = poolMapWithDb.computeIfAbsent(poolKey, key -> {
            try {
                DatasourceConfig rawConfig = getDatasourceConfig(datasourceId);
                DatasourceConfig configWithDb = new DatasourceConfig();
                org.springframework.beans.BeanUtils.copyProperties(rawConfig, configWithDb);
                configWithDb.setUrl(buildUrlWithDatabase(rawConfig.getUrl(), rawConfig.getDatasourceType(), databaseName));
                // poolName 必须在 HikariDataSource 创建前通过 HikariConfig 设置，
                // 否则会抛 IllegalStateException: The configuration of the pool is sealed once started.
                String poolName = "ds-" + userId + "-" + datasourceId + "-" + databaseName;
                return createPoolFromConfig(configWithDb, poolName);
            } catch (Exception e) {
                log.error("创建带数据库名的连接池失败: poolKey={}", poolKey, e);
                throw new RuntimeException("创建连接池失败: " + e.getMessage(), e);
            }
        });

        return pool.getConnection();
    }

    private String buildPoolKey(Long userId, Long datasourceId, String databaseName) {
        return userId + ":" + datasourceId + ":" + databaseName;
    }

    private String buildUrlWithDatabase(String originalUrl, DatasourceType type, String databaseName) {
        if (originalUrl == null || originalUrl.isEmpty()) {
            return originalUrl;
        }

        String normalizedUrl = normalizeUrl(originalUrl, type);

        switch (type) {
            case MYSQL:
            case DORIS:
            case STARROCKS:
                // 查找 "://" 后的第一个 "/"，这才是 host:port 与数据库之间的分隔符，
                // 避免误匹配 query 参数中的 "/"（如 serverTimezone=Asia/Shanghai）
                int schemeEnd = normalizedUrl.indexOf("://");
                if (schemeEnd > 0) {
                    int pathStart = normalizedUrl.indexOf('/', schemeEnd + 3);
                    if (pathStart > 0) {
                        int queryStart = normalizedUrl.indexOf('?', pathStart);
                        if (queryStart > pathStart) {
                            return normalizedUrl.substring(0, pathStart + 1) + databaseName + normalizedUrl.substring(queryStart);
                        } else {
                            return normalizedUrl.substring(0, pathStart + 1) + databaseName;
                        }
                    }
                }
                break;
            case POSTGRESQL:
                // PostgreSQL 的 databaseName 语义为 schema（见 JdbcTableStatsCollector 使用 nspname），
                // 不能像 MySQL 那样替换 URL 中的 database：ods_xxx 是 schema 而非库，
                // 替换后会连接到不存在的库，HikariCP 持续重试直至 connectionTimeout(30s) 后才报
                // "Connection is not available"，导致建表等 DDL 卡 30 秒。
                // 因此保持原 database 不变，仅用 currentSchema 指定 schema。
                return withCurrentSchema(normalizedUrl, databaseName);
            case CLICKHOUSE:
                if (normalizedUrl.contains("jdbc:clickhouse://")) {
                    String hostPart = normalizedUrl.replace("jdbc:clickhouse://", "");
                    if (hostPart.contains("/")) {
                        int firstSlash = hostPart.indexOf('/');
                        return "jdbc:clickhouse://" + hostPart.substring(0, firstSlash + 1) + databaseName;
                    } else {
                        return normalizedUrl + "/" + databaseName;
                    }
                }
                break;
            default:
                break;
        }

        return normalizedUrl;
    }

    /**
     * 保持 PostgreSQL JDBC URL 中的 database 不变，把 currentSchema 设置为指定 schema。
     * <p>PostgreSQL 中一个 database 下有多个 schema，dataforge 的 databaseName 表示 schema，
     * 因此只能在 URL 上覆盖 currentSchema，而不能替换 database。</p>
     *
     * @return 覆盖 currentSchema 后的 URL；schema 为空时原样返回
     */
    private static String withCurrentSchema(String url, String schema) {
        if (url == null || url.isEmpty() || schema == null || schema.isEmpty()) {
            return url;
        }
        int queryStart = url.indexOf('?');
        String base = queryStart >= 0 ? url.substring(0, queryStart) : url;
        String query = queryStart >= 0 ? url.substring(queryStart + 1) : "";

        StringBuilder params = new StringBuilder();
        boolean replaced = false;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            if (pair.regionMatches(true, 0, "currentSchema=", 0, "currentSchema=".length())) {
                params.append("currentSchema=").append(schema);
                replaced = true;
            } else {
                params.append(pair);
            }
            params.append('&');
        }
        if (!replaced) {
            params.append("currentSchema=").append(schema).append('&');
        }
        params.setLength(params.length() - 1);
        return base + "?" + params;
    }

    private HikariDataSource createPoolFromConfig(DatasourceConfig config, String poolName) {
        HikariConfig hc = buildHikariConfig(config, true);
        // 若调用方指定了 poolName，则在 HikariConfig 阶段覆盖（HikariDataSource 创建后不可再修改）
        if (poolName != null && !poolName.isEmpty()) {
            hc.setPoolName(poolName);
        }
        HikariDataSource pool = new HikariDataSource(hc);
        log.info("创建连接池(带数据库): type={}, url={}", config.getDatasourceType(), config.getUrl());
        return pool;
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
        // 测试连接时密码为前端传入的明文，不需要解密
        HikariConfig hc = buildHikariConfig(config, false);
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
        DatasourceConfig config = getDatasourceConfig(datasourceId);
        if (config.getConnectionType() != null && !config.getConnectionType().isJdbcBased()) {
            log.debug("数据源[{}]连接方式为{}，跳过JDBC连接池创建", datasourceId, config.getConnectionType());
            return null;
        }
        return poolMap.computeIfAbsent(datasourceId, this::createPool);
    }

    public void refreshPool(Long datasourceId) {
        synchronized (this) {
            DatasourceConfig config = getDatasourceConfig(datasourceId);
            if (config.getConnectionType() != null && !config.getConnectionType().isJdbcBased()) {
                log.debug("数据源[{}]连接方式为{}，跳过JDBC连接池刷新", datasourceId, config.getConnectionType());
                return;
            }

            HikariDataSource oldPool = poolMap.remove(datasourceId);
            if (oldPool != null && !oldPool.isClosed()) {
                oldPool.close();
                log.info("已关闭旧连接池: datasourceId={}", datasourceId);
            }

            closePoolsWithDbForDatasource(datasourceId);

            getOrCreatePool(datasourceId);
            log.info("已刷新连接池: datasourceId={}", datasourceId);
        }
    }

    private void closePoolsWithDbForDatasource(Long datasourceId) {
        String dsIdStr = ":" + datasourceId + ":";
        List<String> keysToRemove = new ArrayList<>();
        for (String key : poolMapWithDb.keySet()) {
            if (key.contains(dsIdStr)) {
                keysToRemove.add(key);
            }
        }
        for (String key : keysToRemove) {
            HikariDataSource pool = poolMapWithDb.remove(key);
            if (pool != null && !pool.isClosed()) {
                pool.close();
                log.info("已关闭带数据库名的连接池: poolKey={}", key);
            }
        }
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
        List<String> dbKeys = new ArrayList<>(poolMapWithDb.keySet());
        for (String key : dbKeys) {
            HikariDataSource pool = poolMapWithDb.remove(key);
            if (pool != null && !pool.isClosed()) {
                pool.close();
            }
        }
        log.info("已关闭所有连接池, 主池{}个, 带数据库池{}个", ids.size(), dbKeys.size());
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

        HikariConfig hc = buildHikariConfig(config, true);
        HikariDataSource pool = new HikariDataSource(hc);
        log.info("创建连接池: datasourceId={}, type={}, url={}", datasourceId, config.getDatasourceType(), config.getUrl());
        return pool;
    }

    private HikariConfig buildHikariConfig(DatasourceConfig config, boolean decryptConfigPassword) {
        HikariConfig hc = new HikariConfig();
        DatasourceType type = config.getDatasourceType();

        Long currentUserId = SecurityUtils.getCurrentUserId();
        boolean isAdmin = SecurityUtils.isAdmin();

        String tenantCode = "";
        if (currentUserId != null) {
            SysTenant tenant = sysTenantService.getTenantByUserId(currentUserId);
            if (tenant != null) {
                tenantCode = tenant.getTenantCode();
            }
        }

        if (isAdmin) {
            hc.setPoolName("ds-" + config.getId() + "-" + SecurityUtils.getCurrentUsername());
            hc.setUsername(config.getUsername());
            // 数据库取出的密码已加密需要解密；测试连接传入的是明文不需要解密
            String password = decryptConfigPassword ? config.getPlainPassword(aesUtils) : config.getPassword();
            hc.setPassword(password);
        } else {
            // 租户密码始终从数据库取（已加密），需要解密
            String tenantPassword = "";
            if (currentUserId != null) {
                SysTenant tenant = sysTenantService.getTenantByUserId(currentUserId);
                if (tenant != null && tenant.getPassword() != null) {
                    tenantPassword = aesUtils.decryptStored(tenant.getPassword());
                }
            }
            log.info("create connect for {}",tenantCode);
            hc.setPoolName("ds-" + config.getId() + "-" + tenantCode);
            hc.setUsername(tenantCode);
            hc.setPassword(tenantPassword);
        }

        hc.setJdbcUrl(normalizeUrl(config.getUrl(), type));

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
            case POSTGRESQL:
                hc.setDriverClassName(POSTGRESQL_DRIVER);
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
                hc.setMaximumPoolSize(10);
                hc.setMinimumIdle(2);
                break;
            default:
                hc.setMaximumPoolSize(20);
                hc.setMinimumIdle(3);
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
                hc.setKeepaliveTime(300000);
                hc.setInitializationFailTimeout(-1);
                break;
            default:
                hc.setConnectionTimeout(30000);
                hc.setIdleTimeout(600000);
                hc.setMaxLifetime(1800000);
                hc.setValidationTimeout(5000);
                hc.setKeepaliveTime(300000);
                hc.setInitializationFailTimeout(-1);
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
            case POSTGRESQL:
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

    /**
     * 确保数据源是JDBC连接方式，否则抛出异常
     */
    private void ensureJdbcType(DatasourceConfig config) {
        ConnectionType connType = config.getConnectionType();
        if (connType != null && !connType.isJdbcBased()) {
            throw new UnsupportedOperationException(
                    "数据源[" + config.getDatasourceName() + "]的连接方式为" + connType.getDescription()
                            + "，不支持JDBC连接池操作。请使用对应的" + connType.getDescription() + "客户端进行连接。");
        }
    }

    /**
     * 判断指定数据源是否支持JDBC连接池
     */
    public boolean isJdbcSupported(Long datasourceId) {
        DatasourceConfig config = getDatasourceConfig(datasourceId);
        ConnectionType connType = config.getConnectionType();
        return connType == null || connType.isJdbcBased();
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
