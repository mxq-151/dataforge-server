package com.bigdata.modules.development.service.execution;

import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.StorageEngineType;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.vo.ColumnMetadataVO;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KyuubiExecutionStrategy 集成测试（直连真实Kyuubi服务）
 * <p>
 * 连接目标：kyuubi.com:10099
 * 验证完整REST API调用链：
 * 1. buildBaseUrl / createAuthHeaders / buildSessionConfiguration（纯逻辑方法，反射测试）
 * 2. testConnection（创建Session + 关闭Session）
 * 3. listDatabases（创建Session → 提交SHOW DATABASES → 轮询event → 获取rowset → 关闭Session）
 * 4. listTables（同上流程，SQL为 SHOW TABLES IN xxx）
 * 5. listColumns（同上流程，SQL为 DESCRIBE db.table）
 */
@DisplayName("KyuubiExecutionStrategy 集成测试（直连kyuubi.com:10099）")
class KyuubiExecutionStrategyTest {

    private static final String KYUUBI_URL = "http://kyuubi.com:10099";
    private static final String KYUUBI_USERNAME = "mxq";
    private static final String KYUUBI_PASSWORD = "";

    private KyuubiExecutionStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new KyuubiExecutionStrategy();
        // 使用真实的RestTemplate连接远程服务
        ReflectionTestUtils.setField(strategy, "restTemplate", new org.springframework.web.client.RestTemplate());
        // 注入必要的依赖（元数据查询不需要这些，但避免NPE）
        ReflectionTestUtils.setField(strategy, "processManager", null);
        ReflectionTestUtils.setField(strategy, "metadataSyncService", null);
        ReflectionTestUtils.setField(strategy, "executionLogListener", null);
    }

    // ==================== 纯逻辑方法测试（反射调用，不依赖网络）====================

    @Nested
    @DisplayName("buildBaseUrl - URL转换")
    class BuildBaseUrlTests {

        @Test
        @DisplayName("jdbc:hive2://host:port/db -> http://host:port")
        void testHive2Url() throws Exception {
            assertEquals("http://kyuubi-server:10099", invokeBuildBaseUrl("jdbc:hive2://kyuubi-server:10099/default"));
        }

        @Test
        @DisplayName("http:// 开头的URL直接返回（去掉尾部斜杠）")
        void testHttpUrl() throws Exception {
            assertEquals("http://kyuubi.com:10099", invokeBuildBaseUrl("http://kyuubi.com:10099/"));
        }

        @Test
        @DisplayName("https:// 开头的URL直接返回")
        void testHttpsUrl() throws Exception {
            assertEquals("https://kyuubi.com:10099", invokeBuildBaseUrl("https://kyuubi.com:10099"));
        }

        @Test
        @DisplayName("空URL抛出异常")
        void testNullUrlThrowsException() throws Exception {
            assertThrows(Exception.class, () -> invokeBuildBaseUrl(null));
            assertThrows(Exception.class, () -> invokeBuildBaseUrl(""));
        }
    }

    @Nested
    @DisplayName("createAuthHeaders - Basic Auth头生成")
    class CreateAuthHeadersTests {

        @Test
        @DisplayName("用户名密码正常生成Authorization头")
        void testNormalCredentials() throws Exception {
            HttpHeaders headers = invokeCreateAuthHeaders("mxq", "password123");
            assertTrue(headers.containsKey("Authorization"));
            assertTrue(headers.getFirst("Authorization").startsWith("Basic "));
        }
    }

    @Nested
    @DisplayName("buildSessionConfiguration - Session配置构建")
    class BuildSessionConfigTests {

        @Test
        @DisplayName("HIVE存储引擎配置")
        void testHiveEngineConfig() throws Exception {
            var config = invokeBuildSessionConfiguration(StorageEngineType.HIVE);
            assertEquals("true", config.get("hive.exec.parallel"));
            assertEquals("8", config.get("hive.exec.parallel.thread.number"));
        }

        @Test
        @DisplayName("context为null返回空配置")
        void testNullContextReturnsEmptyConfig() throws Exception {
            var config = invokeBuildSessionConfiguration(null);
            assertNotNull(config);
            assertTrue(config.isEmpty());
        }
    }

    @Nested
    @DisplayName("parseOperationState - 操作状态解析")
    class ParseOperationStateTests {

        @Test
        @DisplayName("state=FINISHED正确解析")
        void testFinishedState() throws Exception {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode body = mapper.createObjectNode();
            body.put("state", "FINISHED");
            assertEquals("FINISHED", invokeParseOperationState(body));
        }

        @Test
        @DisplayName("state=RUNNING正确解析")
        void testRunningState() throws Exception {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode body = mapper.createObjectNode();
            body.put("state", "running");
            assertEquals("RUNNING", invokeParseOperationState(body));
        }

        @Test
        @DisplayName("缺少state字段默认RUNNING")
        void testMissingStateDefaultsToRunning() throws Exception {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode body = mapper.createObjectNode();
            assertEquals("RUNNING", invokeParseOperationState(body));
        }
    }

    // ==================== 直连真实服务的集成测试 ====================

    @Nested
    @DisplayName("testConnection - 直连Kyuubi服务测试连接")
    class ConnectionIntegrationTests {

        @Test
        @DisplayName("成功创建并关闭Session")
        void testConnectionSuccess() {
            DatasourceConfig config = createRealDatasourceConfig();

            boolean result = strategy.testConnection(config);

            assertTrue(result, "连接kyuubi.com:10099应成功");
        }
    }

    @Nested
    @DisplayName("listDatabases - 直连获取数据库列表")
    class ListDatabasesIntegrationTests {

        @Test
        @DisplayName("获取数据库列表不为空")
        void testListDatabasesNotEmpty() {
            DatasourceConfig config = createRealDatasourceConfig();

            List<String> databases = strategy.listDatabases(config);

            assertNotNull(databases, "数据库列表不应为null");
            assertFalse(databases.isEmpty(), "应至少返回一个数据库");
            System.out.println("[集成测试] 数据库列表: " + databases);
        }
    }

    @Nested
    @DisplayName("listTables - 直连获取表列表")
    class ListTablesIntegrationTests {

        @Test
        @DisplayName("获取指定库下的表列表")
        void testListTablesInDatabase() {
            DatasourceConfig config = createRealDatasourceConfig();

            // 先获取可用的数据库
            List<String> databases = strategy.listDatabases(config);
            if (databases.isEmpty()) {
                fail("没有可用数据库，无法测试listTables");
                return;
            }
            String databaseName = databases.get(0); // 使用第一个数据库

            List<TableMetadata> tables = strategy.listTables(config, databaseName);

            assertNotNull(tables, "表列表不应为null");
            System.out.println("[集成测试] 数据库[" + databaseName + "]下的表: " +
                    tables.stream().map(TableMetadata::getTableName).toList());

            for (TableMetadata table : tables) {
                assertEquals(databaseName, table.getDatabaseName(), "databaseName应匹配");
                assertNotNull(table.getTableName(), "tableName不应为null");
                assertNotNull(table.getTableType(), "tableType不应为null");
            }
        }
    }

    @Nested
    @DisplayName("listColumns - 直连获取字段信息")
    class ListColumnsIntegrationTests {

        @Test
        @DisplayName("获取指定表的字段信息")
        void testListColumnsOfTable() {
            DatasourceConfig config = createRealDatasourceConfig();

            // 获取数据库和表
            List<String> databases = strategy.listDatabases(config);
            if (databases.isEmpty()) {
                fail("没有可用数据库");
                return;
            }
            String databaseName = databases.get(0);

            List<TableMetadata> tables = strategy.listTables(config, databaseName);
            if (tables.isEmpty()) {
                System.out.println("[集成测试] 数据库[" + databaseName + "]下无表，跳过listColumns测试");
                return; // 无表则跳过
            }
            String tableName = tables.get(0).getTableName(); // 使用第一张表

            List<ColumnMetadataVO> columns = strategy.listColumns(config, databaseName, tableName);

            assertNotNull(columns, "字段列表不应为null");
            assertFalse(columns.isEmpty(), "应至少返回一个字段");
            System.out.println("[集成测试] 表[" + databaseName + "." + tableName + "]的字段:");
            for (ColumnMetadataVO col : columns) {
                System.out.println("  - " + col.getColumnName() + " (" + col.getColumnType() + ") "
                        + (col.getColumnComment() != null ? col.getColumnComment() : ""));
                assertNotNull(col.getColumnName(), "columnName不应为null");
                assertNotNull(col.getColumnType(), "columnType不应为null");
                assertTrue(col.getOrdinalPosition() > 0, "ordinalPosition应大于0");
                assertEquals(databaseName, col.getDatabaseName(), "databaseName应匹配");
                assertEquals(tableName, col.getTableName(), "tableName应匹配");
            }
        }
    }

    @Nested
    @DisplayName("策略基础信息")
    class StrategyBasicInfoTests {

        @Test
        @DisplayName("engineType 为 KYUUBI")
        void testGetEngineType() {
            assertEquals(EngineType.KYUUBI, strategy.getEngineType());
        }

        @Test
        @DisplayName("支持 SQL 类型")
        void testSupportsSql() {
            assertTrue(strategy.supports(CodeType.SQL));
        }

        @Test
        @DisplayName("不支持 JAVA 类型")
        void testNotSupportJava() {
            assertFalse(strategy.supports(CodeType.JAVA));
        }
    }

    // ==================== 辅助方法 ====================

    /** 创建指向真实Kyuubi服务的DatasourceConfig */
    private DatasourceConfig createRealDatasourceConfig() {
        DatasourceConfig config = new DatasourceConfig();
        config.setId(2L);
        config.setDatasourceName("181_host");
        config.setUrl(KYUUBI_URL);
        config.setUsername(KYUUBI_USERNAME);
        config.setPassword(KYUUBI_PASSWORD);
        config.setStorageEngine(StorageEngineType.HIVE);
        return config;
    }

    // 反射辅助方法
    private String invokeBuildBaseUrl(String url) throws Exception {
        Method method = KyuubiExecutionStrategy.class.getDeclaredMethod("buildBaseUrl", String.class);
        method.setAccessible(true);
        return (String) method.invoke(strategy, url);
    }

    private org.springframework.http.HttpHeaders invokeCreateAuthHeaders(String username, String password) throws Exception {
        Method method = KyuubiExecutionStrategy.class.getDeclaredMethod("createAuthHeaders", String.class, String.class);
        method.setAccessible(true);
        return (org.springframework.http.HttpHeaders) method.invoke(strategy, username, password);
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, String> invokeBuildSessionConfiguration(StorageEngineType engineType) throws Exception {
        CodeExecutionContext ctx = new CodeExecutionContext();
        DatasourceConfig ds = new DatasourceConfig();
        ds.setStorageEngine(engineType);
        ctx.setDatasourceConfigs(java.util.Collections.singletonList(ds));

        Method method = KyuubiExecutionStrategy.class.getDeclaredMethod("buildSessionConfiguration", CodeExecutionContext.class);
        method.setAccessible(true);
        return (java.util.Map<String, String>) method.invoke(strategy, ctx);
    }

    private String invokeParseOperationState(com.fasterxml.jackson.databind.JsonNode eventBody) throws Exception {
        Method method = KyuubiExecutionStrategy.class.getDeclaredMethod("parseOperationState", com.fasterxml.jackson.databind.JsonNode.class);
        method.setAccessible(true);
        return (String) method.invoke(strategy, eventBody);
    }
}
