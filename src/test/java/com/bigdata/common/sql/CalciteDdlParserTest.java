package com.bigdata.common.sql;

import com.bigdata.modules.devops.enums.DatasourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;



import static org.junit.jupiter.api.Assertions.*;

/**
 * CalciteDdlParser 单元测试
 * <p>
 * 覆盖 Hive、Doris/StarRocks、ClickHouse 四种引擎的 DDL 解析，
 * 验证库名、表名、表注释、存储格式、分隔符、压缩算法、存储路径、
 * 是否分区表、是否外部表、主键等信息的提取。
 */
@DisplayName("CalciteDdlParser DDL解析器测试")
class CalciteDdlParserTest {

    // ==================== Hive DDL 测试 ====================

    @Nested
    @DisplayName("Hive CREATE TABLE 解析")
    class HiveCreateTableTests {

        @Test
        @DisplayName("Hive内部表 - 基础字段提取")
        void testHiveManagedTable_BasicFields() {
            String sql = "CREATE TABLE mydb.user_info (\n" +
                    "  id BIGINT COMMENT '用户ID',\n" +
                    "  name STRING COMMENT '姓名',\n" +
                    "  age INT COMMENT '年龄',\n" +
                    "  create_time TIMESTAMP COMMENT '创建时间'\n" +
                    ") COMMENT '用户信息表'\n" +
                    "STORED AS ORC\n" +
                    "TBLPROPERTIES ('orc.compress'='ZLIB')";

            CalciteDdlParser.DdlInfo info = CalciteDdlParser.detect(sql, DatasourceType.HIVE);

            assertNotNull(info, "应识别为DDL");
            assertEquals("CREATE", info.getDbType());
            assertEquals("mydb", info.getDatabaseName());
            assertEquals("user_info", info.getTableName());
            assertEquals(DatasourceType.HIVE, info.getEngineType());

            CalciteDdlParser.TableAttributes attrs = info.getTableAttributes();
            assertNotNull(attrs);
            assertEquals("用户信息表", attrs.tableComment);
            assertEquals("ORC", attrs.storageFormat);
            assertEquals("ZLIB", attrs.compressionCodec);
            assertEquals("MANAGED_TABLE", attrs.tableType);
            assertFalse(attrs.isExternal);
            assertFalse(attrs.isPartitioned);
        }

        @Test
        @DisplayName("Hive外部表 - 含LOCATION和PARTITIONED BY")
        void testHiveExternalTable_WithPartitionAndLocation() {
            String sql = "CREATE EXTERNAL TABLE sales_db.order_sales (\n" +
                    "  order_id BIGINT COMMENT '订单ID',\n" +
                    "  product_name STRING COMMENT '商品名称',\n" +
                    "  amount DECIMAL(10,2) COMMENT '金额',\n" +
                    "  order_date STRING COMMENT '订单日期'\n" +
                    ")\n" +
                    "COMMENT '销售订单表'\n" +
                    "PARTITIONED BY (dt STRING COMMENT '分区日期', region STRING COMMENT '地区')\n" +
                    "ROW FORMAT DELIMITED FIELDS TERMINATED BY '\\t'\n" +
                    "STORED AS TEXTFILE\n" +
                    "LOCATION '/user/hive/warehouse/sales_db/order_sales'\n" +
                    "TBLPROPERTIES ('comment'='销售订单外部表', 'parquet.compression'='SNAPPY')";

            CalciteDdlParser.DdlInfo info = CalciteDdlParser.detect(sql, DatasourceType.HIVE);

            assertNotNull(info);
            assertEquals("CREATE", info.getDbType());
            assertEquals("sales_db", info.getDatabaseName());
            assertEquals("order_sales", info.getTableName());

            CalciteDdlParser.TableAttributes attrs = info.getTableAttributes();
            assertNotNull(attrs);
            assertTrue(attrs.isExternal, "应为外部表");
            assertEquals("EXTERNAL_TABLE", attrs.tableType);
            assertEquals("销售订单外部表", attrs.tableComment);
            assertEquals("TEXTFILE", attrs.storageFormat);
            assertEquals("\\t", attrs.fieldDelimiter);
            assertEquals("/user/hive/warehouse/sales_db/order_sales", attrs.location);
            assertEquals("SNAPPY", attrs.compressionCodec);
            assertTrue(attrs.isPartitioned, "应为分区表");
            assertEquals("dt STRING COMMENT '分区日期', region STRING COMMENT '地区'", attrs.partitionColumns);
            assertNotNull(attrs.tblProperties);
            assertEquals("销售订单外部表", attrs.tblProperties.get("comment"));
        }

        @Test
        @DisplayName("Hive表 - ORC格式 + 无库名")
        void testHiveTable_NoDatabaseName() {
            String sql = "CREATE TABLE employee (\n" +
                    "  emp_id BIGINT,\n" +
                    "  emp_name STRING,\n" +
                    "  salary DOUBLE\n" +
                    ") STORED AS ORC";

            CalciteDdlParser.DdlInfo info = CalciteDdlParser.detect(sql, DatasourceType.HIVE);

            assertNotNull(info);
            assertEquals("CREATE", info.getDbType());
            assertNull(info.getDatabaseName(), "无库名时应为null");
            assertEquals("employee", info.getTableName());

            CalciteDdlParser.TableAttributes attrs = info.getTableAttributes();
            assertEquals("ORC", attrs.storageFormat);
            assertEquals("MANAGED_TABLE", attrs.tableType);
        }

        @Test
        @DisplayName("Hive表 - PARQUET格式 + 压缩 + 分区")
        void testHiveTable_ParquetWithPartition() {
            String sql = "CREATE TABLE analytics_db.user_events (\n" +
                    "  event_id STRING,\n" +
                    "  user_id BIGINT,\n" +
                    "  event_type STRING,\n" +
                    "  event_time TIMESTAMP\n" +
                    ")\n" +
                    "PARTITIONED BY (event_date STRING)\n" +
                    "STORED AS PARQUET\n" +
                    "LOCATION 'hdfs://namenode:8020/warehouse/analytics/user_events'\n" +
                    "TBLPROPERTIES ('parquet.compression'='SNAPPY', 'comment'='用户事件日志表')";

            CalciteDdlParser.DdlInfo info = CalciteDdlParser.detect(sql, DatasourceType.HIVE);

            assertNotNull(info);
            assertEquals("analytics_db", info.getDatabaseName());
            assertEquals("user_events", info.getTableName());

            CalciteDdlParser.TableAttributes attrs = info.getTableAttributes();
            assertEquals("PARQUET", attrs.storageFormat);
            assertEquals("SNAPPY", attrs.compressionCodec);
            assertTrue(attrs.isPartitioned);
            assertEquals("event_date STRING", attrs.partitionColumns);
            assertEquals("hdfs://namenode:8020/warehouse/analytics/user_events", attrs.location);
            assertEquals("用户事件日志表", attrs.tableComment);
        }

        @Test
        @DisplayName("Hive表 - IF NOT EXISTS 语法")
        void testHiveTable_IfNotExists() {
            String sql = "CREATE TABLE IF NOT EXISTS test_db.new_table (\n" +
                    "  id BIGINT,\n" +
                    "  name STRING\n" +
                    ") STORED AS ORC";

            CalciteDdlParser.DdlInfo info = CalciteDdlParser.detect(sql, DatasourceType.HIVE);

            assertNotNull(info);
            assertEquals("test_db", info.getDatabaseName());
            assertEquals("new_table", info.getTableName());
        }
    }

    @Nested
    @DisplayName("Hive DROP / ALTER / TRUNCATE 解析")
    class HiveDropAlterTruncateTests {

        @Test
        @DisplayName("DROP TABLE")
        void testDropTable() {
            String sql = "DROP TABLE mydb.user_info";

            CalciteDdlParser.DdlInfo info = CalciteDdlParser.detect(sql, DatasourceType.HIVE);

            assertNotNull(info);
            assertEquals("DROP", info.getDbType());
            assertEquals("mydb", info.getDatabaseName());
            assertEquals("user_info", info.getTableName());
        }

        @Test
        @DisplayName("DROP TABLE IF EXISTS")
        void testDropTableIfExists() {
            String sql = "DROP TABLE IF EXISTS test_db.temp_table";

            CalciteDdlParser.DdlInfo info = CalciteDdlParser.detect(sql, DatasourceType.HIVE);

            assertNotNull(info);
            assertEquals("DROP", info.getDbType());
            assertEquals("test_db", info.getDatabaseName());
            assertEquals("temp_table", info.getTableName());
        }

        @Test
        @DisplayName("ALTER TABLE")
        void testAlterTable() {
            String sql = "ALTER TABLE mydb.user_info ADD COLUMN phone STRING COMMENT '手机号'";

            CalciteDdlParser.DdlInfo info = CalciteDdlParser.detect(sql, DatasourceType.HIVE);

            assertNotNull(info);
            assertEquals("ALTER", info.getDbType());
            assertEquals("mydb", info.getDatabaseName());
            assertEquals("user_info", info.getTableName());
        }

        @Test
        @DisplayName("TRUNCATE TABLE")
        void testTruncateTable() {
            String sql = "TRUNCATE TABLE mydb.temp_log";

            CalciteDdlParser.DdlInfo info = CalciteDdlParser.detect(sql, DatasourceType.HIVE);

            assertNotNull(info);
            assertEquals("TRUNCATE", info.getDbType());
            assertEquals("mydb", info.getDatabaseName());
            assertEquals("temp_log", info.getTableName());
        }
    }

    @Nested
    @DisplayName("非DDL语句检测")
    class NonDdlTests {

        @Test
        @DisplayName("SELECT语句 - 返回null")
        void testSelectStatement_ReturnsNull() {
            assertNull(CalciteDdlParser.detect("SELECT * FROM users"));
            assertNull(CalciteDdlParser.detect("SELECT * FROM users", DatasourceType.HIVE));
        }

        @Test
        @DisplayName("INSERT语句 - 返回null")
        void testInsertStatement_ReturnsNull() {
            assertNull(CalciteDdlParser.detect("INSERT INTO users VALUES (1, 'test')"));
        }

        @Test
        @DisplayName("UPDATE语句 - 返回null")
        void testUpdateStatement_ReturnsNull() {
            assertNull(CalciteDdlParser.detect("UPDATE users SET name='test' WHERE id=1"));
        }

        @Test
        @DisplayName("DELETE语句 - 返回null")
        void testDeleteStatement_ReturnsNull() {
            assertNull(CalciteDdlParser.detect("DELETE FROM users WHERE id=1"));
        }

        @Test
        @DisplayName("null / 空字符串 - 返回null")
        void testNullOrEmpty_ReturnsNull() {
            assertNull(CalciteDdlParser.detect(null));
            assertNull(CalciteDdlParser.detect(""));
            assertNull(CalciteDdlParser.detect("   "));
        }

        @Test
        @DisplayName("默认detect(String) - 使用HIVE模式")
        void testDefaultDetect_UsesHiveMode() {
            String sql = "CREATE TABLE test_db.t1 (id BIGINT) STORED AS ORC";
            CalciteDdlParser.DdlInfo info = CalciteDdlParser.detect(sql);

            assertNotNull(info);
            assertEquals(DatasourceType.HIVE, info.getEngineType());
            assertEquals("test_db", info.getDatabaseName());
            assertEquals("t1", info.getTableName());
        }
    }

    // ==================== Doris DDL 测试 ====================

    @Nested
    @DisplayName("Doris CREATE TABLE 解析")
    class DorisCreateTableTests {

        @Test
        @DisplayName("Doris DUPLICATE KEY模型 - 基础字段")
        void testDorisDuplicateKeyModel() {
            String sql = "CREATE TABLE example_db.user_log (\n" +
                    "  user_id BIGINT NOT NULL COMMENT '用户ID',\n" +
                    "  event_date DATE NOT NULL COMMENT '事件日期',\n" +
                    "  event_type VARCHAR(50) COMMENT '事件类型',\n" +
                    "  event_count INT SUM DEFAULT '0' COMMENT '事件次数'\n" +
                    ")\n" +
                    "ENGINE = olap\n" +
                    "DUPLICATE KEY(user_id, event_date)\n" +
                    "COMMENT '用户行为日志表'\n" +
                    "DISTRIBUTED BY HASH(user_id) BUCKETS 32\n" +
                    "PROPERTIES (\n" +
                    "  'replication_num' = '3',\n" +
                    "  'storage_medium' = 'SSD',\n" +
                    "  'comment' = '用户行为日志表'\n" +
                    ")";

            CalciteDdlParser.DdlInfo info = CalciteDdlParser.detect(sql, DatasourceType.DORIS);

            assertNotNull(info);
            assertEquals("CREATE", info.getDbType());
            assertEquals("example_db", info.getDatabaseName());
            assertEquals("user_log", info.getTableName());
            assertEquals(DatasourceType.DORIS, info.getEngineType());

            CalciteDdlParser.TableAttributes attrs = info.getTableAttributes();
            assertNotNull(attrs);
            assertEquals("OLAP_TABLE", attrs.tableType);
            assertEquals("OLAP", attrs.engine);
            assertEquals("DUPLICATE_KEY", attrs.keyType);
            assertEquals("user_id, event_date", attrs.keyColumns);
            assertEquals("user_id", attrs.distributedHashColumns);
            assertEquals(32, attrs.buckets);
            assertEquals("用户行为日志表", attrs.tableComment);
            assertEquals("3", attrs.replicationNum);
            assertEquals("SSD", attrs.storageMedium);
            assertEquals("用户行为日志表", attrs.properties.get("comment"));
        }

        @Test
        @DisplayName("Doris AGGREGATE KEY模型")
        void testDorisAggregateKeyModel() {
            String sql = "CREATE TABLE metrics_db.site_metrics (\n" +
                    "  site_id BIGINT NOT NULL COMMENT '站点ID',\n" +
                    "  metric_date DATE NOT NULL COMMENT '日期',\n" +
                    "  pv BIGINT SUM DEFAULT '0' COMMENT 'PV',\n" +
                    "  uv BIGINT SUM DEFAULT '0' COMMENT 'UV'\n" +
                    ")\n" +
                    "ENGINE = olap\n" +
                    "AGGREGATE KEY(site_id, metric_date)\n" +
                    "COMMENT '站点指标汇总表'\n" +
                    "DISTRIBUTED BY HASH(site_id) BUCKETS 10\n" +
                    "PROPERTIES (\n" +
                    "  'replication_num' = '1'\n" +
                    ")";

            CalciteDdlParser.DdlInfo info = CalciteDdlParser.detect(sql, DatasourceType.DORIS);

            assertNotNull(info);
            assertEquals("metrics_db", info.getDatabaseName());
            assertEquals("site_metrics", info.getTableName());

            CalciteDdlParser.TableAttributes attrs = info.getTableAttributes();
            assertEquals("AGGREGATE_KEY", attrs.keyType);
            assertEquals("site_id, metric_date", attrs.keyColumns);
            assertEquals(10, attrs.buckets);
            assertEquals("站点指标汇总表", attrs.tableComment);
        }

        @Test
        @DisplayName("Doris UNIQUE KEY模型")
        void testDorisUniqueKeyModel() {
            String sql = "CREATE TABLE order_db.orders (\n" +
                    "  order_id BIGINT NOT NULL COMMENT '订单ID',\n" +
                    "  user_id BIGINT NOT NULL COMMENT '用户ID',\n" +
                    "  product_name VARCHAR(100) REPLACE COMMENT '商品名',\n" +
                    "  amount DECIMAL(10,2) REPLACE DEFAULT '0.00' COMMENT '金额'\n" +
                    ")\n" +
                    "ENGINE = olap\n" +
                    "UNIQUE KEY(order_id, user_id)\n" +
                    "COMMENT '订单表'\n" +
                    "DISTRIBUTED BY HASH(order_id) BUCKETS 16\n" +
                    "PROPERTIES (\n" +
                    "  'replication_num' = '3'\n" +
                    ")";

            CalciteDdlParser.DdlInfo info = CalciteDdlParser.detect(sql, DatasourceType.DORIS);

            assertNotNull(info);
            assertEquals("order_db", info.getDatabaseName());
            assertEquals("orders", info.getTableName());

            CalciteDdlParser.TableAttributes attrs = info.getTableAttributes();
            assertEquals("UNIQUE_KEY", attrs.keyType);
            assertEquals("order_id, user_id", attrs.keyColumns);
            assertEquals("order_id", attrs.distributedHashColumns);
            assertEquals(16, attrs.buckets);
        }
    }

    // ==================== StarRocks DDL 测试 ====================

    @Nested
    @DisplayName("StarRocks CREATE TABLE 解析")
    class StarRocksCreateTableTests {

        @Test
        @DisplayName("StarRocks PRIMARY KEY模型")
        void testStarRocksPrimaryKeyModel() {
            String sql = "CREATE TABLE star_db.user_profile (\n" +
                    "  user_id BIGINT NOT NULL COMMENT '用户ID',\n" +
                    "  user_name VARCHAR(100) COMMENT '用户名',\n" +
                    "  email VARCHAR(200) COMMENT '邮箱',\n" +
                    "  created_time DATETIME COMMENT '创建时间'\n" +
                    ")\n" +
                    "ENGINE = olap\n" +
                    "PRIMARY KEY(user_id)\n" +
                    "COMMENT '用户画像表'\n" +
                    "DISTRIBUTED BY HASH(user_id) BUCKETS 8\n" +
                    "PROPERTIES (\n" +
                    "  'replication_num' = '3'\n" +
                    ")";

            CalciteDdlParser.DdlInfo info = CalciteDdlParser.detect(sql, DatasourceType.STARROCKS);

            assertNotNull(info);
            assertEquals("star_db", info.getDatabaseName());
            assertEquals("user_profile", info.getTableName());
            assertEquals(DatasourceType.STARROCKS, info.getEngineType());

            CalciteDdlParser.TableAttributes attrs = info.getTableAttributes();
            assertEquals("PRIMARY_KEY", attrs.keyType);
            assertEquals("user_id", attrs.keyColumns);
            assertEquals("user_id", attrs.distributedHashColumns);
            assertEquals(8, attrs.buckets);
            assertEquals("用户画像表", attrs.tableComment);
            assertEquals("3", attrs.replicationNum);
        }

        @Test
        @DisplayName("StarRocks 无库名表")
        void testStarRocksTable_NoDatabase() {
            String sql = "CREATE TABLE simple_table (\n" +
                    "  id BIGINT NOT NULL,\n" +
                    "  name VARCHAR(50)\n" +
                    ")\n" +
                    "ENGINE = olap\n" +
                    "DUPLICATE KEY(id)\n" +
                    "DISTRIBUTED BY HASH(id) BUCKETS 4\n" +
                    "PROPERTIES ('replication_num' = '1')";

            CalciteDdlParser.DdlInfo info = CalciteDdlParser.detect(sql, DatasourceType.STARROCKS);

            assertNotNull(info);
            assertNull(info.getDatabaseName());
            assertEquals("simple_table", info.getTableName());

            CalciteDdlParser.TableAttributes attrs = info.getTableAttributes();
            assertEquals("DUPLICATE_KEY", attrs.keyType);
            assertEquals(4, attrs.buckets);
        }
    }

    // ==================== ClickHouse DDL 测试 ====================

    @Nested
    @DisplayName("ClickHouse CREATE TABLE 解析")
    class ClickHouseCreateTableTests {

        @Test
        @DisplayName("ClickHouse MergeTree - 完整字段")
        void testClickHouseMergeTree() {
            String sql = "CREATE TABLE ch_db.events (\n" +
                    "  event_id UUID COMMENT '事件ID',\n" +
                    "  event_time DateTime COMMENT '事件时间',\n" +
                    "  user_id UInt64 COMMENT '用户ID',\n" +
                    "  event_type String COMMENT '事件类型',\n" +
                    "  properties Map(String, String) COMMENT '属性'\n" +
                    ")\n" +
                    "ENGINE = MergeTree()\n" +
                    "PARTITION BY toYYYYMM(event_time)\n" +
                    "ORDER BY (user_id, event_time)\n" +
                    "PRIMARY KEY (user_id, event_time)\n" +
                    "SAMPLE BY user_id\n" +
                    "SETTINGS index_granularity = 8192";

            CalciteDdlParser.DdlInfo info = CalciteDdlParser.detect(sql, DatasourceType.CLICKHOUSE);

            assertNotNull(info);
            assertEquals("CREATE", info.getDbType());
            assertEquals("ch_db", info.getDatabaseName());
            assertEquals("events", info.getTableName());
            assertEquals(DatasourceType.CLICKHOUSE, info.getEngineType());

            CalciteDdlParser.TableAttributes attrs = info.getTableAttributes();
            assertNotNull(attrs);
            assertEquals("MERGE_TREE", attrs.tableType);
            assertEquals("MergeTree", attrs.engine);
            assertEquals("toYYYYMM(event_time)", attrs.partitionByExpr);
            assertEquals("user_id, event_time", attrs.orderByColumns);
            assertEquals("user_id, event_time", attrs.primaryKeyColumns);
            assertEquals("user_id", attrs.sampleByColumn);
            assertTrue(attrs.settings.contains("index_granularity = 8192"));
        }

        @Test
        @DisplayName("ClickHouse ReplacingMergeTree")
        void testClickHouseReplacingMergeTree() {
            String sql = "CREATE TABLE ch_db.products (\n" +
                    "  product_id UInt64,\n" +
                    "  product_name String,\n" +
                    "  price Decimal(10,2),\n" +
                    "  updated_at DateTime\n" +
                    ")\n" +
                    "ENGINE = ReplacingMergeTree(updated_at)\n" +
                    "ORDER BY product_id\n" +
                    "PARTITION BY toYYYYMM(updated_at)";

            CalciteDdlParser.DdlInfo info = CalciteDdlParser.detect(sql, DatasourceType.CLICKHOUSE);

            assertNotNull(info);
            assertEquals("ch_db", info.getDatabaseName());
            assertEquals("products", info.getTableName());

            CalciteDdlParser.TableAttributes attrs = info.getTableAttributes();
            assertEquals("ReplacingMergeTree", attrs.engine);
            assertEquals("updated_at", attrs.engineParams);
            assertEquals("product_id", attrs.orderByColumns);
            assertEquals("toYYYYMM(updated_at)", attrs.partitionByExpr);
        }

        @Test
        @DisplayName("ClickHouse ON CLUSTER 语法")
        void testClickHouseOnCluster() {
            String sql = "CREATE TABLE cluster_db.metrics ON CLUSTER default_cluster (\n" +
                    "  metric_name String,\n" +
                    "  value Float64,\n" +
                    "  timestamp DateTime\n" +
                    ")\n" +
                    "ENGINE = MergeTree()\n" +
                    "ORDER BY (metric_name, timestamp)";

            CalciteDdlParser.DdlInfo info = CalciteDdlParser.detect(sql, DatasourceType.CLICKHOUSE);

            assertNotNull(info);
            assertEquals("cluster_db", info.getDatabaseName());
            assertEquals("metrics", info.getTableName());

            CalciteDdlParser.TableAttributes attrs = info.getTableAttributes();
            assertEquals("MergeTree", attrs.engine);
            assertEquals("metric_name, timestamp", attrs.orderByColumns);
        }
    }

    // ==================== TBLPROPERTIES / PROPERTIES 提取测试 ====================

    @Nested
    @DisplayName("TBLPROPERTIES / PROPERTIES 键值对提取")
    class PropertiesExtractionTests {

        @Test
        @DisplayName("Hive TBLPROPERTIES - 多键值对")
        void testHiveTblProperties_MultipleKeys() {
            String sql = "CREATE TABLE test_db.multi_props (id BIGINT)\n" +
                    "STORED AS ORC\n" +
                    "TBLPROPERTIES (\n" +
                    "  'comment'='多属性测试表',\n" +
                    "  'orc.compress'='ZLIB',\n" +
                    "  'orc.bloom.filter.columns'='id',\n" +
                    "  'transactional'='true'\n" +
                    ")";

            CalciteDdlParser.DdlInfo info = CalciteDdlParser.detect(sql, DatasourceType.HIVE);
            CalciteDdlParser.TableAttributes attrs = info.getTableAttributes();

            assertNotNull(attrs.tblProperties);
            assertEquals(4, attrs.tblProperties.size());
            assertEquals("多属性测试表", attrs.tblProperties.get("comment"));
            assertEquals("ZLIB", attrs.tblProperties.get("orc.compress"));
            assertEquals("id", attrs.tblProperties.get("orc.bloom.filter.columns"));
            assertEquals("true", attrs.tblProperties.get("transactional"));
            assertEquals("ZLIB", attrs.compressionCodec);
        }

        @Test
        @DisplayName("Doris PROPERTIES - 多键值对")
        void testDorisProperties_MultipleKeys() {
            String sql = "CREATE TABLE test_db.prop_table (id BIGINT NOT NULL)\n" +
                    "ENGINE = olap\n" +
                    "DUPLICATE KEY(id)\n" +
                    "DISTRIBUTED BY HASH(id) BUCKETS 4\n" +
                    "PROPERTIES (\n" +
                    "  'replication_num' = '2',\n" +
                    "  'storage_medium' = 'HDD',\n" +
                    "  'comment' = '属性测试表',\n" +
                    "  'in_memory' = 'false'\n" +
                    ")";

            CalciteDdlParser.DdlInfo info = CalciteDdlParser.detect(sql, DatasourceType.DORIS);
            CalciteDdlParser.TableAttributes attrs = info.getTableAttributes();

            assertEquals(4, attrs.properties.size());
            assertEquals("2", attrs.replicationNum);
            assertEquals("HDD", attrs.storageMedium);
            assertEquals("属性测试表", attrs.tableComment);
            assertEquals("false", attrs.properties.get("in_memory"));
        }
    }

    // ==================== 向后兼容性测试 ====================

    @Nested
    @DisplayName("向后兼容性")
    class BackwardCompatibilityTests {

        @Test
        @DisplayName("extractHiveAttributes 返回 TableAttributes")
        void testExtractHiveAttributes_ReturnsTableAttributes() {
            String sql = "CREATE EXTERNAL TABLE test_db.ext_table (id BIGINT)\n" +
                    "STORED AS PARQUET LOCATION '/tmp/ext'";

            CalciteDdlParser.TableAttributes attrs = CalciteDdlParser.extractHiveAttributes(sql);

            assertNotNull(attrs);
            assertTrue(attrs.isExternal);
            assertEquals("PARQUET", attrs.storageFormat);
            assertEquals("/tmp/ext", attrs.location);
        }

        @Test
        @DisplayName("HiveTableAttributes 继承自 TableAttributes")
        void testHiveTableAttributes_ExtendsTableAttributes() {
            CalciteDdlParser.HiveTableAttributes hiveAttrs = new CalciteDdlParser.HiveTableAttributes();
            hiveAttrs.isExternal = true;
            hiveAttrs.storageFormat = "ORC";
            hiveAttrs.engineType = DatasourceType.HIVE;

            assertTrue(hiveAttrs.isExternal);
            assertEquals("ORC", hiveAttrs.storageFormat);
            assertEquals(DatasourceType.HIVE, hiveAttrs.engineType);
        }
    }

    // ==================== 边界条件测试 ====================

    @Nested
    @DisplayName("边界条件")
    class EdgeCaseTests {

        @Test
        @DisplayName("带反引号的库名和表名")
        void testBacktickQuotedNames() {
            String sql = "CREATE TABLE `my-db`.`user-info` (\n" +
                    "  `id` BIGINT,\n" +
                    "  `full-name` STRING\n" +
                    ") STORED AS ORC";

            CalciteDdlParser.DdlInfo info = CalciteDdlParser.detect(sql, DatasourceType.HIVE);

            assertNotNull(info);
            assertEquals("my-db", info.getDatabaseName());
            assertEquals("user-info", info.getTableName());
        }

        @Test
        @DisplayName("多行SQL + 换行符")
        void testMultilineSql() {
            String sql = "CREATE TABLE db1.large_table (\n" +
                    "  col1 STRING,\n" +
                    "  col2 BIGINT,\n" +
                    "  col3 DOUBLE,\n" +
                    "  col4 TIMESTAMP,\n" +
                    "  col5 DECIMAL(18,4),\n" +
                    "  col6 STRING,\n" +
                    "  col7 INT,\n" +
                    "  col8 BIGINT\n" +
                    ")\n" +
                    "PARTITIONED BY (dt STRING, hour STRING)\n" +
                    "STORED AS PARQUET\n" +
                    "TBLPROPERTIES ('parquet.compression'='GZIP')";

            CalciteDdlParser.DdlInfo info = CalciteDdlParser.detect(sql, DatasourceType.HIVE);

            assertNotNull(info);
            assertEquals("db1", info.getDatabaseName());
            assertEquals("large_table", info.getTableName());
            assertTrue(info.getTableAttributes().isPartitioned);
            assertEquals("GZIP", info.getTableAttributes().compressionCodec);
        }
    }
}