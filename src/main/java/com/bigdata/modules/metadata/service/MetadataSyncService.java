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
import com.bigdata.modules.development.service.execution.AbstractBaseExecutionStrategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    @Lazy
    private ExecutionStrategyFactory executionStrategyFactory;

    @Transactional
    public boolean syncSingleTableMetadata(Long datasourceId, StorageEngineType storageEngine, String databaseName, String tableName, String ddlType,
                                            AbstractBaseExecutionStrategy.HiveTableAttributes hiveAttrs) {
        log.info("[DDL-DEBUG-D3] syncSingleTableMetadata 入口: dsId={}, engine={}, db={}, table={}, type={}",
                datasourceId, storageEngine, databaseName, tableName, ddlType);

        if ("DROP".equalsIgnoreCase(ddlType)) {
            log.info("增量同步: DROP操作, 删除本地元数据 {}.{}", databaseName, tableName);
            tableMetadataService.deleteTableByNames(storageEngine, databaseName, tableName);
            return true;
        }
        try {
            DatasourceConfig config = datasourceConfigService.getDatasourceById(datasourceId);
            if (config == null) {
                log.error("[DDL-DEBUG-D3] 数据源配置不存在! datasourceId={}", datasourceId);
                return false;
            }
            log.info("[DDL-DEBUG-D3] 数据源配置获取成功: id={}, name={}", config.getId(), config.getDatasourceName());

            TableMetadata targetTable = this.tableMetadataMapper.getTableMetadata(storageEngine, databaseName, tableName);
            if (targetTable == null) {
                log.info("[DDL-DEBUG-D3] 表不存在(首次建表), 将新建: {}.{}", databaseName, tableName);
                targetTable = new TableMetadata();
                targetTable.setTableName(tableName);
                targetTable.setDatabaseName(databaseName);
                targetTable.setDatasourceId(datasourceId);
                targetTable.setStorageEngine(storageEngine);
            } else {
                log.info("[DDL-DEBUG-D3] 表已存在, 将更新: id={}, {}.{}", targetTable.getId(), databaseName, tableName);
            }

            // 应用Hive表级属性（从DDL语句中提取的EXTERNAL/comment/LOCATION/STORED AS等）
            if (hiveAttrs != null) {
                applyHiveAttributes(targetTable, hiveAttrs);
                log.info("[DDL-DEBUG-D3] Hive属性已应用: type={}, comment={}, storageFormat={}, location={}",
                        targetTable.getTableType(), targetTable.getTableComment(),
                        targetTable.getStorageFormat(), targetTable.getLocation());
            }

            // 通过ExecutionStrategy获取字段信息
            ExecutionStrategy strategy = executionStrategyFactory.getStrategy(config);
            log.info("[DDL-DEBUG-D3] ExecutionStrategy获取成功: {}", strategy.getClass().getSimpleName());
            List<ColumnMetadataVO> columnVOs = strategy.listColumns(config, databaseName, tableName);
            log.info("[DDL-DEBUG-D4] listColumns返回字段数量: {}", columnVOs != null ? columnVOs.size() : "null");
            List<ColumnMetadata> columns = convertToColumnMetadataEntities(columnVOs, targetTable, storageEngine, databaseName);

            log.info("[DDL-DEBUG-D3] 准备写入DB: table={}, columns={}", targetTable.getTableName(), columns.size());
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
            // 远程存在的表键集合(databaseName + DELIMITER + tableName)，用于同步后清理孤表
            Set<String> remoteTableKeys = new HashSet<>();
            for (String db : databases) {
                if (isSystemDatabase(db)) continue;
                List<TableMetadata> tables = strategy.listTables(config, db);
                if (tables != null && !tables.isEmpty()) {
                    databaseTableMap.put(db, tables);
                    totalTableCount += tables.size();
                    for (TableMetadata t : tables) {
                        remoteTableKeys.add(db + TableMetadataService.DELIMITER + t.getTableName());
                    }
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

            // 清理本地多余表元数据(远程已不存在的表或数据库)
            if (remoteTableKeys.isEmpty()) {
                log.warn("远程未获取到任何表元数据, 跳过孤表清理: datasourceId={}", datasourceId);
            } else {
                int deletedCount = tableMetadataService.deleteOrphanTablesByDatasource(datasourceId, storageEngine, remoteTableKeys);
                if (deletedCount > 0) {
                    log.info("清理孤表元数据: datasourceId={}, 删除表数量={}", datasourceId, deletedCount);
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
     * 将从DDL语句中提取的Hive表级属性应用到TableMetadata实体。
     * 包括：表类型(EXTERNAL/MANAGED)、表注释、存储格式、存储路径等。
     */
    private void applyHiveAttributes(TableMetadata target, AbstractBaseExecutionStrategy.HiveTableAttributes attrs) {
        if (attrs == null || target == null) return;
        if (attrs.tableType != null) {
            target.setTableType(attrs.tableType);
        }
        if (attrs.tableComment != null) {
            target.setTableComment(attrs.tableComment);
        }
        if (attrs.storageFormat != null) {
            target.setStorageFormat(attrs.storageFormat);
        }
        if (attrs.location != null) {
            target.setLocation(attrs.location);
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