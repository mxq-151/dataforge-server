package com.bigdata.modules.metadata.service;

import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.metadata.entity.TableMetadata;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.sql.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetadataSyncServiceRealDataTest {

    private static final String MYSQL_URL = "jdbc:mysql://127.0.0.1:3306/bigdata_platform?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    private static final String MYSQL_USER = "root";
    private static final String MYSQL_PASSWORD = "123456";
    private static final String HIVE_URL = "jdbc:hive2://192.168.1.8:10009/default";
    private static final String HIVE_USER = "hive";
    private static final String HIVE_PASSWORD = "";

    private MetadataSyncService metadataSyncService;

    @BeforeEach
    void setUp() {
        metadataSyncService = new MetadataSyncService();
    }

    private Connection getRealMySQLConnection() {
        try {
            Connection conn = DriverManager.getConnection(MYSQL_URL, MYSQL_USER, MYSQL_PASSWORD);
            return conn;
        } catch (Exception e) {
            return null;
        }
    }

    private Connection getRealHiveConnection() {
        try {
            Connection conn = DriverManager.getConnection(HIVE_URL, HIVE_USER, HIVE_PASSWORD);
            return conn;
        } catch (Exception e) {
            return null;
        }
    }

    @Test
    void testEnrichFromInfoSchema_WithRealMySQL() throws Exception {
        Connection conn = getRealMySQLConnection();
        if (conn == null) {
            System.out.println("MySQL not available, skipping test");
            return;
        }

        try {
            String dbName = "bigdata_platform";
            String tableName = "table_metadata";

            System.out.println("=== Real MySQL: Raw information_schema.tables for " + dbName + "." + tableName + " ===");
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT TABLE_ROWS, DATA_LENGTH, INDEX_LENGTH, CREATE_TIME, ENGINE, TABLE_COMMENT FROM information_schema.tables WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
                pstmt.setString(1, dbName);
                pstmt.setString(2, tableName);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        System.out.println("TABLE_ROWS:    " + rs.getObject("TABLE_ROWS"));
                        System.out.println("DATA_LENGTH:   " + rs.getObject("DATA_LENGTH"));
                        System.out.println("INDEX_LENGTH:  " + rs.getObject("INDEX_LENGTH"));
                        System.out.println("CREATE_TIME:   " + rs.getObject("CREATE_TIME"));
                        System.out.println("ENGINE:        " + rs.getObject("ENGINE"));
                        System.out.println("TABLE_COMMENT: " + rs.getObject("TABLE_COMMENT"));
                    } else {
                        System.out.println("No data found in information_schema!");
                    }
                }
            }

            System.out.println("\n--- Testing @@datadir ---");
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT @@datadir")) {
                if (rs.next()) {
                    System.out.println("@@datadir: " + rs.getString(1));
                }
            } catch (Exception e) {
                System.out.println("@@datadir query failed: " + e.getMessage());
            }

            TableMetadata table = new TableMetadata();
            table.setDatabaseName(dbName);
            table.setTableName(tableName);

            Method method = MetadataSyncService.class.getDeclaredMethod(
                    "enrichFromInfoSchema", Connection.class, String.class, TableMetadata.class);
            method.setAccessible(true);
            method.invoke(metadataSyncService, conn, dbName, table);

            System.out.println("\n--- enrichFromInfoSchema Results ---");
            System.out.println("storageFormat:   " + table.getStorageFormat());
            System.out.println("tableCreateTime: " + table.getTableCreateTime());
            System.out.println("rowCount:        " + table.getRowCount());
            System.out.println("sizeBytes:       " + table.getSizeBytes());
            System.out.println("compression:     " + table.getCompression());
            System.out.println("location:        " + table.getLocation());

            assertNotNull(table.getStorageFormat(), "storageFormat should not be null");
            assertNotNull(table.getRowCount(), "rowCount should not be null");
            assertNotNull(table.getSizeBytes(), "sizeBytes should not be null");
            assertNotNull(table.getTableCreateTime(), "tableCreateTime should not be null");
            assertNotNull(table.getLocation(), "location should not be null");
        } finally {
            conn.close();
        }
    }

    @Test
    void testEnrichFromInfoSchema_AllMySQLTables() throws Exception {
        Connection conn = getRealMySQLConnection();
        if (conn == null) {
            System.out.println("MySQL not available, skipping test");
            return;
        }

        try {
            String dbName = "bigdata_platform";

            System.out.println("=== Real MySQL: All tables in " + dbName + " ===");
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT TABLE_NAME, ENGINE, TABLE_ROWS, DATA_LENGTH + INDEX_LENGTH AS TOTAL_SIZE, CREATE_TIME FROM information_schema.tables WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME")) {
                pstmt.setString(1, dbName);
                try (ResultSet rs = pstmt.executeQuery()) {
                    System.out.printf("%-30s %-10s %10s %15s %s%n", "TABLE_NAME", "ENGINE", "TABLE_ROWS", "TOTAL_SIZE", "CREATE_TIME");
                    System.out.println("-".repeat(80));
                    while (rs.next()) {
                        System.out.printf("%-30s %-10s %10s %15s %s%n",
                                rs.getString("TABLE_NAME"),
                                rs.getString("ENGINE"),
                                rs.getObject("TABLE_ROWS"),
                                rs.getObject("TOTAL_SIZE"),
                                rs.getObject("CREATE_TIME"));
                    }
                }
            }

            String[] testTables = {"table_metadata", "datasource_config", "sys_user"};
            for (String tableName : testTables) {
                TableMetadata table = new TableMetadata();
                table.setDatabaseName(dbName);
                table.setTableName(tableName);

                Method method = MetadataSyncService.class.getDeclaredMethod(
                        "enrichFromInfoSchema", Connection.class, String.class, TableMetadata.class);
                method.setAccessible(true);
                method.invoke(metadataSyncService, conn, dbName, table);

                System.out.println("\n--- " + tableName + " ---");
                System.out.println("  storageFormat:   " + table.getStorageFormat());
                System.out.println("  tableCreateTime: " + table.getTableCreateTime());
                System.out.println("  rowCount:        " + table.getRowCount());
                System.out.println("  sizeBytes:       " + table.getSizeBytes());
                System.out.println("  compression:     " + table.getCompression());
                System.out.println("  location:        " + table.getLocation());

                assertNotNull(table.getStorageFormat(), tableName + ": storageFormat should not be null");
                assertNotNull(table.getTableCreateTime(), tableName + ": tableCreateTime should not be null");
            }
        } finally {
            conn.close();
        }
    }

    @Test
    void testGetTablesByDatabase_WithRealMySQL() throws Exception {
        Connection conn = getRealMySQLConnection();
        if (conn == null) {
            System.out.println("MySQL not available, skipping test");
            return;
        }

        try {
            Method method = MetadataSyncService.class.getDeclaredMethod(
                    "getTablesByDatabase", Connection.class, String.class, DatasourceType.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<TableMetadata> tables = (List<TableMetadata>) method.invoke(
                    metadataSyncService, conn, "bigdata_platform", DatasourceType.MYSQL);

            System.out.println("=== Real MySQL: getTablesByDatabase (bigdata_platform) ===");
            System.out.println("Total tables: " + tables.size());
            for (TableMetadata table : tables) {
                System.out.println("  " + table.getTableName() + ":");
                System.out.println("    storageFormat:   " + table.getStorageFormat());
                System.out.println("    tableCreateTime: " + table.getTableCreateTime());
                System.out.println("    rowCount:        " + table.getRowCount());
                System.out.println("    sizeBytes:       " + table.getSizeBytes());
                System.out.println("    compression:     " + table.getCompression());
                System.out.println("    location:        " + table.getLocation());
            }

            assertFalse(tables.isEmpty(), "Should find tables in bigdata_platform");
            for (TableMetadata table : tables) {
                assertNotNull(table.getStorageFormat(),
                        table.getTableName() + ": storageFormat should not be null");
            }
        } finally {
            conn.close();
        }
    }

    @Test
    void testEnrichFromDescribeFormatted_WithRealHive() throws Exception {
        Connection conn = getRealHiveConnection();
        if (conn == null) {
            System.out.println("Hive/Kyuubi not available, skipping test");
            return;
        }

        try {
            String dbName = "ods";
            String tableName = "ods_log_full";

            System.out.println("=== Real Hive: Raw DESCRIBE FORMATTED " + dbName + "." + tableName + " ===");
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("DESCRIBE FORMATTED " + dbName + "." + tableName)) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                System.out.println("Column count: " + colCount);
                for (int i = 1; i <= colCount; i++) {
                    System.out.println("  Column " + i + ": " + meta.getColumnLabel(i) + " (" + meta.getColumnTypeName(i) + ")");
                }

                System.out.println("\n--- Raw DESCRIBE FORMATTED data ---");
                int rowNum = 0;
                while (rs.next()) {
                    rowNum++;
                    StringBuilder sb = new StringBuilder();
                    sb.append(String.format("Row %3d:", rowNum));
                    for (int i = 1; i <= colCount; i++) {
                        String val = rs.getString(i);
                        sb.append(String.format(" [%s]", val != null ? val.trim() : "NULL"));
                    }
                    System.out.println(sb);
                }
            } catch (Exception e) {
                System.out.println("DESCRIBE FORMATTED failed: " + e.getClass().getName() + ": " + e.getMessage());
            }

            System.out.println("\n--- Raw SHOW TBLPROPERTIES ---");
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW TBLPROPERTIES " + dbName + "." + tableName)) {
                while (rs.next()) {
                    System.out.println("  " + rs.getString(1) + " = " + rs.getString(2));
                }
            } catch (Exception e) {
                System.out.println("SHOW TBLPROPERTIES failed: " + e.getClass().getName() + ": " + e.getMessage());
            }

            System.out.println("\n--- Raw SHOW CREATE TABLE ---");
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW CREATE TABLE " + dbName + "." + tableName)) {
                while (rs.next()) {
                    System.out.println("  " + rs.getString(1));
                }
            } catch (Exception e) {
                System.out.println("SHOW CREATE TABLE failed: " + e.getClass().getName() + ": " + e.getMessage());
            }

            TableMetadata table = new TableMetadata();
            table.setDatabaseName(dbName);
            table.setTableName(tableName);

            Method method = MetadataSyncService.class.getDeclaredMethod(
                    "enrichFromDescribeFormatted", Connection.class, String.class, TableMetadata.class);
            method.setAccessible(true);
            method.invoke(metadataSyncService, conn, dbName, table);

            System.out.println("\n--- enrichFromDescribeFormatted Results ---");
            System.out.println("storageFormat:   " + table.getStorageFormat());
            System.out.println("tableCreateTime: " + table.getTableCreateTime());
            System.out.println("rowCount:        " + table.getRowCount());
            System.out.println("sizeBytes:       " + table.getSizeBytes());
            System.out.println("compression:     " + table.getCompression());
            System.out.println("location:        " + table.getLocation());

            assertNotNull(table.getStorageFormat(), "storageFormat should not be null");
            assertNotNull(table.getLocation(), "location should not be null");
        } finally {
            conn.close();
        }
    }

    @Test
    void testGetAllTables_WithRealHive() throws Exception {
        Connection conn = getRealHiveConnection();
        if (conn == null) {
            System.out.println("Hive/Kyuubi not available, skipping test");
            return;
        }

        try {
            Method method = MetadataSyncService.class.getDeclaredMethod(
                    "getAllTables", Connection.class, DatasourceType.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            Map<String, List<TableMetadata>> result = (Map<String, List<TableMetadata>>) method.invoke(
                    metadataSyncService, conn, DatasourceType.HIVE);

            System.out.println("=== Real Hive: getAllTables Results ===");
            System.out.println("Total databases: " + result.size());
            for (Map.Entry<String, List<TableMetadata>> entry : result.entrySet()) {
                System.out.println("Database: " + entry.getKey() + " (" + entry.getValue().size() + " tables)");
                for (TableMetadata table : entry.getValue()) {
                    System.out.println("  " + table.getTableName() + ":");
                    System.out.println("    storageFormat:   " + table.getStorageFormat());
                    System.out.println("    tableCreateTime: " + table.getTableCreateTime());
                    System.out.println("    rowCount:        " + table.getRowCount());
                    System.out.println("    sizeBytes:       " + table.getSizeBytes());
                    System.out.println("    compression:     " + table.getCompression());
                    System.out.println("    location:        " + table.getLocation());
                }
            }

            assertFalse(result.isEmpty(), "Should find databases in Hive");
        } finally {
            conn.close();
        }
    }

    @Test
    void testEnrichFromClickHouseSystem_WithRealClickHouse() throws Exception {
        String url = System.getProperty("clickhouse.url", "");
        if (url.isEmpty()) {
            System.out.println("ClickHouse not configured, skipping test");
            return;
        }

        String user = System.getProperty("clickhouse.user", "default");
        String password = System.getProperty("clickhouse.password", "");
        String dbName = System.getProperty("clickhouse.db", "default");
        String tableName = System.getProperty("clickhouse.table", "");

        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            System.out.println("=== Real ClickHouse: Querying " + dbName + "." + tableName + " ===");

            TableMetadata table = new TableMetadata();
            table.setDatabaseName(dbName);
            table.setTableName(tableName);

            Method method = MetadataSyncService.class.getDeclaredMethod(
                    "enrichFromClickHouseSystem", Connection.class, String.class, TableMetadata.class);
            method.setAccessible(true);
            method.invoke(metadataSyncService, conn, dbName, table);

            System.out.println("storageFormat:   " + table.getStorageFormat());
            System.out.println("tableCreateTime: " + table.getTableCreateTime());
            System.out.println("rowCount:        " + table.getRowCount());
            System.out.println("sizeBytes:       " + table.getSizeBytes());
            System.out.println("compression:     " + table.getCompression());
            System.out.println("location:        " + table.getLocation());

            assertNotNull(table.getStorageFormat(), "storageFormat should not be null");
        }
    }
}
