package com.bigdata.modules.metadata.service;

import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.devops.enums.StorageEngineType;
import com.bigdata.modules.devops.mapper.DatasourceConfigMapper;
import com.bigdata.modules.devops.service.DatasourceConfigService;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.MetadataSyncLog;
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
    private DatasourceConfigMapper datasourceConfigMapper;

    @Autowired
    private TableMetadataService tableMetadataService;

    @Autowired
    private MetadataSyncLogService metadataSyncLogService;

    @Autowired
    private DatasourceConfigService datasourceConfigService;

    @Autowired
    private TableMetadataMapper tableMetadataMapper;

    @Transactional
    public Map<String, Object> syncDatasourceMetadata(Long datasourceId) {
        DatasourceConfig dsConfig = datasourceConfigMapper.selectById(datasourceId);
        if (dsConfig == null) {
            return Map.of("success", false, "message", "数据源不存在");
        }

        MetadataSyncLog syncLog = metadataSyncLogService.createSyncLog(datasourceId, "full", 1L);

        Connection connection = null;
        try {
            connection = datasourceConfigService.getConnection(datasourceId);
            List<TableMetadata> tables = new ArrayList<>();
            Map<String, List<ColumnMetadata>> columnsMap = new HashMap<>();
            Set<String> databases = new HashSet<>();
            int totalColumns = 0;

            List<String> databaseNames = getDatabases(connection, dsConfig);

            for (String dbName : databaseNames) {
                databases.add(dbName);
                List<TableMetadata> dbTables = getTables(connection, dsConfig, dbName);
                for (TableMetadata table : dbTables) {
                    tables.add(table);
                    table.setStorageEngine(dsConfig.getStorageEngine());
                                       List<ColumnMetadata> columns = getColumns(connection, table, dbName);
                    String tableKey = table.getStorageEngine().name() + TableMetadataService.DELIMITER + dbName + TableMetadataService.DELIMITER + table.getTableName();
                    columnsMap.put(tableKey, columns);
                    totalColumns += columns.size();
                }
            }
            for(String dbName : databases){
                tableMetadataService.syncMetadataFromDatasource(dsConfig.getStorageEngine(), dbName, tables, columnsMap);

            }

            metadataSyncLogService.completeSyncLog(syncLog.getId(),
                    databases.size(), tables.size(), totalColumns);

            dsConfig.setConnectionStatus(1);
            datasourceConfigMapper.updateById(dsConfig);

            return Map.of(
                    "success", true,
                    "message", "同步成功",
                    "databaseCount", databases.size(),
                    "tableCount", tables.size(),
                    "columnCount", totalColumns
            );
        } catch (Exception e) {
            log.error("元数据同步失败: datasourceId={}, error={}", datasourceId, e.getMessage(), e);
            metadataSyncLogService.failSyncLog(syncLog.getId(), e.getMessage());

            dsConfig.setConnectionStatus(2);
            datasourceConfigMapper.updateById(dsConfig);

            return Map.of("success", false, "message", "同步失败: " + e.getMessage());
        } finally {
            if (connection != null) {
                try { connection.close(); } catch (SQLException ignored) {}
            }
        }
    }

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

    private List<String> getDatabases(Connection conn, DatasourceConfig dsConfig) throws SQLException {
        List<String> databases = new ArrayList<>();
        DatasourceType type = dsConfig.getDatasourceType();

        if (type == DatasourceType.MYSQL || type == DatasourceType.DORIS || type == DatasourceType.STARROCKS) {
            try (ResultSet rs = conn.getMetaData().getCatalogs()) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_CAT");
                    if (!isSystemDatabase(name, type)) {
                        databases.add(name);
                    }
                }
            }
        } else {
            try (ResultSet rs = conn.getMetaData().getSchemas()) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_SCHEM");
                    if (!isSystemSchema(name)) {
                        databases.add(name);
                    }
                }
            }
            if (databases.isEmpty()) {
                databases.add(dsConfig.getDatabaseName() != null ? dsConfig.getDatabaseName() : "default");
            }
        }
        return databases;
    }

    private boolean isSystemDatabase(String name, DatasourceType type) {
        if (type == DatasourceType.MYSQL) {
            return Set.of("information_schema", "mysql", "performance_schema", "sys").contains(name.toLowerCase());
        }
        if (type == DatasourceType.DORIS || type == DatasourceType.STARROCKS) {
            return Set.of("information_schema", "__internal_schema").contains(name.toLowerCase());
        }
        return false;
    }

    private boolean isSystemSchema(String name) {
        return Set.of("information_schema", "sys", "INFORMATION_SCHEMA").contains(name);
    }

    private List<TableMetadata> getTables(Connection conn, DatasourceConfig dsConfig, String dbName) throws SQLException {
        List<TableMetadata> tables = new ArrayList<>();
        String[] tableTypes = {"TABLE", "VIEW"};
        DatasourceType type = dsConfig.getDatasourceType();
        StorageEngineType storageEngine = dsConfig.getStorageEngine() != null ? 
                dsConfig.getStorageEngine() : inferStorageEngineFromDatasourceType(type);

        String catalog = null;
        String schema = dbName;
        if (type == DatasourceType.MYSQL || type == DatasourceType.DORIS || type == DatasourceType.STARROCKS) {
            catalog = dbName;
            schema = null;
        }

        try (ResultSet rs = conn.getMetaData().getTables(catalog, schema, "%", tableTypes)) {
            while (rs.next()) {
                TableMetadata table = new TableMetadata();
                table.setDatasourceId(dsConfig.getId());
                table.setStorageEngine(storageEngine);
                table.setDatabaseName(dbName);
                table.setTableName(rs.getString("TABLE_NAME"));
                table.setTableType(rs.getString("TABLE_TYPE"));
                table.setTableComment(rs.getString("REMARKS"));
                table.setSyncTime(new java.util.Date());
                table.setDeleted(0);
                tables.add(table);
            }
        }
        return tables;
    }

    private StorageEngineType inferStorageEngineFromDatasourceType(DatasourceType datasourceType) {
        if (datasourceType == null) return StorageEngineType.MYSQL;
        switch (datasourceType) {
            case HIVE: return StorageEngineType.HIVE;
            case DORIS: return StorageEngineType.DORIS;
            case STARROCKS: return StorageEngineType.STARROCKS;
            case MYSQL: return StorageEngineType.MYSQL;
            case ES: return StorageEngineType.ES;
            case CLICKHOUSE: return StorageEngineType.CLICKHOUSE;
            default: return StorageEngineType.MYSQL;
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
