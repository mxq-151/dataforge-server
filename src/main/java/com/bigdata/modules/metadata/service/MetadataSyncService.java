package com.bigdata.modules.metadata.service;

import com.bigdata.modules.devops.enums.StorageEngineType;
import com.bigdata.modules.devops.service.DatasourceConfigService;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import com.bigdata.modules.metadata.vo.ColumnMetadataVO;
import com.bigdata.modules.development.service.execution.ExecutionStrategy;
import com.bigdata.modules.development.service.execution.ExecutionStrategyFactory;

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

    @Autowired
    private ExecutionStrategyFactory executionStrategyFactory;

    @Transactional
    public boolean syncSingleTableMetadata(Long datasourceId, StorageEngineType storageEngine, String databaseName, String tableName, String ddlType) {

        if ("DROP".equalsIgnoreCase(ddlType)) {
            log.info("增量同步: DROP操作, 删除本地元数据 {}.{}", databaseName, tableName);
            tableMetadataService.deleteTableByNames(storageEngine, databaseName, tableName);
            return true;
        }
        try {
            DatasourceConfig config = datasourceConfigService.getDatasourceById(datasourceId);
            if (config == null) {
                log.error("数据源配置不存在: datasourceId={}", datasourceId);
                return false;
            }

            TableMetadata targetTable = this.tableMetadataMapper.getTableMetadata(storageEngine, databaseName, tableName);

            // 通过ExecutionStrategy获取字段信息
            ExecutionStrategy strategy = executionStrategyFactory.getStrategy(config);
            List<ColumnMetadataVO> columnVOs = strategy.listColumns(config, databaseName, tableName);
            List<ColumnMetadata> columns = convertToColumnMetadataEntities(columnVOs, targetTable, storageEngine, databaseName);

            tableMetadataService.syncSingleTable(storageEngine, targetTable, columns);
            log.info("增量同步成功: {} {}.{}", ddlType, databaseName, tableName);
            return true;
        } catch (Exception e) {
            log.error("增量同步失败: datasourceId={}, {}.{}", datasourceId, databaseName, tableName, e);
            return false;
        }
    }

    @Transactional
    public boolean syncAllMetadataByDatasourceId(Long datasourceId) {
        try {
            DatasourceConfig config = datasourceConfigService.getDatasourceById(datasourceId);
            if (config == null) {
                log.error("数据源配置不存在: datasourceId={}", datasourceId);
                return false;
            }

            StorageEngineType storageEngine = config.getStorageEngine();

            // 通过ExecutionStrategy获取库表元信息
            ExecutionStrategy strategy = executionStrategyFactory.getStrategy(config);

            List<String> databases = strategy.listDatabases(config);
            if (databases == null || databases.isEmpty()) {
                log.warn("未获取到任何数据库: datasourceId={}", datasourceId);
                return false;
            }

            int totalTableCount = 0;
            Map<String, List<TableMetadata>> databaseTableMap = new LinkedHashMap<>();
            for (String db : databases) {
                if (isSystemDatabase(db)) continue;
                List<TableMetadata> tables = strategy.listTables(config, db);
                if (tables != null && !tables.isEmpty()) {
                    databaseTableMap.put(db, tables);
                    totalTableCount += tables.size();
                }
            }

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
                            mergeTableMetadata(targetTable, remoteTable);
                        }
                        // 通过ExecutionStrategy获取字段信息
                        List<ColumnMetadataVO> columnVOs = strategy.listColumns(config, db, remoteTable.getTableName());
                        List<ColumnMetadata> columns = convertToColumnMetadataEntities(columnVOs, targetTable, storageEngine, db);
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
        }
    }

    /**
     * 判断是否为系统数据库，跳过同步
     */
    private boolean isSystemDatabase(String dbName) {
        if (dbName == null || dbName.isEmpty()) return true;
        String lower = dbName.toLowerCase();
        return "information_schema".equals(lower)
                || "mysql".equals(lower)
                || "performance_schema".equals(lower)
                || "sys".equals(lower);
    }

    /**
     * 将远程表元数据合并到本地已有记录（仅覆盖非空字段）
     */
    private void mergeTableMetadata(TableMetadata target, TableMetadata source) {
        if (source.getTableComment() != null) {
            target.setTableComment(source.getTableComment());
        }
        if (source.getTableType() != null) {
            target.setTableType(source.getTableType());
        }
        if (source.getCatalogName() != null) {
            target.setCatalogName(source.getCatalogName());
        }
        if (source.getStorageFormat() != null) {
            target.setStorageFormat(source.getStorageFormat());
        }
        if (source.getCompression() != null) {
            target.setCompression(source.getCompression());
        }
        if (source.getLocation() != null) {
            target.setLocation(source.getLocation());
        }
        if (source.getRowCount() != null) {
            target.setRowCount(source.getRowCount());
        }
        if (source.getSizeBytes() != null) {
            target.setSizeBytes(source.getSizeBytes());
        }
        if (source.getTableCreateTime() != null) {
            target.setTableCreateTime(source.getTableCreateTime());
        }
    }

    /**
     * 将ExecutionStrategy返回的ColumnMetadataVO列表转换为ColumnMetadata实体列表
     */
    private List<ColumnMetadata> convertToColumnMetadataEntities(List<ColumnMetadataVO> columnVOs,
                                                                 TableMetadata table,
                                                                 StorageEngineType storageEngine,
                                                                 String databaseName) {
        List<ColumnMetadata> columns = new ArrayList<>();
        if (columnVOs == null) return columns;

        for (ColumnMetadataVO vo : columnVOs) {
            ColumnMetadata col = new ColumnMetadata();
            col.setTableMetadataId(table != null ? table.getId() : null);
            col.setStorageEngine(storageEngine);
            col.setDatabaseName(databaseName);
            col.setTableName(vo.getTableName());
            col.setColumnName(vo.getColumnName());
            col.setColumnType(vo.getColumnType());
            col.setColumnComment(vo.getColumnComment());
            col.setNullable(vo.getNullable());
            col.setDefaultValue(vo.getDefaultValue());
            col.setOrdinalPosition(vo.getOrdinalPosition());
            col.setIsPartition(vo.getIsPartition() != null ? vo.getIsPartition() : 0);
            col.setIsPrimaryKey(vo.getIsPrimaryKey() != null ? vo.getIsPrimaryKey() : 0);
            col.setSyncTime(new java.util.Date());
            columns.add(col);
        }
        return columns;
    }
}