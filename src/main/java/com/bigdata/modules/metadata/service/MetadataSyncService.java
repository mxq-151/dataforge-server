package com.bigdata.modules.metadata.service;

import com.bigdata.modules.devops.enums.StorageEngineType;
import com.bigdata.modules.devops.service.DatasourceConfigService;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.*;
import java.util.*;

@Service
public class MetadataSyncService {

    private static final Logger log = LoggerFactory.getLogger(MetadataSyncService.class);

    @Autowired
    private TableMetadataService tableMetadataService;

    @Autowired
    private DatasourceConfigService datasourceConfigService;

    @Autowired
    private TableMetadataMapper tableMetadataMapper;

    @Transactional
    public boolean syncSingleTableMetadata(Long datasourceId, StorageEngineType storageEngine, String databaseName, String tableName, String ddlType) {

        if ("DROP".equalsIgnoreCase(ddlType)) {
            log.info("增量同步: DROP操作, 删除本地元数据 {}.{}", databaseName, tableName);
            tableMetadataService.deleteTableByNames(storageEngine, databaseName, tableName);
            return true;
        }
        Connection connection =null;
        try {
            connection = datasourceConfigService.getConnection(datasourceId);
            TableMetadata targetTable = this.tableMetadataMapper.getTableMetadata(storageEngine, databaseName, tableName);
            List<ColumnMetadata> columns = getColumns(connection, targetTable, databaseName);
            tableMetadataService.syncSingleTable(storageEngine, targetTable, columns);
            log.info("增量同步成功: {} {}.{}", ddlType, databaseName, tableName);
            return true;
        } catch (Exception e) {
            log.error("增量同步失败: datasourceId={}, {}.{}", datasourceId, databaseName, tableName, e);
            return false;
        } finally {
            if (connection != null) {
                try { connection.close(); } catch (SQLException ignored) {}
            }
        }
    }

    @Transactional
    public boolean syncAllMetadataByDatasourceId(Long datasourceId) {
        Connection connection = null;
        try {
            DatasourceConfig config = datasourceConfigService.getDatasourceById(datasourceId);
            if (config == null) {
                log.error("数据源配置不存在: datasourceId={}", datasourceId);
                return false;
            }

            StorageEngineType storageEngine = config.getStorageEngine();

            connection = datasourceConfigService.getConnection(datasourceId);

            Map<String, List<TableMetadata>> databaseTableMap = getAllTables(connection, storageEngine);
            int totalTableCount = databaseTableMap.values().stream().mapToInt(List::size).sum();
            log.info("开始全量同步: datasourceId={}, 数据库数量={}, 表总数={}", datasourceId, databaseTableMap.size(), totalTableCount);

            int successCount = 0;
            int failCount = 0;
            for (Map.Entry<String, List<TableMetadata>> entry : databaseTableMap.entrySet()) {
                String db = entry.getKey();
                List<TableMetadata> tables = entry.getValue();
                log.info("同步数据库: {}, 表数量: {}", db, tables.size());
                
                for (TableMetadata remoteTable : tables) {
                    try {
                        TableMetadata targetTable = this.tableMetadataMapper.getTableMetadata(storageEngine, db, remoteTable.getTableName());
                        if (targetTable == null) {
                            targetTable = remoteTable;
                            targetTable.setDatasourceId(datasourceId);
                            targetTable.setStorageEngine(storageEngine);
                        } else {
                            if (remoteTable.getTableComment() != null) {
                                targetTable.setTableComment(remoteTable.getTableComment());
                            }
                            if (remoteTable.getTableType() != null) {
                                targetTable.setTableType(remoteTable.getTableType());
                            }
                            if (remoteTable.getCatalogName() != null) {
                                targetTable.setCatalogName(remoteTable.getCatalogName());
                            }
                            if (remoteTable.getStorageFormat() != null) {
                                targetTable.setStorageFormat(remoteTable.getStorageFormat());
                            }
                            if (remoteTable.getCompression() != null) {
                                targetTable.setCompression(remoteTable.getCompression());
                            }
                            if (remoteTable.getLocation() != null) {
                                targetTable.setLocation(remoteTable.getLocation());
                            }
                            if (remoteTable.getRowCount() != null) {
                                targetTable.setRowCount(remoteTable.getRowCount());
                            }
                            if (remoteTable.getSizeBytes() != null) {
                                targetTable.setSizeBytes(remoteTable.getSizeBytes());
                            }
                            if (remoteTable.getTableCreateTime() != null) {
                                targetTable.setTableCreateTime(remoteTable.getTableCreateTime());
                            }
                        }
                        List<ColumnMetadata> columns = getColumns(connection, targetTable, db);
                        tableMetadataService.syncSingleTable(storageEngine, targetTable, columns);
                        successCount++;
                    } catch (Exception e) {
                        log.error("同步表失败: {}.{}", db, remoteTable.getTableName(), e);
                        failCount++;
                    }
                }
            }

            log.info("全量同步完成: datasourceId={}, 成功={}, 失败={}", datasourceId, successCount, failCount);
            return failCount == 0;
        } catch (Exception e) {
            log.error("全量同步失败: datasourceId={}", datasourceId, e);
            return false;
        } finally {
            if (connection != null) {
                try { connection.close(); } catch (SQLException ignored) {}
            }
        }
    }

    private Map<String, List<TableMetadata>> getAllTables(Connection conn, StorageEngineType storageEngine) throws SQLException {
        Map<String, List<TableMetadata>> databaseTableMap = new LinkedHashMap<>();

        try (ResultSet rs = conn.getMetaData().getSchemas()) {
            while (rs.next()) {
                String schemaName = rs.getString("TABLE_SCHEM");
                if (schemaName != null && !schemaName.isEmpty() && !"information_schema".equalsIgnoreCase(schemaName)
                        && !"mysql".equalsIgnoreCase(schemaName) && !"performance_schema".equalsIgnoreCase(schemaName)
                        && !"sys".equalsIgnoreCase(schemaName)) {
                    List<TableMetadata> tables = getTablesByDatabase(conn, schemaName, storageEngine);
                    if (!tables.isEmpty()) {
                        databaseTableMap.put(schemaName, tables);
                    }
                }
            }
        }

        if (databaseTableMap.isEmpty()) {
            try (ResultSet rs = conn.getMetaData().getCatalogs()) {
                while (rs.next()) {
                    String catalogName = rs.getString("TABLE_CAT");
                    if (catalogName != null && !catalogName.isEmpty()) {
                        List<TableMetadata> tables = getTablesByDatabase(conn, catalogName, storageEngine);
                        if (!tables.isEmpty()) {
                            databaseTableMap.put(catalogName, tables);
                        }
                    }
                }
            }
        }

        return databaseTableMap;
    }

    private List<TableMetadata> getTablesByDatabase(Connection conn, String dbName, StorageEngineType storageEngine) throws SQLException {
        List<TableMetadata> tables = new ArrayList<>();

        String catalog = null;
        String schemaPattern = dbName;
        if (storageEngine == StorageEngineType.MYSQL
                || storageEngine == StorageEngineType.DORIS
                || storageEngine == StorageEngineType.STARROCKS) {
            catalog = dbName;
        }

        try (ResultSet rs = conn.getMetaData().getTables(catalog, schemaPattern, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                if (tableName != null && !tableName.isEmpty()) {
                    TableMetadata table = new TableMetadata();
                    table.setDatabaseName(dbName);
                    table.setTableName(tableName);
                    table.setCatalogName(rs.getString("TABLE_CAT"));
                    table.setTableType(rs.getString("TABLE_TYPE"));
                    table.setTableComment(rs.getString("REMARKS"));
                    enrichTableDetails(conn, dbName, table, storageEngine);
                    tables.add(table);
                }
            }
        }
        return tables;
    }

    private void enrichTableDetails(Connection conn, String dbName, TableMetadata table, StorageEngineType storageEngine) {
        if (storageEngine == null) return;

        switch (storageEngine) {
            case HIVE:
            case HUDI:
            case ICEBERG:
                enrichFromDescribeFormatted(conn, dbName, table);
                break;
            case MYSQL:
            case DORIS:
            case STARROCKS:
                enrichFromInfoSchema(conn, dbName, table);
                break;
            case CLICKHOUSE:
                enrichFromClickHouseSystem(conn, dbName, table);
                break;
            default:
                break;
        }
    }

    private void enrichFromDescribeFormatted(Connection conn, String dbName, TableMetadata table) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("DESCRIBE FORMATTED " + dbName + "." + table.getTableName())) {
            while (rs.next()) {
                String col1 = rs.getString(1);
                String col2 = rs.getString(2);
                String col3 = null;
                try { col3 = rs.getString(3); } catch (SQLException ignored) {}

                String key = (col1 != null) ? col1.trim() : "";
                String val2 = (col2 != null) ? col2.trim() : "";
                String val3 = (col3 != null) ? col3.trim() : "";

                if (!key.isEmpty()) {
                    if (key.startsWith("CreateTime:") || key.equals("Created Time")) {
                        if (!val2.isEmpty()) table.setTableCreateTime(parseDate(val2));
                    } else if (key.startsWith("InputFormat:") || key.equals("InputFormat")) {
                        if (!val2.isEmpty()) table.setStorageFormat(val2);
                    } else if (key.startsWith("Compressed:")) {
                        if ("Yes".equalsIgnoreCase(val2)) {
                            table.setCompression(extractCompressionFromDDL(conn, dbName, table.getTableName()));
                        } else {
                            table.setCompression("NO");
                        }
                    } else if (key.startsWith("numRows")) {
                        String v = !val2.isEmpty() ? val2 : val3;
                        if (!v.isEmpty() && !"-1".equals(v)) {
                            try { table.setRowCount(Long.parseLong(v)); } catch (NumberFormatException ignored) {}
                        }
                    } else if (key.startsWith("totalSize")) {
                        String v = !val2.isEmpty() ? val2 : val3;
                        if (!v.isEmpty() && !"0".equals(v)) {
                            try { table.setSizeBytes(Long.parseLong(v)); } catch (NumberFormatException ignored) {}
                        }
                    } else if (key.startsWith("Location:") || key.equals("Location")) {
                        if (!val2.isEmpty()) table.setLocation(val2);
                    } else if (key.equals("Table Properties")) {
                        parseSparkTableProperties(val2, table);
                    }
                } else if (!val2.isEmpty()) {
                    if ("numRows".equals(val2)) {
                        if (!val3.isEmpty() && !"-1".equals(val3)) {
                            try { table.setRowCount(Long.parseLong(val3)); } catch (NumberFormatException ignored) {}
                        }
                    } else if ("totalSize".equals(val2)) {
                        if (!val3.isEmpty() && !"0".equals(val3)) {
                            try { table.setSizeBytes(Long.parseLong(val3)); } catch (NumberFormatException ignored) {}
                        }
                    } else if ("LOCATION".equals(val2)) {
                        if (!val3.isEmpty()) table.setLocation(val3);
                    } else if ("transient_lastDdlTime".equals(val2) && table.getTableCreateTime() == null) {
                        try { table.setTableCreateTime(new java.util.Date(Long.parseLong(val3) * 1000L)); } catch (NumberFormatException ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            log.warn("DESCRIBE FORMATTED 失败: {}.{}, {}", dbName, table.getTableName(), e.getMessage());
        }

        if (table.getCompression() == null) {
            table.setCompression(extractCompressionFromDDL(conn, dbName, table.getTableName()));
        }

        if (table.getRowCount() == null || table.getSizeBytes() == null) {
            enrichFromHiveTableParams(conn, dbName, table);
        }
    }

    private void parseSparkTableProperties(String propsStr, TableMetadata table) {
        if (propsStr == null || propsStr.isEmpty()) return;

        String cleaned = propsStr;
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }

        String[] pairs = cleaned.split(",");
        for (String pair : pairs) {
            pair = pair.trim();
            int eqIdx = pair.indexOf('=');
            if (eqIdx <= 0) continue;

            String propKey = pair.substring(0, eqIdx).trim();
            String propVal = pair.substring(eqIdx + 1).trim();

            if ("numRows".equals(propKey) && table.getRowCount() == null && !"-1".equals(propVal)) {
                try { table.setRowCount(Long.parseLong(propVal)); } catch (NumberFormatException ignored) {}
            } else if ("totalSize".equals(propKey) && table.getSizeBytes() == null && !"0".equals(propVal)) {
                try { table.setSizeBytes(Long.parseLong(propVal)); } catch (NumberFormatException ignored) {}
            } else if ("transient_lastDdlTime".equals(propKey) && table.getTableCreateTime() == null) {
                try { table.setTableCreateTime(new java.util.Date(Long.parseLong(propVal) * 1000L)); } catch (NumberFormatException ignored) {}
            } else if ((propKey.contains("compress") || propKey.contains("compression")) && table.getCompression() == null) {
                String upper = propVal.toUpperCase();
                if (upper.contains("SNAPPY")) table.setCompression("SNAPPY");
                else if (upper.contains("GZIP")) table.setCompression("GZIP");
                else if (upper.contains("ZSTD")) table.setCompression("ZSTD");
                else if (upper.contains("LZ4")) table.setCompression("LZ4");
                else if (upper.contains("LZO")) table.setCompression("LZO");
                else if (upper.contains("BZIP2")) table.setCompression("BZIP2");
                else if (upper.contains("DEFLATE")) table.setCompression("DEFLATE");
                else if (upper.contains("ZLIB")) table.setCompression("ZLIB");
                else if (upper.contains("NO") || upper.contains("NONE") || upper.contains("UNCOMPRESSED")) table.setCompression("NO");
            }
        }
    }

    private void enrichFromHiveTableParams(Connection conn, String dbName, TableMetadata table) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW TBLPROPERTIES " + dbName + "." + table.getTableName())) {
            while (rs.next()) {
                String key = rs.getString(1);
                String value = rs.getString(2);
                if (key == null || value == null) continue;

                key = key.trim();
                value = value.trim();

                if ("numRows".equals(key) && !"-1".equals(value) && table.getRowCount() == null) {
                    try { table.setRowCount(Long.parseLong(value)); } catch (NumberFormatException ignored) {}
                } else if ("totalSize".equals(key) && !"0".equals(value) && table.getSizeBytes() == null) {
                    try { table.setSizeBytes(Long.parseLong(value)); } catch (NumberFormatException ignored) {}
                } else if ("transient_lastDdlTime".equals(key) && table.getTableCreateTime() == null) {
                    try { table.setTableCreateTime(new java.util.Date(Long.parseLong(value) * 1000L)); } catch (NumberFormatException ignored) {}
                } else if ("LOCATION".equals(key) && table.getLocation() == null) {
                    table.setLocation(value);
                } else if ((key.contains("compress") || key.contains("compression")) && table.getCompression() == null) {
                    String upper = value.toUpperCase();
                    if (upper.contains("SNAPPY")) table.setCompression("SNAPPY");
                    else if (upper.contains("GZIP")) table.setCompression("GZIP");
                    else if (upper.contains("ZSTD")) table.setCompression("ZSTD");
                    else if (upper.contains("LZ4")) table.setCompression("LZ4");
                    else if (upper.contains("LZO")) table.setCompression("LZO");
                    else if (upper.contains("BZIP2")) table.setCompression("BZIP2");
                    else if (upper.contains("DEFLATE")) table.setCompression("DEFLATE");
                    else if (upper.contains("ZLIB")) table.setCompression("ZLIB");
                    else if (upper.contains("NO") || upper.contains("NONE") || upper.contains("UNCOMPRESSED")) table.setCompression("NO");
                }
            }
        } catch (Exception e) {
            log.warn("SHOW TBLPROPERTIES 失败: {}.{}, {}", dbName, table.getTableName(), e.getMessage());
        }
    }

    private void enrichFromInfoSchema(Connection conn, String dbName, TableMetadata table) {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT TABLE_ROWS, DATA_LENGTH, INDEX_LENGTH, CREATE_TIME, ENGINE, TABLE_COMMENT FROM information_schema.tables WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
            pstmt.setString(1, dbName);
            pstmt.setString(2, table.getTableName());
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    long rowCount = rs.getLong("TABLE_ROWS");
                    if (!rs.wasNull()) table.setRowCount(rowCount);

                    long dataLength = rs.getLong("DATA_LENGTH");
                    boolean dataLengthNull = rs.wasNull();
                    long indexLength = rs.getLong("INDEX_LENGTH");
                    boolean indexLengthNull = rs.wasNull();
                    if (!dataLengthNull || !indexLengthNull) {
                        table.setSizeBytes((dataLengthNull ? 0 : dataLength) + (indexLengthNull ? 0 : indexLength));
                    }

                    java.sql.Timestamp createTime = rs.getTimestamp("CREATE_TIME");
                    if (createTime != null) table.setTableCreateTime(new java.util.Date(createTime.getTime()));

                    String engine = rs.getString("ENGINE");
                    if (engine != null) table.setStorageFormat(engine);

                    String tableComment = rs.getString("TABLE_COMMENT");
                    if (tableComment != null && !tableComment.isEmpty() && table.getTableComment() == null) {
                        table.setTableComment(tableComment);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("information_schema 查询失败: {}.{}, {}", dbName, table.getTableName(), e.getMessage());
        }

        if (table.getLocation() == null) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT @@datadir")) {
                if (rs.next()) {
                    String datadir = rs.getString(1);
                    if (datadir != null && !datadir.isEmpty()) {
                        table.setLocation(datadir + dbName + "/");
                    }
                }
            } catch (Exception ignored) {}
        }

        table.setCompression(extractCompressionFromDDL(conn, dbName, table.getTableName()));
    }

    private void enrichFromClickHouseSystem(Connection conn, String dbName, TableMetadata table) {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT total_rows, total_bytes, create_time, engine, data_paths FROM system.tables WHERE database = ? AND name = ?")) {
            pstmt.setString(1, dbName);
            pstmt.setString(2, table.getTableName());
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    long totalRows = rs.getLong("total_rows");
                    if (!rs.wasNull()) table.setRowCount(totalRows);

                    long totalBytes = rs.getLong("total_bytes");
                    if (!rs.wasNull()) table.setSizeBytes(totalBytes);

                    java.sql.Timestamp createTime = rs.getTimestamp("create_time");
                    if (createTime != null) table.setTableCreateTime(new java.util.Date(createTime.getTime()));

                    String engine = rs.getString("engine");
                    if (engine != null) table.setStorageFormat(engine);

                    String dataPaths = rs.getString("data_paths");
                    if (dataPaths != null && !dataPaths.isEmpty()) {
                        table.setLocation(dataPaths);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("system.tables 查询失败: {}.{}, {}", dbName, table.getTableName(), e.getMessage());
        }

        table.setCompression(extractCompressionFromDDL(conn, dbName, table.getTableName()));
    }

    private String extractCompressionFromDDL(Connection conn, String dbName, String tableName) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW CREATE TABLE " + dbName + "." + tableName)) {
            while (rs.next()) {
                String ddl = rs.getString(1);
                if (ddl == null) continue;
                String ddlLower = ddl.toLowerCase();
                if (ddlLower.contains("snappy")) return "SNAPPY";
                if (ddlLower.contains("gzip")) return "GZIP";
                if (ddlLower.contains("zstd")) return "ZSTD";
                if (ddlLower.contains("lz4")) return "LZ4";
                if (ddlLower.contains("lzo")) return "LZO";
                if (ddlLower.contains("bzip2")) return "BZIP2";
                if (ddlLower.contains("deflate")) return "DEFLATE";
                if (ddlLower.contains("orc.compress") && ddlLower.contains("zlib")) return "ZLIB";
            }
        } catch (Exception e) {
            log.warn("SHOW CREATE TABLE 获取压缩算法失败: {}.{}, {}", dbName, tableName, e.getMessage());
        }
        return null;
    }

    private java.util.Date parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return null;
        try {
            return new java.text.SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.ENGLISH).parse(dateStr);
        } catch (Exception e1) {
            try {
                return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(dateStr);
            } catch (Exception e2) {
                try {
                    return new java.text.SimpleDateFormat("yyyy-MM-dd").parse(dateStr);
                } catch (Exception e3) {
                    return null;
                }
            }
        }
    }

    private List<ColumnMetadata> getColumns(Connection conn, TableMetadata table, String dbName) throws SQLException {
        List<ColumnMetadata> columns = new ArrayList<>();

        try (ResultSet rs = conn.getMetaData().getColumns(null, dbName, table.getTableName(), null)) {
            int pos = 0;
            while (rs.next()) {
                ColumnMetadata col = new ColumnMetadata();
                col.setTableMetadataId(table.getId());
                col.setStorageEngine(table.getStorageEngine());
                col.setDatabaseName(dbName);
                col.setTableName(table.getTableName());
                col.setColumnName(rs.getString("COLUMN_NAME"));
                col.setColumnType(rs.getString("TYPE_NAME") + "(" + rs.getInt("COLUMN_SIZE") + ")");
                col.setColumnComment(rs.getString("REMARKS"));
                col.setNullable("YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")) ? 1 : 0);
                col.setDefaultValue(rs.getString("COLUMN_DEF"));
                col.setOrdinalPosition(++pos);
                col.setIsPartition(0);
                col.setIsPrimaryKey(0);
                col.setSyncTime(new java.util.Date());
                columns.add(col);
            }
        }

        try (ResultSet rs = conn.getMetaData().getPrimaryKeys(null, dbName, table.getTableName())) {
            while (rs.next()) {
                String pkCol = rs.getString("COLUMN_NAME");
                columns.stream()
                        .filter(c -> c.getColumnName().equals(pkCol))
                        .forEach(c -> c.setIsPrimaryKey(1));
            }
        } catch (Exception ignored) {
        }

        return columns;
    }
}