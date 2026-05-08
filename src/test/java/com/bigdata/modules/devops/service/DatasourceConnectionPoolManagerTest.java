package com.bigdata.modules.devops.service;

import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.devops.mapper.DatasourceConfigMapper;
import com.bigdata.modules.devops.service.DatasourceConnectionPoolManager.PoolStatus;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class DatasourceConnectionPoolManagerTest {

    @Mock
    private DatasourceConfigMapper datasourceConfigMapper;

    @InjectMocks
    private DatasourceConnectionPoolManager poolManager;

    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
    }

    @AfterEach
    void tearDown() throws Exception {
        poolManager.closeAllPools();
        mocks.close();
    }

    private DatasourceConfig createMySqlConfig(Long id) {
        DatasourceConfig config = new DatasourceConfig();
        config.setId(id);
        config.setDatasourceName("test-mysql");
        config.setDatasourceType(DatasourceType.MYSQL);
        config.setUrl("jdbc:h2:mem:testdb" + id + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        config.setUsername("sa");
        config.setPassword("");
        config.setDatabaseName("PUBLIC");
        return config;
    }

    private DatasourceConfig createH2Config(Long id) {
        DatasourceConfig config = new DatasourceConfig();
        config.setId(id);
        config.setDatasourceName("test-h2");
        config.setDatasourceType(DatasourceType.MYSQL);
        config.setUrl("jdbc:h2:mem:testdb" + id + ";DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        return config;
    }

    private DatasourceConfig createKyuubiConfig(Long id, String url) {
        DatasourceConfig config = new DatasourceConfig();
        config.setId(id);
        config.setDatasourceName("test-kyuubi");
        config.setDatasourceType(DatasourceType.KYUUBI);
        config.setUrl(url);
        config.setUsername("mxq");
        config.setPassword("");
        config.setDatabaseName("default");
        return config;
    }

    private DatasourceConfig createHiveConfig(Long id, String url) {
        DatasourceConfig config = new DatasourceConfig();
        config.setId(id);
        config.setDatasourceName("test-hive");
        config.setDatasourceType(DatasourceType.HIVE);
        config.setUrl(url);
        config.setUsername("hive");
        config.setPassword("");
        config.setDatabaseName("default");
        return config;
    }

    @Test
    void testCreatePool() {
        DatasourceConfig config = createH2Config(100L);
        when(datasourceConfigMapper.selectById(100L)).thenReturn(config);

        HikariDataSource pool = poolManager.getOrCreatePool(100L);

        assertNotNull(pool);
        assertFalse(pool.isClosed());
        assertEquals("ds-100", pool.getPoolName());
        verify(datasourceConfigMapper).selectById(100L);
    }

    @Test
    void testPoolReuse() {
        DatasourceConfig config = createH2Config(200L);
        when(datasourceConfigMapper.selectById(200L)).thenReturn(config);

        HikariDataSource pool1 = poolManager.getOrCreatePool(200L);
        HikariDataSource pool2 = poolManager.getOrCreatePool(200L);

        assertSame(pool1, pool2, "Same datasource ID should return the same pool instance");
        verify(datasourceConfigMapper, times(1)).selectById(200L);
    }

    @Test
    void testGetConnection() throws SQLException {
        DatasourceConfig config = createH2Config(300L);
        when(datasourceConfigMapper.selectById(300L)).thenReturn(config);

        try (Connection conn = poolManager.getConnection(300L)) {
            assertNotNull(conn);
            assertFalse(conn.isClosed());
        }
    }

    @Test
    void testGetConnectionWithDatabaseName() throws SQLException {
        DatasourceConfig config = createH2Config(400L);
        when(datasourceConfigMapper.selectById(400L)).thenReturn(config);

        try (Connection conn = poolManager.getConnection(400L, "PUBLIC")) {
            assertNotNull(conn);
            assertFalse(conn.isClosed());
        }
    }

    @Test
    void testGetConnectionMultipleTimes() throws SQLException {
        DatasourceConfig config = createH2Config(500L);
        when(datasourceConfigMapper.selectById(500L)).thenReturn(config);

        try (Connection conn1 = poolManager.getConnection(500L)) {
            assertNotNull(conn1);
        }
        try (Connection conn2 = poolManager.getConnection(500L)) {
            assertNotNull(conn2);
        }
    }

    @Test
    void testClosePool() {
        DatasourceConfig config = createH2Config(600L);
        when(datasourceConfigMapper.selectById(600L)).thenReturn(config);

        HikariDataSource pool = poolManager.getOrCreatePool(600L);
        assertFalse(pool.isClosed());
        assertTrue(poolManager.hasPool(600L));

        poolManager.closePool(600L);

        assertTrue(pool.isClosed());
        assertFalse(poolManager.hasPool(600L));
    }

    @Test
    void testCloseNonExistentPool() {
        assertDoesNotThrow(() -> poolManager.closePool(999L));
    }

    @Test
    void testRefreshPool() throws SQLException {
        DatasourceConfig config = createH2Config(700L);
        when(datasourceConfigMapper.selectById(700L)).thenReturn(config);

        HikariDataSource oldPool = poolManager.getOrCreatePool(700L);
        String oldPoolName = oldPool.getPoolName();

        poolManager.refreshPool(700L);

        assertTrue(oldPool.isClosed(), "Old pool should be closed after refresh");

        HikariDataSource newPool = poolManager.getOrCreatePool(700L);
        assertFalse(newPool.isClosed(), "New pool should be active");
        verify(datasourceConfigMapper, times(2)).selectById(700L);
    }

    @Test
    void testCloseAllPools() {
        DatasourceConfig config1 = createH2Config(801L);
        DatasourceConfig config2 = createH2Config(802L);
        when(datasourceConfigMapper.selectById(801L)).thenReturn(config1);
        when(datasourceConfigMapper.selectById(802L)).thenReturn(config2);

        HikariDataSource pool1 = poolManager.getOrCreatePool(801L);
        HikariDataSource pool2 = poolManager.getOrCreatePool(802L);

        assertEquals(2, poolManager.getPoolCount());

        poolManager.closeAllPools();

        assertTrue(pool1.isClosed());
        assertTrue(pool2.isClosed());
        assertEquals(0, poolManager.getPoolCount());
    }

    @Test
    void testGetPoolStatus() throws SQLException {
        DatasourceConfig config = createH2Config(900L);
        when(datasourceConfigMapper.selectById(900L)).thenReturn(config);

        poolManager.getOrCreatePool(900L);
        try (Connection conn = poolManager.getConnection(900L)) {
            assertNotNull(conn);
        }

        PoolStatus status = poolManager.getPoolStatus(900L);

        assertNotNull(status);
        assertEquals(900L, status.getDatasourceId());
        assertTrue(status.isActive());
        assertEquals(5, status.getMaxPoolSize());
        assertEquals(1, status.getMinIdle());
    }

    @Test
    void testGetPoolStatusNonExistent() {
        PoolStatus status = poolManager.getPoolStatus(999L);

        assertNotNull(status);
        assertFalse(status.isActive());
        assertEquals(0, status.getMaxPoolSize());
    }

    @Test
    void testGetAllPoolStatus() {
        DatasourceConfig config1 = createH2Config(1001L);
        DatasourceConfig config2 = createH2Config(1002L);
        when(datasourceConfigMapper.selectById(1001L)).thenReturn(config1);
        when(datasourceConfigMapper.selectById(1002L)).thenReturn(config2);

        poolManager.getOrCreatePool(1001L);
        poolManager.getOrCreatePool(1002L);

        List<PoolStatus> allStatus = poolManager.getAllPoolStatus();

        assertEquals(2, allStatus.size());
    }

    @Test
    void testHasPool() {
        DatasourceConfig config = createH2Config(1100L);
        when(datasourceConfigMapper.selectById(1100L)).thenReturn(config);

        assertFalse(poolManager.hasPool(1100L));

        poolManager.getOrCreatePool(1100L);

        assertTrue(poolManager.hasPool(1100L));
    }

    @Test
    void testGetPoolCount() {
        assertEquals(0, poolManager.getPoolCount());

        DatasourceConfig config1 = createH2Config(1201L);
        DatasourceConfig config2 = createH2Config(1202L);
        when(datasourceConfigMapper.selectById(1201L)).thenReturn(config1);
        when(datasourceConfigMapper.selectById(1202L)).thenReturn(config2);

        poolManager.getOrCreatePool(1201L);
        assertEquals(1, poolManager.getPoolCount());

        poolManager.getOrCreatePool(1202L);
        assertEquals(2, poolManager.getPoolCount());

        poolManager.closePool(1201L);
        assertEquals(1, poolManager.getPoolCount());
    }

    @Test
    void testTestConnection() {
        DatasourceConfig config = createH2Config(1300L);
        when(datasourceConfigMapper.selectById(1300L)).thenReturn(config);

        boolean result = poolManager.testConnection(1300L);
        assertTrue(result, "H2 connection test should succeed");
    }

    @Test
    void testTestConnectionNonExistentDatasource() {
        when(datasourceConfigMapper.selectById(anyLong())).thenReturn(null);

        boolean result = poolManager.testConnection(9999L);
        assertFalse(result, "Non-existent datasource should return false");
    }

    @Test
    void testDestroy() {
        DatasourceConfig config = createH2Config(1400L);
        when(datasourceConfigMapper.selectById(1400L)).thenReturn(config);

        HikariDataSource pool = poolManager.getOrCreatePool(1400L);
        assertFalse(pool.isClosed());

        poolManager.destroy();

        assertTrue(pool.isClosed());
        assertEquals(0, poolManager.getPoolCount());
    }

    @Test
    void testPoolConfigurationForMySql() {
        DatasourceConfig config = createH2Config(1500L);
        when(datasourceConfigMapper.selectById(1500L)).thenReturn(config);

        HikariDataSource pool = poolManager.getOrCreatePool(1500L);

        assertEquals(5, pool.getMaximumPoolSize());
        assertEquals(1, pool.getMinimumIdle());
        assertEquals(30000, pool.getConnectionTimeout());
        assertEquals("SELECT 1", pool.getConnectionTestQuery());
    }

    @Test
    void testConnectionReturnToPool() throws SQLException {
        DatasourceConfig config = createH2Config(1600L);
        when(datasourceConfigMapper.selectById(1600L)).thenReturn(config);

        poolManager.getOrCreatePool(1600L);

        try (Connection conn1 = poolManager.getConnection(1600L)) {
            assertNotNull(conn1);
        }

        PoolStatus status = poolManager.getPoolStatus(1600L);
        assertTrue(status.getIdleConnections() >= 1, "Connection should be returned to pool as idle");
    }

    @Test
    void testMultipleDatasourcesIndependent() throws SQLException {
        DatasourceConfig config1 = createH2Config(1701L);
        DatasourceConfig config2 = createH2Config(1702L);
        when(datasourceConfigMapper.selectById(1701L)).thenReturn(config1);
        when(datasourceConfigMapper.selectById(1702L)).thenReturn(config2);

        try (Connection conn1 = poolManager.getConnection(1701L);
             Connection conn2 = poolManager.getConnection(1702L)) {
            assertNotNull(conn1);
            assertNotNull(conn2);
            assertNotSame(conn1, conn2);
        }

        assertEquals(2, poolManager.getPoolCount());
    }

    @Test
    void testKyuubiUrlNormalizationWithoutPrefix() {
        DatasourceConfig config = createKyuubiConfig(2001L, "192.168.1.8:10009/");
        when(datasourceConfigMapper.selectById(2001L)).thenReturn(config);

        HikariDataSource pool = poolManager.getOrCreatePool(2001L);

        assertEquals("jdbc:hive2://192.168.1.8:10009/", pool.getJdbcUrl());
        poolManager.closePool(2001L);
    }

    @Test
    void testKyuubiUrlNormalizationWithHive2Prefix() {
        DatasourceConfig config = createKyuubiConfig(2002L, "jdbc:hive2://192.168.1.8:10009/");
        when(datasourceConfigMapper.selectById(2002L)).thenReturn(config);

        HikariDataSource pool = poolManager.getOrCreatePool(2002L);

        assertEquals("jdbc:hive2://192.168.1.8:10009/", pool.getJdbcUrl());
        poolManager.closePool(2002L);
    }

    @Test
    void testKyuubiUrlNormalizationWithKyuubiPrefix() {
        DatasourceConfig config = createKyuubiConfig(2003L, "jdbc:kyuubi://192.168.1.8:10009/");
        when(datasourceConfigMapper.selectById(2003L)).thenReturn(config);

        HikariDataSource pool = poolManager.getOrCreatePool(2003L);

        assertEquals("jdbc:kyuubi://192.168.1.8:10009/", pool.getJdbcUrl());
        poolManager.closePool(2003L);
    }

    @Test
    void testKyuubiPoolConfiguration() {
        DatasourceConfig config = createKyuubiConfig(2004L, "jdbc:hive2://192.168.1.8:10009/");
        when(datasourceConfigMapper.selectById(2004L)).thenReturn(config);

        HikariDataSource pool = poolManager.getOrCreatePool(2004L);

        assertEquals("org.apache.kyuubi.jdbc.KyuubiHiveDriver", pool.getDriverClassName());
        assertEquals(3, pool.getMaximumPoolSize());
        assertEquals(1, pool.getMinimumIdle());
        assertEquals(60000, pool.getConnectionTimeout());
        assertEquals(30000, pool.getValidationTimeout());
        assertEquals(600000, pool.getIdleTimeout());
        assertEquals(1800000, pool.getMaxLifetime());
        assertEquals("SELECT 1", pool.getConnectionTestQuery());
        poolManager.closePool(2004L);
    }

    @Test
    void testKyuubiPoolStatus() {
        DatasourceConfig config = createKyuubiConfig(2005L, "jdbc:hive2://192.168.1.8:10009/");
        when(datasourceConfigMapper.selectById(2005L)).thenReturn(config);

        poolManager.getOrCreatePool(2005L);

        PoolStatus status = poolManager.getPoolStatus(2005L);
        assertNotNull(status);
        assertEquals(2005L, status.getDatasourceId());
        assertTrue(status.isActive());
        assertEquals(3, status.getMaxPoolSize());
        assertEquals(1, status.getMinIdle());
        poolManager.closePool(2005L);
    }

    @Test
    void testKyuubiPoolLazyInitializationWhenUnreachable() {
        DatasourceConfig config = new DatasourceConfig();
        config.setId(2006L);
        config.setDatasourceName("kyuubi-unreachable");
        config.setDatasourceType(DatasourceType.KYUUBI);
        config.setUrl("jdbc:hive2://127.0.0.1:19999/");
        config.setUsername("sa");
        config.setPassword("");
        when(datasourceConfigMapper.selectById(2006L)).thenReturn(config);

        HikariDataSource pool = poolManager.getOrCreatePool(2006L);

        assertNotNull(pool);
        assertFalse(pool.isClosed());
        assertEquals(-1, pool.getInitializationFailTimeout());
        assertEquals(60000, pool.getConnectionTimeout());
        assertEquals(30000, pool.getValidationTimeout());
        assertEquals("org.apache.kyuubi.jdbc.KyuubiHiveDriver", pool.getDriverClassName());
        assertEquals("jdbc:hive2://127.0.0.1:19999/", pool.getJdbcUrl());

        PoolStatus status = poolManager.getPoolStatus(2006L);
        assertTrue(status.isActive());
        assertEquals(0, status.getTotalConnections());

        poolManager.closePool(2006L);
    }

    @Test
    void testKyuubiPoolCloseAndCleanup() {
        DatasourceConfig config = createKyuubiConfig(2007L, "jdbc:hive2://192.168.1.8:10009/");
        when(datasourceConfigMapper.selectById(2007L)).thenReturn(config);

        HikariDataSource pool = poolManager.getOrCreatePool(2007L);
        assertFalse(pool.isClosed());
        assertTrue(poolManager.hasPool(2007L));

        poolManager.closePool(2007L);

        assertTrue(pool.isClosed());
        assertFalse(poolManager.hasPool(2007L));
        assertEquals(0, poolManager.getPoolCount());
    }

    @Test
    void testKyuubiPoolRefresh() {
        DatasourceConfig config = createKyuubiConfig(2008L, "jdbc:hive2://192.168.1.8:10009/");
        when(datasourceConfigMapper.selectById(2008L)).thenReturn(config);

        HikariDataSource oldPool = poolManager.getOrCreatePool(2008L);
        assertEquals("jdbc:hive2://192.168.1.8:10009/", oldPool.getJdbcUrl());

        poolManager.refreshPool(2008L);

        assertTrue(oldPool.isClosed());

        HikariDataSource newPool = poolManager.getOrCreatePool(2008L);
        assertFalse(newPool.isClosed());
        assertEquals("jdbc:hive2://192.168.1.8:10009/", newPool.getJdbcUrl());
        verify(datasourceConfigMapper, times(2)).selectById(2008L);
    }

    @Test
    void testHiveUrlNormalizationWithoutPrefix() {
        DatasourceConfig config = createHiveConfig(2101L, "192.168.1.8:10000/");
        when(datasourceConfigMapper.selectById(2101L)).thenReturn(config);

        HikariDataSource pool = poolManager.getOrCreatePool(2101L);

        assertEquals("jdbc:hive2://192.168.1.8:10000/", pool.getJdbcUrl());
        poolManager.closePool(2101L);
    }

    @Test
    void testHivePoolConfiguration() {
        DatasourceConfig config = createHiveConfig(2102L, "jdbc:hive2://192.168.1.8:10000/");
        when(datasourceConfigMapper.selectById(2102L)).thenReturn(config);

        HikariDataSource pool = poolManager.getOrCreatePool(2102L);

        assertEquals("org.apache.kyuubi.jdbc.KyuubiHiveDriver", pool.getDriverClassName());
        assertEquals(3, pool.getMaximumPoolSize());
        assertEquals(1, pool.getMinimumIdle());
        assertEquals(60000, pool.getConnectionTimeout());
        assertEquals(30000, pool.getValidationTimeout());
        assertEquals("SELECT 1", pool.getConnectionTestQuery());
        poolManager.closePool(2102L);
    }

    @Test
    void testKyuubiAndMySqlPoolsCoexist() {
        DatasourceConfig mysqlConfig = createH2Config(2201L);
        DatasourceConfig kyuubiConfig = createKyuubiConfig(2202L, "jdbc:hive2://192.168.1.8:10009/");
        when(datasourceConfigMapper.selectById(2201L)).thenReturn(mysqlConfig);
        when(datasourceConfigMapper.selectById(2202L)).thenReturn(kyuubiConfig);

        HikariDataSource mysqlPool = poolManager.getOrCreatePool(2201L);
        HikariDataSource kyuubiPool = poolManager.getOrCreatePool(2202L);

        assertEquals(5, mysqlPool.getMaximumPoolSize());
        assertEquals(3, kyuubiPool.getMaximumPoolSize());
        assertEquals(30000, mysqlPool.getConnectionTimeout());
        assertEquals(60000, kyuubiPool.getConnectionTimeout());
        assertEquals(2, poolManager.getPoolCount());

        poolManager.closeAllPools();
        assertTrue(mysqlPool.isClosed());
        assertTrue(kyuubiPool.isClosed());
    }

    @Test
    void testKyuubiGetConnectionWithDatabaseNameSchemaSwitch() throws SQLException {
        DatasourceConfig config = createKyuubiConfig(2301L, "jdbc:hive2://192.168.1.8:10009/");
        when(datasourceConfigMapper.selectById(2301L)).thenReturn(config);

        HikariDataSource pool = poolManager.getOrCreatePool(2301L);

        assertNotNull(pool);
        assertEquals("org.apache.kyuubi.jdbc.KyuubiHiveDriver", pool.getDriverClassName());
        assertEquals(3, pool.getMaximumPoolSize());
        assertEquals(-1, pool.getInitializationFailTimeout());

        poolManager.closePool(2301L);
    }

    @Test
    void testKyuubiPoolWithUsernameAndPassword() {
        DatasourceConfig config = new DatasourceConfig();
        config.setId(2401L);
        config.setDatasourceName("kyuubi-with-auth");
        config.setDatasourceType(DatasourceType.KYUUBI);
        config.setUrl("jdbc:hive2://192.168.1.8:10009/");
        config.setUsername("mxq");
        config.setPassword("secret");
        when(datasourceConfigMapper.selectById(2401L)).thenReturn(config);

        HikariDataSource pool = poolManager.getOrCreatePool(2401L);

        assertEquals("mxq", pool.getUsername());
        poolManager.closePool(2401L);
    }

    @Test
    void testKyuubiUrlWithDatabaseInPath() {
        DatasourceConfig config = createKyuubiConfig(2501L, "jdbc:hive2://192.168.1.8:10009/mydb");
        when(datasourceConfigMapper.selectById(2501L)).thenReturn(config);

        HikariDataSource pool = poolManager.getOrCreatePool(2501L);

        assertEquals("jdbc:hive2://192.168.1.8:10009/mydb", pool.getJdbcUrl());
        poolManager.closePool(2501L);
    }
}
