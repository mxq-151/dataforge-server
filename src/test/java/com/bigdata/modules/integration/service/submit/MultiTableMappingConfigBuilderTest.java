package com.bigdata.modules.integration.service.submit;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.integration.config.IntegrationClusterProperties;
import com.bigdata.modules.integration.enums.ClusterType;
import com.bigdata.modules.integration.enums.DataSourceKind;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖「单源 + 单目标 + 多张表」的 Flink CDC 作业生成：
 * 每张表一个 CDC source（MySQL 分配独立 server-id）、列投影、排除字段、
 * 读并行度落到 source、写并行度落到 sink、历史 JDBC URL 兼容与 SeaTunnel 迁移。
 */
class MultiTableMappingConfigBuilderTest {

    /** 模拟从源库元数据读取到的列清单 */
    private static final List<ColumnDef> COLUMNS = List.of(
            new ColumnDef("id", "BIGINT"),
            new ColumnDef("name", "STRING"),
            new ColumnDef("amount", "DECIMAL(18,2)"),
            new ColumnDef("etl_time", "TIMESTAMP(3)"));

    @Test
    void mysqlCdcGeneratesPerTableSourceWithProjectionAndServerIds() {
        DataIntegrationSubmitter.SubmitContext ctx = context("mysql", mysqlSource(), "kafka", kafkaTarget(),
                List.of(table("orders", "etl_time"),
                        table("users", "name", "amount"),
                        table("pay")));

        String sql = FlinkSqlBuilder.build(ctx, new IntegrationClusterProperties.Flink(), 4, 2);
        System.out.println("===== Flink CDC 多表 SQL =====\n" + sql);

        assertEquals(3, count(sql, "'connector' = 'mysql-cdc'"));
        assertEquals(3, count(sql, "CREATE TABLE `src_"));
        assertEquals(3, count(sql, "CREATE TABLE `snk_"));
        assertEquals(3, count(sql, "INSERT INTO"));

        // 源端连接拆成 hostname / port / database-name，表名只取表清单
        assertTrue(sql.contains("'hostname' = '127.0.0.1'"));
        assertTrue(sql.contains("'port' = '3306'"));
        assertEquals(3, count(sql, "'database-name' = 'ods'"));
        assertEquals(1, count(sql, "'table-name' = 'orders'"));
        assertEquals(1, count(sql, "'table-name' = 'users'"));
        assertEquals(1, count(sql, "'table-name' = 'pay'"));

        // 每表一个 CDC source，server-id 互不冲突（默认基数 5400 + taskId 偏移 10）
        assertTrue(sql.contains("'server-id' = '5410'"), sql);
        assertTrue(sql.contains("'server-id' = '5411'"), sql);
        assertTrue(sql.contains("'server-id' = '5412'"), sql);
        assertTrue(sql.contains("'scan.startup.mode' = 'initial'"));

        // 排除字段不进入对应表的列清单与投影
        assertFalse(sourceBlock(sql, "src_0").contains("`etl_time`"), "src_0 不应包含排除字段 etl_time");
        assertFalse(sourceBlock(sql, "src_1").contains("`amount`"), "src_1 不应包含排除字段 amount");
        assertTrue(sourceBlock(sql, "src_2").contains("`etl_time`"), "未排除的表应保留全部字段");
        assertTrue(sql.contains("INSERT INTO `snk_0` SELECT `id`, `name`, `amount` FROM `src_0`;"));

        // 读并行度落到作业级与 CDC 快照；写并行度落到每个 sink
        assertTrue(sql.contains("SET 'parallelism.default' = '4'"));
        assertEquals(3, count(sql, "'scan.incremental.snapshot.parallelism' = '4'"));
        assertEquals(3, count(sql, "'sink.parallelism' = '2'"));

        // 表名按表清单推导目标 topic；不再有 SELECT *
        assertTrue(sql.contains("'topic' = 'orders'"));
        assertTrue(sql.contains("'topic' = 'users'"));
        assertTrue(sql.contains("'topic' = 'pay'"));
        assertFalse(sql.contains("SELECT *"));
        assertFalse(sql.contains("legacy_only"), "不应读取 source_config 中遗留的 columns");
        assertFalse(sql.contains("'columns' ="));

        // 每表一个 source，不合并成一个正则 table-name
        assertFalse(sql.contains("orders|users"));
    }

    @Test
    void whereFilterIsAppliedToCdcProjection() {
        Map<String, Object> source = mysqlSource();
        source.put("where", "amount > 100");
        DataIntegrationSubmitter.SubmitContext ctx = context("mysql", source, "kafka", kafkaTarget(),
                List.of(table("orders")));

        String sql = FlinkSqlBuilder.build(ctx, new IntegrationClusterProperties.Flink(), 2, 2);
        assertTrue(sql.contains("FROM `src_0` WHERE amount > 100;"), sql);
    }

    @Test
    void postgresCdcUsesSchemaNameAndReplicationSlot() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("host", "10.0.0.9");
        source.put("port", "5433");
        source.put("database", "ods");
        source.put("schema", "public");
        source.put("username", "repl");
        source.put("password", "secret");
        DataIntegrationSubmitter.SubmitContext ctx = context("postgresql", source, "kafka", kafkaTarget(),
                List.of(table("orders"), table("users")));

        String sql = FlinkSqlBuilder.build(ctx, new IntegrationClusterProperties.Flink(), 2, 2);
        System.out.println("===== Flink PG CDC SQL =====\n" + sql);

        assertEquals(2, count(sql, "'connector' = 'postgres-cdc'"));
        assertTrue(sql.contains("'hostname' = '10.0.0.9'"));
        assertTrue(sql.contains("'port' = '5433'"));
        assertTrue(sql.contains("'database-name' = 'ods'"));
        assertTrue(sql.contains("'schema-name' = 'public'"));
        assertTrue(sql.contains("'decoding.plugin.name' = 'pgoutput'"));
        // 复制槽按任务编码 + 序号命名，两个 source 名字不同
        assertTrue(sql.contains("'slot.name' = 'df_multi_sync_0'"));
        assertTrue(sql.contains("'slot.name' = 'df_multi_sync_1'"));
        // PostgreSQL 不需要 server-id
        assertFalse(sql.contains("'server-id'"));
        // MySQL CDC 专有参数不能出现在 PG source 上（否则 connector 报 Unsupported options）
        assertFalse(sql.contains("'scan.incremental.snapshot.parallelism'"));
        assertFalse(sql.contains("'server-time-zone'"));
    }

    @Test
    void oracleCdcUsesSchemaAndScnStartup() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("host", "10.0.0.8");
        source.put("port", "1521");
        source.put("database", "ORCLCDB");
        source.put("schema", "INVENTORY");
        source.put("username", "flinkuser");
        source.put("password", "secret");
        source.put("startupMode", "specific-offset");
        source.put("startupScn", "2384593");
        source.put("connectionPoolSize", "15");
        DataIntegrationSubmitter.SubmitContext ctx = context("oracle", source, "kafka", kafkaTarget(),
                List.of(table("PRODUCTS")));

        String sql = FlinkSqlBuilder.build(ctx, new IntegrationClusterProperties.Flink(), 2, 1);
        System.out.println("===== Oracle CDC SQL =====\n" + sql);

        assertTrue(sql.contains("'connector' = 'oracle-cdc'"), sql);
        assertTrue(sql.contains("'schema-name' = 'INVENTORY'"), sql);
        assertTrue(sql.contains("'table-name' = 'PRODUCTS'"), sql);
        assertTrue(sql.contains("'scan.startup.mode' = 'specific-offset'"), sql);
        assertTrue(sql.contains("'scan.startup.specific-offset.scn' = '2384593'"), sql);
        assertTrue(sql.contains("'connection.pool.size' = '15'"), sql);
        assertFalse(sql.contains("'server-id'"), sql);
    }

    @Test
    void oracleRejectsSchemaQualifiedTable() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("host", "10.0.0.8");
        source.put("database", "ORCLCDB");
        source.put("schema", "INVENTORY");
        source.put("username", "flinkuser");
        source.put("password", "secret");
        DataIntegrationSubmitter.SubmitContext ctx = context("oracle", source, "kafka", kafkaTarget(),
                List.of(table("INVENTORY.PRODUCTS")));

        BusinessException error = assertThrows(BusinessException.class,
                () -> FlinkSqlBuilder.build(ctx, new IntegrationClusterProperties.Flink(), 1, 1));
        assertTrue(error.getMessage().contains("只填表名"), error.getMessage());
    }

    @Test
    void oracleRejectsTimestampStartup() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("host", "10.0.0.8");
        source.put("database", "ORCLCDB");
        source.put("schema", "INVENTORY");
        source.put("username", "flinkuser");
        source.put("password", "secret");
        source.put("startupMode", "timestamp");
        source.put("startupTimestamp", "1700000000000");
        DataIntegrationSubmitter.SubmitContext ctx = context("oracle", source, "kafka", kafkaTarget(),
                List.of(table("PRODUCTS")));

        BusinessException error = assertThrows(BusinessException.class,
                () -> FlinkSqlBuilder.build(ctx, new IntegrationClusterProperties.Flink(), 1, 1));
        assertTrue(error.getMessage().contains("不支持 timestamp"), error.getMessage());
    }

    @Test
    void sqlServerCdcQualifiesTableWithSchema() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("host", "10.0.0.9");
        source.put("port", "1433");
        source.put("database", "inventory");
        source.put("schema", "dbo");
        source.put("username", "sa");
        source.put("password", "secret");
        source.put("startupMode", "latest-offset");
        source.put("serverTimezone", "UTC");
        DataIntegrationSubmitter.SubmitContext ctx = context("sqlserver", source, "kafka", kafkaTarget(),
                List.of(table("orders"), table("users")));

        String sql = FlinkSqlBuilder.build(ctx, new IntegrationClusterProperties.Flink(), 2, 1);
        assertTrue(sql.contains("'connector' = 'sqlserver-cdc'"), sql);
        assertTrue(sql.contains("'database-name' = 'inventory'"), sql);
        assertTrue(sql.contains("'table-name' = 'dbo.orders'"), sql);
        assertTrue(sql.contains("'table-name' = 'dbo.users'"), sql);
        assertTrue(sql.contains("'scan.startup.mode' = 'latest-offset'"), sql);
        assertTrue(sql.contains("'server-time-zone' = 'UTC'"), sql);
        assertFalse(sql.contains("'server-id'"), sql);
    }

    @Test
    void db2CdcUsesConfiguredSchemaForPlainTable() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("host", "10.0.0.10");
        source.put("port", "50000");
        source.put("database", "TESTDB");
        source.put("schema", "db2inst1");
        source.put("username", "db2inst1");
        source.put("password", "secret");
        DataIntegrationSubmitter.SubmitContext ctx = context("db2", source, "kafka", kafkaTarget(),
                List.of(table("PRODUCTS")));

        String sql = FlinkSqlBuilder.build(ctx, new IntegrationClusterProperties.Flink(), 1, 1);
        assertTrue(sql.contains("'connector' = 'db2-cdc'"), sql);
        assertTrue(sql.contains("'table-name' = 'DB2INST1.PRODUCTS'"), sql);
        assertFalse(sql.contains("'server-id'"), sql);
    }

    @Test
    void oceanBaseCdcAllocatesServerIds() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("host", "10.0.0.11");
        source.put("port", "2881");
        source.put("database", "ods");
        source.put("username", "root@test");
        source.put("password", "secret");
        source.put("serverTimezone", "Asia/Shanghai");
        DataIntegrationSubmitter.SubmitContext ctx = context("oceanbase", source, "kafka", kafkaTarget(),
                List.of(table("orders"), table("users")));

        String sql = FlinkSqlBuilder.build(ctx, new IntegrationClusterProperties.Flink(), 2, 1);
        assertEquals(2, count(sql, "'connector' = 'oceanbase-cdc'"));
        assertTrue(sql.contains("'server-id' = '5410'"), sql);
        assertTrue(sql.contains("'server-id' = '5411'"), sql);
        assertTrue(sql.contains("'server-time-zone' = 'Asia/Shanghai'"), sql);
        assertFalse(sql.contains("'scan.incremental.snapshot.parallelism'"), sql);
    }

    @Test
    void dorisSinkUsesDatabaseAndLabelPrefix() {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("fenodes", "127.0.0.1:8030");
        target.put("database", "dwd");
        target.put("username", "root");
        target.put("password", "123456");
        DataIntegrationSubmitter.SubmitContext ctx = context("mysql", mysqlSource(), "doris", target,
                List.of(table("orders")));

        String sql = FlinkSqlBuilder.build(ctx, new IntegrationClusterProperties.Flink(), 2, 3);
        assertTrue(sql.contains("'connector' = 'doris'"), sql);
        assertTrue(sql.contains("'fenodes' = '127.0.0.1:8030'"), sql);
        assertTrue(sql.contains("'table.identifier' = 'dwd.orders'"), sql);
        assertTrue(sql.contains("'sink.enable-delete' = 'true'"), sql);
        assertTrue(sql.contains("'sink.label-prefix' = 'df_multi-sync_orders'"), sql);
        assertTrue(sql.contains("'sink.parallelism' = '3'"), sql);
    }

    @Test
    void starRocksSinkUsesDatabaseAndTable() {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("jdbcUrl", "jdbc:mysql://127.0.0.1:9030");
        target.put("loadUrl", "http://127.0.0.1:8030");
        target.put("database", "dwd");
        target.put("username", "root");
        target.put("password", "123456");
        DataIntegrationSubmitter.SubmitContext ctx = context("mysql", mysqlSource(), "starrocks", target,
                List.of(table("orders")));

        String sql = FlinkSqlBuilder.build(ctx, new IntegrationClusterProperties.Flink(), 2, 2);
        assertTrue(sql.contains("'connector' = 'starrocks'"), sql);
        assertTrue(sql.contains("'jdbc-url' = 'jdbc:mysql://127.0.0.1:9030'"), sql);
        assertTrue(sql.contains("'load-url' = 'http://127.0.0.1:8030'"), sql);
        assertTrue(sql.contains("'database-name' = 'dwd'"), sql);
        assertTrue(sql.contains("'table-name' = 'orders'"), sql);
    }

    @Test
    void mongoCdcUsesHostsDatabaseAndCollection() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("hosts", "10.0.0.12:27017,10.0.0.13:27017");
        source.put("database", "order_db");
        source.put("username", "root");
        source.put("password", "secret");
        source.put("connectionOptions", "replicaSet=rs0&authSource=admin");
        source.put("startupMode", "timestamp");
        source.put("startupTimestamp", "1700000000000");
        DataIntegrationSubmitter.SubmitContext ctx = context("mongodb", source, "kafka", kafkaTarget(),
                List.of(table("orders")));

        String sql = FlinkSqlBuilder.build(ctx, new IntegrationClusterProperties.Flink(), 2, 1);
        System.out.println("===== MongoDB CDC SQL =====\n" + sql);

        assertTrue(sql.contains("'connector' = 'mongodb-cdc'"), sql);
        assertTrue(sql.contains("'hosts' = '10.0.0.12:27017,10.0.0.13:27017'"), sql);
        assertTrue(sql.contains("'database' = 'order_db'"), sql);
        assertTrue(sql.contains("'collection' = 'orders'"), sql);
        assertTrue(sql.contains("'connection.options' = 'replicaSet=rs0&authSource=admin'"), sql);
        assertTrue(sql.contains("'scan.startup.mode' = 'timestamp'"), sql);
        assertTrue(sql.contains("'scan.startup.timestamp-millis' = '1700000000000'"), sql);
        assertFalse(sql.contains("'hostname'"), sql);
        assertFalse(sql.contains("'table-name'"), sql);
        assertFalse(sql.contains("'server-id'"), sql);
    }

    @Test
    void tidbCdcUsesPdAddressesAndSqlEndpointForMetadata() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("pdAddresses", "10.0.0.14:2379");
        source.put("host", "10.0.0.14");
        source.put("port", "4000");
        source.put("database", "order_db");
        source.put("username", "root");
        source.put("password", "secret");
        source.put("startupMode", "latest-offset");
        DataIntegrationSubmitter.SubmitContext ctx = context("tidb", source, "kafka", kafkaTarget(),
                List.of(table("orders"), table("users")));

        String sql = FlinkSqlBuilder.build(ctx, new IntegrationClusterProperties.Flink(), 2, 1);
        assertTrue(sql.contains("'connector' = 'tidb-cdc'"), sql);
        assertTrue(sql.contains("'pd-addresses' = '10.0.0.14:2379'"), sql);
        assertTrue(sql.contains("'database-name' = 'order_db'"), sql);
        assertTrue(sql.contains("'table-name' = 'orders'"), sql);
        assertTrue(sql.contains("'scan.startup.mode' = 'latest-offset'"), sql);
        // tidb-cdc 不使用 hostname/port/username/password，SQL 端点仅用于平台读取字段
        assertFalse(sql.contains("'hostname'"), sql);
        assertFalse(sql.contains("'username'"), sql);
        assertFalse(sql.contains("'server-id'"), sql);
    }

    @Test
    void tidbRejectsTimestampStartup() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("pdAddresses", "10.0.0.14:2379");
        source.put("host", "10.0.0.14");
        source.put("database", "order_db");
        source.put("username", "root");
        source.put("password", "secret");
        source.put("startupMode", "timestamp");
        source.put("startupTimestamp", "1700000000000");
        DataIntegrationSubmitter.SubmitContext ctx = context("tidb", source, "kafka", kafkaTarget(),
                List.of(table("orders")));

        BusinessException error = assertThrows(BusinessException.class,
                () -> FlinkSqlBuilder.build(ctx, new IntegrationClusterProperties.Flink(), 1, 1));
        assertTrue(error.getMessage().contains("不支持 timestamp"), error.getMessage());
    }

    @Test
    void mongoSamplesInferColumnsAndPrimaryKey() {
        List<org.bson.Document> samples = List.of(
                new org.bson.Document("_id", new org.bson.types.ObjectId())
                        .append("name", "alpha")
                        .append("amount", 12.5)
                        .append("created", new java.util.Date()),
                new org.bson.Document("_id", new org.bson.types.ObjectId())
                        .append("name", "beta")
                        .append("amount", 13)
                        .append("active", true));

        List<ColumnDef> columns = MongoSchemaSampler.inferColumns(samples, "orders");
        assertEquals("_id", columns.get(0).getName());
        assertTrue(columns.get(0).isPrimaryKey());
        assertEquals("STRING", columns.get(0).getFlinkType());
        // amount 在不同样本里是 Double / Int32，类型冲突退化为 STRING
        ColumnDef amount = columns.stream().filter(c -> "amount".equals(c.getName())).findFirst().orElseThrow();
        assertEquals("STRING", amount.getFlinkType());
        ColumnDef created = columns.stream().filter(c -> "created".equals(c.getName())).findFirst().orElseThrow();
        assertEquals("TIMESTAMP(3)", created.getFlinkType());
        ColumnDef active = columns.stream().filter(c -> "active".equals(c.getName())).findFirst().orElseThrow();
        assertEquals("BOOLEAN", active.getFlinkType());
    }

    @Test
    void mongoNestedFieldIsRejected() {
        List<org.bson.Document> samples = List.of(new org.bson.Document("_id", "1")
                .append("profile", new org.bson.Document("city", "hz")));
        BusinessException error = assertThrows(BusinessException.class,
                () -> MongoSchemaSampler.inferColumns(samples, "users"));
        assertTrue(error.getMessage().contains("嵌套文档或数组"), error.getMessage());
    }

    @Test
    void newSourcesParseLegacyJdbcUrls() {
        Map<String, Object> oracle = new LinkedHashMap<>();
        oracle.put("url", "jdbc:oracle:thin:@//db.local:1521/ORCLCDB");
        Map<String, Object> oracleParsed = SourceConnection.normalize(DataSourceKind.ORACLE, oracle);
        assertEquals("db.local", oracleParsed.get("host"));
        assertEquals("1521", oracleParsed.get("port"));
        assertEquals("ORCLCDB", oracleParsed.get("database"));
        assertFalse(oracleParsed.containsKey("url"));

        Map<String, Object> sqlserver = new LinkedHashMap<>();
        sqlserver.put("url", "jdbc:sqlserver://db.local:1433;databaseName=inventory;encrypt=false");
        Map<String, Object> sqlserverParsed = SourceConnection.normalize(DataSourceKind.SQLSERVER, sqlserver);
        assertEquals("db.local", sqlserverParsed.get("host"));
        assertEquals("1433", sqlserverParsed.get("port"));
        assertEquals("inventory", sqlserverParsed.get("database"));

        Map<String, Object> db2 = new LinkedHashMap<>();
        db2.put("url", "jdbc:db2://db.local:50000/TESTDB");
        Map<String, Object> db2Parsed = SourceConnection.normalize(DataSourceKind.DB2, db2);
        assertEquals("db.local", db2Parsed.get("host"));
        assertEquals("50000", db2Parsed.get("port"));
        assertEquals("TESTDB", db2Parsed.get("database"));
    }

    @Test
    void legacyJdbcUrlIsParsedIntoExplicitCdcFields() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("url", "jdbc:mysql://127.0.0.1:3307/legacy_db?useSSL=false");
        source.put("username", "root");
        source.put("password", "123456");
        DataIntegrationSubmitter.SubmitContext ctx = context("mysql", source, "kafka", kafkaTarget(),
                List.of(table("orders")));

        String sql = FlinkSqlBuilder.build(ctx, new IntegrationClusterProperties.Flink(), 2, 2);

        assertTrue(sql.contains("'hostname' = '127.0.0.1'"), sql);
        assertTrue(sql.contains("'port' = '3307'"), sql);
        assertTrue(sql.contains("'database-name' = 'legacy_db'"), sql);
        assertFalse(sql.contains("jdbc:mysql://"), "生成侧不应再出现 JDBC URL");

        Map<String, Object> normalized = SourceConnection.normalize(DataSourceKind.MYSQL, source);
        assertFalse(normalized.containsKey("url"), "解析成功后应删除历史 url");
    }

    @Test
    void postgresLegacyUrlKeepsCurrentSchema() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("url", "jdbc:postgresql://db.local:5432/ods?currentSchema=dwd");
        Map<String, Object> normalized = SourceConnection.normalize(DataSourceKind.POSTGRESQL, source);

        assertEquals("db.local", normalized.get("host"));
        assertEquals("5432", normalized.get("port"));
        assertEquals("ods", normalized.get("database"));
        assertEquals("dwd", normalized.get("schema"));
    }

    @Test
    void unparseableLegacyUrlReportsReadableError() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("url", "mysql://no-scheme/ods");
        DataIntegrationSubmitter.SubmitContext ctx = context("mysql", source, "kafka", kafkaTarget(),
                List.of(table("orders")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> FlinkSqlBuilder.build(ctx, new IntegrationClusterProperties.Flink(), 2, 2));
        assertTrue(ex.getMessage().contains("host / port / database"), ex.getMessage());
    }

    @Test
    void cdcServerIdRangeAndCrossDatabaseAreValidated() {
        Map<String, Object> source = mysqlSource();
        source.put("serverId", "2147483647");
        DataIntegrationSubmitter.SubmitContext overflow = context("mysql", source, "kafka", kafkaTarget(),
                List.of(table("orders"), table("users")));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> FlinkSqlBuilder.build(overflow, new IntegrationClusterProperties.Flink(), 2, 2));
        assertTrue(ex.getMessage().contains("超出"), ex.getMessage());

        DataIntegrationSubmitter.SubmitContext crossDb = context("mysql", mysqlSource(), "kafka", kafkaTarget(),
                List.of(table("dws.orders")));
        BusinessException cross = assertThrows(BusinessException.class,
                () -> FlinkSqlBuilder.build(crossDb, new IntegrationClusterProperties.Flink(), 2, 2));
        assertTrue(cross.getMessage().contains("不一致"), cross.getMessage());
    }

    @Test
    void explicitServerIdBaseIsHonoured() {
        Map<String, Object> source = mysqlSource();
        source.put("serverId", "9000");
        DataIntegrationSubmitter.SubmitContext ctx = context("mysql", source, "kafka", kafkaTarget(),
                List.of(table("orders"), table("users")));

        String sql = FlinkSqlBuilder.build(ctx, new IntegrationClusterProperties.Flink(), 2, 2);
        assertTrue(sql.contains("'server-id' = '9000'"));
        assertTrue(sql.contains("'server-id' = '9001'"));
    }

    @Test
    void startupTimestampModeIsEmitted() {
        Map<String, Object> source = mysqlSource();
        source.put("startupMode", "timestamp");
        source.put("startupTimestamp", "1700000000000");
        DataIntegrationSubmitter.SubmitContext ctx = context("mysql", source, "kafka", kafkaTarget(),
                List.of(table("orders")));

        String sql = FlinkSqlBuilder.build(ctx, new IntegrationClusterProperties.Flink(), 2, 2);
        assertTrue(sql.contains("'scan.startup.mode' = 'timestamp'"));
        assertTrue(sql.contains("'scan.startup.timestamp-millis' = '1700000000000'"));

        source.remove("startupTimestamp");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> FlinkSqlBuilder.build(context("mysql", source, "kafka", kafkaTarget(),
                        List.of(table("orders"))), new IntegrationClusterProperties.Flink(), 2, 2));
        assertTrue(ex.getMessage().contains("时间戳"), ex.getMessage());
    }

    @Test
    void flinkHiveUsesCatalogTableNameDerivedFromTemplate() {
        Map<String, Object> hive = new LinkedHashMap<>();
        hive.put("database", "dwd");
        hive.put("nameTemplate", "ods_{table}");
        DataIntegrationSubmitter.SubmitContext ctx = context("mysql", mysqlSource(), "hive", hive,
                List.of(table("pay", "etl_time")));

        String sql = FlinkSqlBuilder.build(ctx, new IntegrationClusterProperties.Flink(), 2, 3);
        System.out.println("===== Flink Hive SQL =====\n" + sql);

        assertTrue(sql.contains("CREATE CATALOG IF NOT EXISTS `hive`"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `hive`.`dwd`.`ods_pay`"));
        assertTrue(sql.contains("INSERT INTO `hive`.`dwd`.`ods_pay`"));
        assertTrue(sql.contains("'sink.parallelism' = '3'"));
        assertFalse(sourceBlock(sql, "src_0").contains("`etl_time`"));
    }

    @Test
    void hdfsPathIsDerivedFromBasePathAndTemplate() {
        DataIntegrationSubmitter.SubmitContext ctx = context("mysql", mysqlSource(), "hdfs",
                Map.of("path", "hdfs://nn:8020/dwd"),
                List.of(table("orders"), table("users")));

        String sql = FlinkSqlBuilder.build(ctx, new IntegrationClusterProperties.Flink(), 2, 2);
        System.out.println("===== Flink HDFS SQL =====\n" + sql);

        assertTrue(sql.contains("'path' = 'hdfs://nn:8020/dwd/orders'"), sql);
        assertTrue(sql.contains("'path' = 'hdfs://nn:8020/dwd/users'"), sql);
    }

    @Test
    void primaryKeyIsRenderedForCdcSourceAndJdbcSink() {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("url", "jdbc:mysql://127.0.0.1:3306/dw");
        target.put("username", "root");
        target.put("password", "123456");
        TableSpec withKey = new TableSpec("orders", List.of(), 0, List.of(
                new ColumnDef("id", "BIGINT", true),
                new ColumnDef("name", "STRING")));
        DataIntegrationSubmitter.SubmitContext ctx = context("mysql", mysqlSource(), "mysql", target,
                List.of(withKey));

        String sql = FlinkSqlBuilder.build(ctx, new IntegrationClusterProperties.Flink(), 1, 1);
        // CDC 源 + JDBC Sink 都要声明主键，否则 Sink 无法处理 -U/+D 记录
        assertEquals(2, count(sql, "PRIMARY KEY (`id`) NOT ENFORCED"), sql);
    }

    @Test
    void mysqlJdbcSinkKeepsSchemaPrefix() {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("url", "jdbc:mysql://127.0.0.1:3306/dw");
        target.put("username", "root");
        target.put("password", "123456");
        target.put("schema", "dwd");
        DataIntegrationSubmitter.SubmitContext ctx = context("mysql", mysqlSource(), "mysql", target,
                List.of(table("orders")));

        String sql = FlinkSqlBuilder.build(ctx, new IntegrationClusterProperties.Flink(), 2, 2);
        assertTrue(sql.contains("'connector' = 'jdbc'"));
        assertTrue(sql.contains("'table-name' = 'dwd.orders'"), sql);
    }

    @Test
    void elasticsearchIndexUsesDbTableTemplate() {
        DataIntegrationSubmitter.SubmitContext ctx = context("mysql", mysqlSource(), "elasticsearch",
                Map.of("hosts", "http://127.0.0.1:9200", "nameTemplate", "{db}_{table}"),
                List.of(table("orders")));

        String sql = FlinkSqlBuilder.build(ctx, new IntegrationClusterProperties.Flink(), 2, 2);
        assertTrue(sql.contains("'index' = 'ods_orders'"), sql);
    }

    @Test
    void duplicateDerivedIdentifierIsRejected() {
        DataIntegrationSubmitter.SubmitContext ctx = context("mysql", mysqlSource(), "kafka", kafkaTarget(),
                List.of(table("orders"), table("orders")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> FlinkSqlBuilder.build(ctx, new IntegrationClusterProperties.Flink(), 2, 2));
        assertTrue(ex.getMessage().contains("目标标识相同"), ex.getMessage());
    }

    @Test
    void invalidOrUnknownTemplateIsRejected() {
        DataIntegrationSubmitter.SubmitContext ctx = context("mysql", mysqlSource(), "kafka",
                Map.of("bootstrapServers", "127.0.0.1:9092", "nameTemplate", "{table}-{date}"),
                List.of(table("orders")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> FlinkSqlBuilder.build(ctx, new IntegrationClusterProperties.Flink(), 2, 2));
        assertTrue(ex.getMessage().contains("不支持的占位符"), ex.getMessage());

        BusinessException missing = assertThrows(BusinessException.class,
                () -> TargetNaming.checkTemplate("dwd_order", "目标端配置"));
        assertTrue(missing.getMessage().contains("{table}"), missing.getMessage());
    }

    @Test
    void templateRequiresDatabaseWhenUsed() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> TargetNaming.render("{db}_{table}", "orders"));
        assertTrue(ex.getMessage().contains("库名"), ex.getMessage());
    }

    @Test
    void nonJdbcSourceIsRejected() {
        Map<String, Object> hdfs = new LinkedHashMap<>();
        hdfs.put("path", "hdfs://nn:8020/ods/input");
        DataIntegrationSubmitter.SubmitContext ctx = context("hdfs", hdfs, "kafka", kafkaTarget(),
                List.of(table("a")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> FlinkSqlBuilder.build(ctx, new IntegrationClusterProperties.Flink(), 2, 2));
        assertTrue(ex.getMessage().contains("MySQL / PostgreSQL"), ex.getMessage());
    }

    @Test
    void seatunnelClusterIsNoLongerSelectable() {
        assertNull(ClusterType.fromCode("seatunnel"), "SeaTunnel 已移除，不能再被路由");
        assertEquals(1, ClusterType.values().length);
        assertEquals("flink-standalone", ClusterType.FLINK_STANDALONE.getCode());
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, Object> mysqlSource() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("host", "127.0.0.1");
        config.put("port", "3306");
        config.put("database", "ods");
        config.put("username", "root");
        config.put("password", "123456");
        // 历史数据里可能残留字段列表与表名，生成侧应忽略它们
        config.put("columns", "legacy_only STRING");
        config.put("table", "legacy.table");
        return config;
    }

    private static Map<String, Object> kafkaTarget() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("bootstrapServers", "127.0.0.1:9092");
        return config;
    }

    private static TableSpec table(String sourceTable, String... excludes) {
        return new TableSpec(sourceTable, List.of(excludes), 0, COLUMNS);
    }

    private static DataIntegrationSubmitter.SubmitContext context(String sourceType,
                                                                 Map<String, Object> sourceConfig,
                                                                 String targetType,
                                                                 Map<String, Object> targetConfig,
                                                                 List<TableSpec> tables) {
        return new DataIntegrationSubmitter.SubmitContext(
                1L, "多表同步", "multi-sync", "BATCH",
                sourceType, sourceConfig, targetType, targetConfig,
                tables, Map.of(), 2, 2);
    }

    private static int count(String text, String token) {
        int count = 0;
        int index = text.indexOf(token);
        while (index >= 0) {
            count++;
            index = text.indexOf(token, index + token.length());
        }
        return count;
    }

    /** 取某个 CREATE TABLE 块（到下一个 CREATE 或 INSERT 为止） */
    private static String sourceBlock(String sql, String name) {
        int start = sql.indexOf("CREATE TABLE `" + name + "`");
        if (start < 0) {
            return "";
        }
        int end = sql.length();
        for (String marker : List.of("CREATE TABLE", "INSERT INTO")) {
            int next = sql.indexOf(marker, start + 10);
            if (next > 0 && next < end) {
                end = next;
            }
        }
        return sql.substring(start, end);
    }
}
