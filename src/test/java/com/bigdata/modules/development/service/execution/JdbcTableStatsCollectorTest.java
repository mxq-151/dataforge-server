package com.bigdata.modules.development.service.execution;

import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.metadata.entity.TableMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

/**
 * JdbcTableStatsCollector 单测：验证各引擎系统表查询的行映射与清洗逻辑。
 *
 * <p>使用 Mock JDBC 元数据验证「查到的值如何写入 TableMetadata」，
 * 不依赖真实数据库；SQL 本身由系统表口径保证。</p>
 */
@DisplayName("JdbcTableStatsCollector - 表统计采集行映射")
class JdbcTableStatsCollectorTest {

    private final JdbcTableStatsCollector collector =
            new JdbcTableStatsCollector(LoggerFactory.getLogger(JdbcTableStatsCollectorTest.class));

    private List<TableMetadata> singleTable(String name) {
        TableMetadata table = new TableMetadata();
        table.setTableName(name);
        table.setDatabaseName("dw");
        List<TableMetadata> tables = new ArrayList<>();
        tables.add(table);
        return tables;
    }

    @Test
    @DisplayName("MySQL：行数/大小/时间/压缩/分区表达式全部映射")
    void testFillMySql() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statsPs = mock(PreparedStatement.class);
        PreparedStatement partitionPs = mock(PreparedStatement.class);
        when(connection.prepareStatement(contains("information_schema.TABLES"))).thenReturn(statsPs);
        when(connection.prepareStatement(contains("information_schema.PARTITIONS"))).thenReturn(partitionPs);

        ResultSet statsRs = mock(ResultSet.class);
        when(statsPs.executeQuery()).thenReturn(statsRs);
        when(statsRs.next()).thenReturn(true, false);
        when(statsRs.getString("TABLE_NAME")).thenReturn("dwd_order");
        when(statsRs.getLong("DATA_LENGTH")).thenReturn(100L);
        when(statsRs.getLong("INDEX_LENGTH")).thenReturn(20L);
        Timestamp createTime = Timestamp.valueOf("2026-01-01 10:00:00");
        Timestamp updateTime = Timestamp.valueOf("2026-09-30 02:00:00");
        when(statsRs.getTimestamp("CREATE_TIME")).thenReturn(createTime);
        when(statsRs.getTimestamp("UPDATE_TIME")).thenReturn(updateTime);
        when(statsRs.getString("CREATE_OPTIONS")).thenReturn("COMPRESSION=\"zlib\"");

        ResultSet partitionRs = mock(ResultSet.class);
        when(partitionPs.executeQuery()).thenReturn(partitionRs);
        when(partitionRs.next()).thenReturn(true, false);
        when(partitionRs.getString("TABLE_NAME")).thenReturn("dwd_order");
        when(partitionRs.getString("EXPR")).thenReturn("`dt`,to_days(`created_at`)");

        List<TableMetadata> tables = singleTable("dwd_order");
        collector.fill(DatasourceType.MYSQL, connection, "dw", tables);

        TableMetadata table = tables.get(0);
        assertEquals(120L, table.getSizeBytes(), "大小应为 DATA_LENGTH + INDEX_LENGTH");
        assertEquals(createTime, table.getTableCreateTime());
        assertEquals(updateTime, table.getLastModifyTime());
        assertEquals("ZLIB", table.getCompression());
        assertEquals("dt,to_days(created_at)", table.getPartitionColumns());
    }

    @Test
    @DisplayName("ClickHouse：分区表达式/压缩编码/大小映射，反引号与空白被清理")
    void testFillClickHouse() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statsPs = mock(PreparedStatement.class);
        PreparedStatement codecPs = mock(PreparedStatement.class);
        when(connection.prepareStatement(contains("system.tables"))).thenReturn(statsPs);
        when(connection.prepareStatement(contains("system.columns"))).thenReturn(codecPs);

        ResultSet statsRs = mock(ResultSet.class);
        when(statsPs.executeQuery()).thenReturn(statsRs);
        when(statsRs.next()).thenReturn(true, false);
        when(statsRs.getString("name")).thenReturn("events");
        when(statsRs.getLong("total_bytes")).thenReturn(4096L);
        when(statsRs.getString("partition_key")).thenReturn(" toYYYYMM( event_date ) ");
        Timestamp modifyTime = Timestamp.valueOf("2026-09-29 12:00:00");
        when(statsRs.getTimestamp("metadata_modification_time")).thenReturn(modifyTime);

        ResultSet codecRs = mock(ResultSet.class);
        when(codecPs.executeQuery()).thenReturn(codecRs);
        when(codecRs.next()).thenReturn(true, false);
        when(codecRs.getString("table")).thenReturn("events");
        when(codecRs.getString("codecs")).thenReturn("CODEC(LZ4),CODEC(ZSTD(1))");

        List<TableMetadata> tables = singleTable("events");
        collector.fill(DatasourceType.CLICKHOUSE, connection, "dw", tables);

        TableMetadata table = tables.get(0);
        assertEquals(4096L, table.getSizeBytes());
        assertEquals(modifyTime, table.getLastModifyTime());
        assertEquals("toYYYYMM( event_date )", table.getPartitionColumns(), "应清理首尾空白");
        assertEquals("CODEC(LZ4),CODEC(ZSTD(1))", table.getCompression());
    }

    @Test
    @DisplayName("查询抛异常时保留空值且不抛出，返回的清单不被破坏")
    void testFillFailureKeepsEmptyValues() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.prepareStatement(contains("information_schema.TABLES")))
                .thenThrow(new java.sql.SQLException("boom"));
        when(connection.prepareStatement(contains("information_schema.PARTITIONS")))
                .thenThrow(new java.sql.SQLException("boom"));

        List<TableMetadata> tables = singleTable("dwd_order");
        collector.fill(DatasourceType.MYSQL, connection, "dw", tables);

        TableMetadata table = tables.get(0);
        assertNull(table.getSizeBytes());
        assertNull(table.getCompression());
        assertEquals(1, tables.size());
    }

    @Test
    @DisplayName("清单外的表（系统表返回多余行）被忽略")
    void testFillIgnoresUnknownTable() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statsPs = mock(PreparedStatement.class);
        PreparedStatement partitionPs = mock(PreparedStatement.class);
        when(connection.prepareStatement(contains("information_schema.TABLES"))).thenReturn(statsPs);
        when(connection.prepareStatement(contains("information_schema.PARTITIONS"))).thenReturn(partitionPs);

        ResultSet statsRs = mock(ResultSet.class);
        when(statsPs.executeQuery()).thenReturn(statsRs);
        when(statsRs.next()).thenReturn(true, false);
        when(statsRs.getString("TABLE_NAME")).thenReturn("other_table");
        when(statsRs.getLong("DATA_LENGTH")).thenReturn(9L);

        ResultSet partitionRs = mock(ResultSet.class);
        when(partitionPs.executeQuery()).thenReturn(partitionRs);
        when(partitionRs.next()).thenReturn(false);

        List<TableMetadata> tables = singleTable("dwd_order");
        collector.fill(DatasourceType.MYSQL, connection, "dw", tables);

        assertNull(tables.get(0).getSizeBytes(), "不在清单中的表不应被写入");
    }

    @Test
    @DisplayName("loadSizeBytes：返回原始表名到字节数的映射，无大小的表不计入")
    void testLoadSizeBytes() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statsPs = mock(PreparedStatement.class);
        PreparedStatement partitionPs = mock(PreparedStatement.class);
        when(connection.prepareStatement(contains("information_schema.TABLES"))).thenReturn(statsPs);
        when(connection.prepareStatement(contains("information_schema.PARTITIONS"))).thenReturn(partitionPs);

        ResultSet statsRs = mock(ResultSet.class);
        when(statsPs.executeQuery()).thenReturn(statsRs);
        when(statsRs.next()).thenReturn(true, true, false);
        when(statsRs.getString("TABLE_NAME")).thenReturn("Order_Detail", "empty_tbl");
        when(statsRs.getLong("DATA_LENGTH")).thenReturn(2048L, 0L);
        when(statsRs.getLong("INDEX_LENGTH")).thenReturn(0L, 0L);

        ResultSet partitionRs = mock(ResultSet.class);
        when(partitionPs.executeQuery()).thenReturn(partitionRs);
        when(partitionRs.next()).thenReturn(false);

        Map<String, Long> sizes = collector.loadSizeBytes(DatasourceType.MYSQL, connection, "dw");

        assertEquals(1, sizes.size());
        assertEquals(2048L, sizes.get("Order_Detail"), "表名应保留系统表原始大小写");
    }

    @Test
    @DisplayName("PostgreSQL：大小与行数都按「父表 = 自身 + 分区子表」聚合，子表不作为独立表返回")
    void testPostgresAggregatesPartitions() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statsPs = mock(PreparedStatement.class);
        PreparedStatement partitionPs = mock(PreparedStatement.class);
        when(connection.prepareStatement(contains("pg_inherits"))).thenReturn(statsPs);
        when(connection.prepareStatement(contains("pg_partitioned_table"))).thenReturn(partitionPs);

        ResultSet statsRs = mock(ResultSet.class);
        when(statsPs.executeQuery()).thenReturn(statsRs);
        when(statsRs.next()).thenReturn(true, false);
        when(statsRs.getString("table_name")).thenReturn("ods_olist_orders_d");
        when(statsRs.getLong("size_bytes")).thenReturn(24428544L);
        when(statsRs.getLong("row_count")).thenReturn(99441L);

        ResultSet partitionRs = mock(ResultSet.class);
        when(partitionPs.executeQuery()).thenReturn(partitionRs);
        when(partitionRs.next()).thenReturn(true, false);
        when(partitionRs.getString("table_name")).thenReturn("ods_olist_orders_d");
        when(partitionRs.getString("partition_columns")).thenReturn("dt");

        List<TableMetadata> tables = singleTable("ods_olist_orders_d");
        collector.fill(DatasourceType.POSTGRESQL, connection, "ods_olist_test", tables);

        TableMetadata table = tables.get(0);
        assertEquals(24428544L, table.getSizeBytes(), "分区父表大小应为聚合值而非 0");
        assertEquals(99441L, table.getRowCount(), "分区父表行数应为子表累加值");
        assertEquals("dt", table.getPartitionColumns());
    }

    @Test
    @DisplayName("PostgreSQL 取数 SQL 必须聚合分区子表并排除子表本身")
    void testPostgresSqlAggregatesPartitions() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statsPs = mock(PreparedStatement.class);
        PreparedStatement partitionPs = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statsPs);
        ResultSet statsRs = mock(ResultSet.class);
        when(statsPs.executeQuery()).thenReturn(statsRs);
        when(statsRs.next()).thenReturn(false);
        when(connection.prepareStatement(contains("pg_partitioned_table"))).thenReturn(partitionPs);
        ResultSet partitionRs = mock(ResultSet.class);
        when(partitionPs.executeQuery()).thenReturn(partitionRs);
        when(partitionRs.next()).thenReturn(false);

        collector.loadSizeBytes(DatasourceType.POSTGRESQL, connection, "ods_olist_test");

        // 一次采集会下发两条语句（统计 + 分区列），此处只校验统计那条
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection, times(2)).prepareStatement(sqlCaptor.capture());
        String sql = sqlCaptor.getAllValues().stream()
                .filter(value -> value.contains("pg_inherits"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到带分区聚合的表统计 SQL"));
        assertTrue(sql.contains("sum(pg_total_relation_size"), "父表大小必须累加分区子表：" + sql);
        assertTrue(sql.contains("NOT c.relispartition"), "分区子表不应作为独立表返回：" + sql);
        assertTrue(sql.contains("row_count"), "同一轮查询应带出行数：" + sql);
    }

    @Test
    @DisplayName("loadRowCounts：行数为 0 是合法值要保留，未统计过（负数）的表不计入")
    void testLoadRowCounts() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statsPs = mock(PreparedStatement.class);
        PreparedStatement partitionPs = mock(PreparedStatement.class);
        when(connection.prepareStatement(contains("information_schema.TABLES"))).thenReturn(statsPs);
        when(connection.prepareStatement(contains("information_schema.PARTITIONS"))).thenReturn(partitionPs);

        ResultSet statsRs = mock(ResultSet.class);
        when(statsPs.executeQuery()).thenReturn(statsRs);
        when(statsRs.next()).thenReturn(true, true, true, false);
        when(statsRs.getString("TABLE_NAME")).thenReturn("Order_Detail", "empty_tbl", "never_analyzed");
        when(statsRs.getLong("TABLE_ROWS")).thenReturn(2048L, 0L, -1L);
        when(statsRs.getLong("DATA_LENGTH")).thenReturn(0L);
        when(statsRs.getLong("INDEX_LENGTH")).thenReturn(0L);

        ResultSet partitionRs = mock(ResultSet.class);
        when(partitionPs.executeQuery()).thenReturn(partitionRs);
        when(partitionRs.next()).thenReturn(false);

        Map<String, Long> counts = collector.loadRowCounts(DatasourceType.MYSQL, connection, "dw");

        assertEquals(2, counts.size());
        assertEquals(2048L, counts.get("Order_Detail"), "表名应保留系统表原始大小写");
        assertEquals(0L, counts.get("empty_tbl"), "空表的 0 必须保留，不能与「未知」混为一谈");
    }
}