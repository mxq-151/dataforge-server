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
