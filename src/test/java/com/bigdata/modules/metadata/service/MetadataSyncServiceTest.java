package com.bigdata.modules.metadata.service;

import com.bigdata.modules.devops.service.DatasourceConfigService;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MetadataSyncServiceTest {

    @Mock
    private TableMetadataService tableMetadataService;

    @Mock
    private DatasourceConfigService datasourceConfigService;

    @Mock
    private TableMetadataMapper tableMetadataMapper;

    @InjectMocks
    private MetadataSyncService metadataSyncService;

    private Connection mockConn;
    private Statement mockStmt;
    private PreparedStatement mockPstmt;

    @BeforeEach
    void setUp() throws SQLException {
        mockConn = mock(Connection.class);
        mockStmt = mock(Statement.class);
        mockPstmt = mock(PreparedStatement.class);
    }

    private ResultSet buildDescribeFormattedRs(List<String[]> rows) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        final int[] index = {0};
        lenient().when(rs.next()).thenAnswer(inv -> {
            index[0]++;
            return index[0] <= rows.size();
        });
        lenient().when(rs.getString(1)).thenAnswer(inv -> rows.get(index[0] - 1)[0]);
        lenient().when(rs.getString(2)).thenAnswer(inv -> rows.get(index[0] - 1)[1]);
        lenient().when(rs.getString(3)).thenAnswer(inv -> rows.get(index[0] - 1).length > 2 ? rows.get(index[0] - 1)[2] : null);
        return rs;
    }

    private void setupCompressionMock(String ddl) throws SQLException {
        ResultSet ddlRs = mock(ResultSet.class);
        when(ddlRs.next()).thenReturn(true, false);
        when(ddlRs.getString(1)).thenReturn(ddl);
        Statement ddlStmt = mock(Statement.class);
        when(ddlStmt.executeQuery(startsWith("SHOW CREATE TABLE"))).thenReturn(ddlRs);

        Statement[] stmts = new Statement[]{mockStmt, ddlStmt};
        int[] callCount = {0};
        when(mockConn.createStatement()).thenAnswer(inv -> {
            callCount[0]++;
            return stmts[Math.min(callCount[0] - 1, stmts.length - 1)];
        });
    }

    @Test
    void testEnrichFromDescribeFormatted_TableParamsInColumn2ValueInColumn3() throws Exception {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"# Detailed Table Information", "", ""});
        rows.add(new String[]{"CreateTime:", "Wed May 22 10:30:00 CST 2024", ""});
        rows.add(new String[]{"InputFormat:", "org.apache.hadoop.mapred.TextInputFormat", ""});
        rows.add(new String[]{"Compressed:", "Yes", ""});
        rows.add(new String[]{"", "numRows", "1234567"});
        rows.add(new String[]{"", "totalSize", "1073741824"});
        rows.add(new String[]{"", "LOCATION", "hdfs://namenode:8020/user/hive/warehouse/ods.db/user_info"});
        rows.add(new String[]{"", "transient_lastDdlTime", "1716352200"});

        ResultSet describeRs = buildDescribeFormattedRs(rows);
        setupCompressionMock("CREATE TABLE ods.user_info (id INT) STORED AS ORC tblproperties (\"orc.compress\"=\"SNAPPY\")");

        when(mockStmt.executeQuery(startsWith("DESCRIBE FORMATTED"))).thenReturn(describeRs);

        TableMetadata table = new TableMetadata();
        table.setDatabaseName("ods");
        table.setTableName("user_info");

        Method method = MetadataSyncService.class.getDeclaredMethod("enrichFromDescribeFormatted", Connection.class, String.class, TableMetadata.class);
        method.setAccessible(true);
        method.invoke(metadataSyncService, mockConn, "ods", table);

        System.out.println("=== TableParams在Column2/Value在Column3 ===");
        System.out.println("storageFormat: " + table.getStorageFormat());
        System.out.println("tableCreateTime: " + table.getTableCreateTime());
        System.out.println("compression: " + table.getCompression());
        System.out.println("rowCount: " + table.getRowCount());
        System.out.println("sizeBytes: " + table.getSizeBytes());
        System.out.println("location: " + table.getLocation());

        assertEquals("org.apache.hadoop.mapred.TextInputFormat", table.getStorageFormat());
        assertNotNull(table.getTableCreateTime());
        assertEquals("SNAPPY", table.getCompression());
        assertEquals(1234567L, table.getRowCount());
        assertEquals(1073741824L, table.getSizeBytes());
        assertEquals("hdfs://namenode:8020/user/hive/warehouse/ods.db/user_info", table.getLocation());
    }

    @Test
    void testEnrichFromDescribeFormatted_TableParamsInColumn1() throws Exception {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"# Detailed Table Information", "", ""});
        rows.add(new String[]{"CreateTime:", "2024-05-22 10:30:00", ""});
        rows.add(new String[]{"InputFormat:", "org.apache.hadoop.hive.ql.io.orc.OrcInputFormat", ""});
        rows.add(new String[]{"Compressed:", "No", ""});
        rows.add(new String[]{"numRows", "500000", null});
        rows.add(new String[]{"totalSize", "2147483648", null});
        rows.add(new String[]{"Location:", "hdfs://namenode:8020/user/hive/warehouse/ods.db/order_detail", ""});

        ResultSet describeRs = buildDescribeFormattedRs(rows);
        when(mockConn.createStatement()).thenReturn(mockStmt);
        when(mockStmt.executeQuery(startsWith("DESCRIBE FORMATTED"))).thenReturn(describeRs);

        TableMetadata table = new TableMetadata();
        table.setDatabaseName("ods");
        table.setTableName("order_detail");

        Method method = MetadataSyncService.class.getDeclaredMethod("enrichFromDescribeFormatted", Connection.class, String.class, TableMetadata.class);
        method.setAccessible(true);
        method.invoke(metadataSyncService, mockConn, "ods", table);

        System.out.println("=== TableParams在Column1 ===");
        System.out.println("storageFormat: " + table.getStorageFormat());
        System.out.println("tableCreateTime: " + table.getTableCreateTime());
        System.out.println("compression: " + table.getCompression());
        System.out.println("rowCount: " + table.getRowCount());
        System.out.println("sizeBytes: " + table.getSizeBytes());
        System.out.println("location: " + table.getLocation());

        assertEquals("org.apache.hadoop.hive.ql.io.orc.OrcInputFormat", table.getStorageFormat());
        assertNotNull(table.getTableCreateTime());
        assertEquals("NO", table.getCompression());
        assertEquals(500000L, table.getRowCount());
        assertEquals(2147483648L, table.getSizeBytes());
        assertEquals("hdfs://namenode:8020/user/hive/warehouse/ods.db/order_detail", table.getLocation());
    }

    @Test
    void testEnrichFromInfoSchema_MySQL() throws Exception {
        when(mockConn.prepareStatement(anyString())).thenReturn(mockPstmt);
        ResultSet infoRs = mock(ResultSet.class);
        when(mockPstmt.executeQuery()).thenReturn(infoRs);
        when(infoRs.next()).thenReturn(true, false);
        when(infoRs.getLong("TABLE_ROWS")).thenReturn(10000L);
        when(infoRs.wasNull()).thenReturn(false, false, false);
        when(infoRs.getLong("DATA_LENGTH")).thenReturn(1048576L);
        when(infoRs.getLong("INDEX_LENGTH")).thenReturn(524288L);
        when(infoRs.getTimestamp("CREATE_TIME")).thenReturn(Timestamp.valueOf("2024-01-15 08:30:00"));
        when(infoRs.getString("ENGINE")).thenReturn("InnoDB");
        when(infoRs.getString("TABLE_COMMENT")).thenReturn("用户表");

        Statement datadirStmt = mock(Statement.class);
        ResultSet datadirRs = mock(ResultSet.class);
        when(datadirRs.next()).thenReturn(true, false);
        when(datadirRs.getString(1)).thenReturn("/var/lib/mysql/data/");
        when(datadirStmt.executeQuery(eq("SELECT @@datadir"))).thenReturn(datadirRs);

        Statement ddlStmt = mock(Statement.class);
        ResultSet ddlRs = mock(ResultSet.class);
        when(ddlRs.next()).thenReturn(false);
        when(ddlStmt.executeQuery(startsWith("SHOW CREATE TABLE"))).thenReturn(ddlRs);

        Statement[] stmts = new Statement[]{datadirStmt, ddlStmt};
        int[] callCount = {0};
        when(mockConn.createStatement()).thenAnswer(inv -> {
            callCount[0]++;
            return stmts[Math.min(callCount[0] - 1, stmts.length - 1)];
        });

        TableMetadata table = new TableMetadata();
        table.setDatabaseName("mydb");
        table.setTableName("users");

        Method method = MetadataSyncService.class.getDeclaredMethod("enrichFromInfoSchema", Connection.class, String.class, TableMetadata.class);
        method.setAccessible(true);
        method.invoke(metadataSyncService, mockConn, "mydb", table);

        System.out.println("=== information_schema 解析结果 ===");
        System.out.println("storageFormat: " + table.getStorageFormat());
        System.out.println("tableCreateTime: " + table.getTableCreateTime());
        System.out.println("rowCount: " + table.getRowCount());
        System.out.println("sizeBytes: " + table.getSizeBytes());
        System.out.println("location: " + table.getLocation());
        System.out.println("tableComment: " + table.getTableComment());

        assertEquals("InnoDB", table.getStorageFormat());
        assertNotNull(table.getTableCreateTime());
        assertEquals(10000L, table.getRowCount());
        assertEquals(1048576L + 524288L, table.getSizeBytes());
        assertEquals("/var/lib/mysql/data/mydb/", table.getLocation());
        assertEquals("用户表", table.getTableComment());
    }

    @Test
    void testEnrichFromClickHouseSystem() throws Exception {
        when(mockConn.prepareStatement(anyString())).thenReturn(mockPstmt);
        ResultSet ckRs = mock(ResultSet.class);
        when(mockPstmt.executeQuery()).thenReturn(ckRs);
        when(ckRs.next()).thenReturn(true, false);
        when(ckRs.getLong("total_rows")).thenReturn(500000L);
        when(ckRs.wasNull()).thenReturn(false);
        when(ckRs.getLong("total_bytes")).thenReturn(2097152L);
        when(ckRs.getTimestamp("create_time")).thenReturn(Timestamp.valueOf("2024-03-10 12:00:00"));
        when(ckRs.getString("engine")).thenReturn("MergeTree");
        when(ckRs.getString("data_paths")).thenReturn("/var/lib/clickhouse/data/default/events/");

        Statement ddlStmt = mock(Statement.class);
        ResultSet ddlRs = mock(ResultSet.class);
        when(ddlRs.next()).thenReturn(false);
        when(ddlStmt.executeQuery(anyString())).thenReturn(ddlRs);
        when(mockConn.createStatement()).thenReturn(ddlStmt);

        TableMetadata table = new TableMetadata();
        table.setDatabaseName("default");
        table.setTableName("events");

        Method method = MetadataSyncService.class.getDeclaredMethod("enrichFromClickHouseSystem", Connection.class, String.class, TableMetadata.class);
        method.setAccessible(true);
        method.invoke(metadataSyncService, mockConn, "default", table);

        System.out.println("=== ClickHouse system.tables 解析结果 ===");
        System.out.println("storageFormat: " + table.getStorageFormat());
        System.out.println("tableCreateTime: " + table.getTableCreateTime());
        System.out.println("rowCount: " + table.getRowCount());
        System.out.println("sizeBytes: " + table.getSizeBytes());
        System.out.println("location: " + table.getLocation());

        assertEquals("MergeTree", table.getStorageFormat());
        assertNotNull(table.getTableCreateTime());
        assertEquals(500000L, table.getRowCount());
        assertEquals(2097152L, table.getSizeBytes());
        assertEquals("/var/lib/clickhouse/data/default/events/", table.getLocation());
    }

    @Test
    void testParseDate() throws Exception {
        Method method = MetadataSyncService.class.getDeclaredMethod("parseDate", String.class);
        method.setAccessible(true);

        java.util.Date d1 = (java.util.Date) method.invoke(metadataSyncService, "Wed May 22 10:30:00 CST 2024");
        assertNotNull(d1, "Hive日期格式应被正确解析");

        java.util.Date d2 = (java.util.Date) method.invoke(metadataSyncService, "2024-05-22 10:30:00");
        assertNotNull(d2, "标准日期时间格式应被正确解析");

        java.util.Date d3 = (java.util.Date) method.invoke(metadataSyncService, "2024-05-22");
        assertNotNull(d3, "日期格式应被正确解析");

        java.util.Date d4 = (java.util.Date) method.invoke(metadataSyncService, "");
        assertNull(d4, "空字符串应返回null");

        java.util.Date d5 = (java.util.Date) method.invoke(metadataSyncService, (String) null);
        assertNull(d5, "null应返回null");
    }

    @Test
    void testExtractCompressionFromDDL() throws Exception {
        Statement stmt = mock(Statement.class);
        when(mockConn.createStatement()).thenReturn(stmt);

        ResultSet snappyRs = mock(ResultSet.class);
        when(snappyRs.next()).thenReturn(true, false);
        when(snappyRs.getString(1)).thenReturn("CREATE TABLE t (id INT) STORED AS ORC tblproperties (\"orc.compress\"=\"SNAPPY\")");
        when(stmt.executeQuery(anyString())).thenReturn(snappyRs);

        Method method = MetadataSyncService.class.getDeclaredMethod("extractCompressionFromDDL", Connection.class, String.class, String.class);
        method.setAccessible(true);
        String result = (String) method.invoke(metadataSyncService, mockConn, "ods", "user_info");
        assertEquals("SNAPPY", result);
    }
}
