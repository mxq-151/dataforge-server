package com.bigdata.modules.metadata.service;

import com.bigdata.modules.devops.enums.StorageEngineType;
import com.bigdata.modules.devops.service.DatasourceConfigService;
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