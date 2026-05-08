package com.bigdata.modules.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.devops.enums.StorageEngineType;
import com.bigdata.modules.devops.service.DatasourceConfigService;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.ColumnMetadataMapper;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import com.bigdata.modules.metadata.vo.TableDetailVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class TableMetadataService extends ServiceImpl<TableMetadataMapper, TableMetadata> {

    @Autowired
    private ColumnMetadataMapper columnMetadataMapper;

    @Autowired
    private TableMetadataMapper tableMetadataMapper;

    @Autowired
    private DatasourceConfigService datasourceConfigService;

    public static final String DELIMITER = "##";

    public Page<TableMetadata> getTableList(int current, int size, Long datasourceId,
                                             String databaseName, String tableName, String keyword) {
        Page<TableMetadata> page = new Page<>(current, size);
        LambdaQueryWrapper<TableMetadata> wrapper = new LambdaQueryWrapper<>();

        if (datasourceId != null) {
            wrapper.eq(TableMetadata::getDatasourceId, datasourceId);
        }
        if (databaseName != null && !databaseName.isEmpty()) {
            wrapper.eq(TableMetadata::getDatabaseName, databaseName);
        }
        if (tableName != null && !tableName.isEmpty()) {
            wrapper.like(TableMetadata::getTableName, tableName);
        }
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(TableMetadata::getTableName, keyword)
                    .or().like(TableMetadata::getTableComment, keyword)
                    .or().like(TableMetadata::getDatabaseName, keyword));
        }

        wrapper.orderByDesc(TableMetadata::getSyncTime);

        return page(page, wrapper);
    }

    public TableMetadata getTableById(Long id) {
        return getById(id);
    }

    public List<TableMetadata> getTablesByDatasourceId(Long datasourceId) {
        LambdaQueryWrapper<TableMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TableMetadata::getDatasourceId, datasourceId)
               .orderByAsc(TableMetadata::getDatabaseName)
               .orderByAsc(TableMetadata::getTableName);
        return list(wrapper);
    }

    public List<String> getDatabasesByDatasourceId(Long datasourceId) {
        List<String> databases = new ArrayList<>();

        try {
            java.sql.Connection conn = datasourceConfigService.getConnection(datasourceId);
            DatasourceConfig config = datasourceConfigService.getDatasourceById(datasourceId);

            if (config == null) {
                return databases;
            }

            DatasourceType type = config.getDatasourceType();

            if (type == DatasourceType.MYSQL || type == DatasourceType.DORIS || type == DatasourceType.STARROCKS) {
                try (java.sql.ResultSet rs = conn.getMetaData().getCatalogs()) {
                    while (rs.next()) {
                        String name = rs.getString("TABLE_CAT");
                        if (name != null && !isSystemDatabase(name, type)) {
                            databases.add(name);
                        }
                    }
                }
            } else {
                Set<String> schemaSet = new LinkedHashSet<>();
                try (java.sql.ResultSet rs = conn.getMetaData().getSchemas()) {
                    while (rs.next()) {
                        String name = rs.getString("TABLE_SCHEM");
                        if (name != null && !isSystemSchema(name)) {
                            schemaSet.add(name);
                        }
                    }
                }

                if (schemaSet.isEmpty()) {
                    if (config.getDatabaseName() != null && !config.getDatabaseName().isEmpty()) {
                        databases.add(config.getDatabaseName());
                    } else {
                        databases.add("default");
                    }
                } else {
                    databases.addAll(schemaSet);
                }
            }

            conn.close();
        } catch (Exception e) {

            LambdaQueryWrapper<TableMetadata> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(TableMetadata::getDatasourceId, datasourceId)
                   .select(TableMetadata::getDatabaseName)
                   .groupBy(TableMetadata::getDatabaseName);
            List<TableMetadata> list = list(wrapper);
            databases = list.stream()
                .map(TableMetadata::getDatabaseName)
                .distinct()
                .collect(java.util.stream.Collectors.toList());
        }

        return databases;
    }

    private boolean isSystemDatabase(String name, DatasourceType type) {
        if (name == null) return true;
        if (type == DatasourceType.MYSQL) {
            return Set.of("information_schema", "mysql", "performance_schema", "sys")
                       .contains(name.toLowerCase());
        }
        if (type == DatasourceType.DORIS || type == DatasourceType.STARROCKS) {
            return Set.of("information_schema", "__internal_schema").contains(name.toLowerCase());
        }
        return false;
    }

    private boolean isSystemSchema(String name) {
        if (name == null) return true;
        return Set.of("information_schema", "sys", "INFORMATION_SCHEMA").contains(name);
    }

    public List<ColumnMetadata> getColumnsByTableId(Long tableMetadataId) {
        LambdaQueryWrapper<ColumnMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ColumnMetadata::getTableMetadataId, tableMetadataId)
               .orderByAsc(ColumnMetadata::getOrdinalPosition);
        return columnMetadataMapper.selectList(wrapper);
    }

    @Transactional
    public void deleteTable(Long id) {
        removeById(id);
        LambdaQueryWrapper<ColumnMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ColumnMetadata::getTableMetadataId, id);
        columnMetadataMapper.delete(wrapper);
    }

    @Transactional
    public void syncMetadataFromDatasource(StorageEngineType storageEngine,String databaseName, List<TableMetadata> tables,
                                            Map<String, List<ColumnMetadata>> columnsMap) {
        safeIncrementalSync(storageEngine, databaseName, tables, columnsMap);
    }

    @Transactional
    public void safeIncrementalSync(StorageEngineType storageEngine,String databaseName, List<TableMetadata> tables,
                                     Map<String, List<ColumnMetadata>> columnsMap) {
        Set<String> existingTableKeys = getExistingTableKeys(storageEngine, databaseName);
        Set<String> newTableKeys = new HashSet<>();

        for (TableMetadata table : tables) {
            String tableKey = table.getStorageEngine() + DELIMITER + table.getDatabaseName() + DELIMITER + table.getTableName();
            newTableKeys.add(tableKey);
            syncSingleTable(storageEngine, table, columnsMap.getOrDefault(tableKey, Collections.emptyList()));
        }

        existingTableKeys.removeAll(newTableKeys);
        for (String deletedTableKey : existingTableKeys) {
            String[] parts = deletedTableKey.split(DELIMITER);
            if (parts.length == 3) {
                deleteTableByEngineAndNames(parts[0], parts[1], parts[2]);
            }
        }
    }

    private Set<String> getExistingTableKeys(StorageEngineType storageEngine,String databaseName) {
        LambdaQueryWrapper<TableMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TableMetadata::getStorageEngine, storageEngine)
               .eq(TableMetadata::getDatabaseName, databaseName);

        List<TableMetadata> existingTables = list(wrapper);
        return existingTables.stream()
                .map(t -> t.getStorageEngine() + DELIMITER + t.getDatabaseName() + DELIMITER + t.getTableName())
                .collect(Collectors.toSet());
    }

    @Transactional
    public Long saveOrUpdateAndGetId(Long datasourceId, TableMetadata table) {
        table.setDatasourceId(datasourceId);
        table.setSyncTime(new Date());
        table.setDeleted(0);

        baseMapper.insertOrUpdate(table);

        if (table.getId() == null) {
            LambdaQueryWrapper<TableMetadata> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(TableMetadata::getStorageEngine, table.getStorageEngine())
                   .eq(TableMetadata::getDatabaseName, table.getDatabaseName())
                   .eq(TableMetadata::getTableName, table.getTableName())
                   .select(TableMetadata::getId);
            TableMetadata existing = getOne(wrapper, false);
            if (existing != null) {
                return existing.getId();
            }
        }
        return table.getId();
    }

    @Transactional
    public void smartSyncColumns(Long tableMetadataId, List<ColumnMetadata> newColumns,
                                  StorageEngineType storageEngine, String databaseName, String tableName) {
        List<ColumnMetadata> existingCols = getColumnsByTableId(tableMetadataId);

        Set<String> existingColNames = existingCols.stream()
                .map(ColumnMetadata::getColumnName)
                .collect(Collectors.toSet());

        Set<String> newColNames = newColumns.stream()
                .map(ColumnMetadata::getColumnName)
                .collect(Collectors.toSet());

        for (ColumnMetadata newCol : newColumns) {
            newCol.setTableMetadataId(tableMetadataId);
            newCol.setStorageEngine(storageEngine);
            newCol.setDatabaseName(databaseName);
            newCol.setTableName(tableName);
            newCol.setSyncTime(new Date());
            columnMetadataMapper.insertOrUpdate(newCol);
        }

        Set<String> colsToDelete = new HashSet<>(existingColNames);
        colsToDelete.removeAll(newColNames);
        for (String colName : colsToDelete) {
            LambdaQueryWrapper<ColumnMetadata> deleteWrapper = new LambdaQueryWrapper<>();
            deleteWrapper.eq(ColumnMetadata::getTableMetadataId, tableMetadataId)
                        .eq(ColumnMetadata::getColumnName, colName);
            columnMetadataMapper.delete(deleteWrapper);
        }
    }

    @Transactional
    public void syncSingleTable(StorageEngineType storageEngine, TableMetadata table, List<ColumnMetadata> columns) {
        LambdaQueryWrapper<TableMetadata> findWrapper = new LambdaQueryWrapper<>();
        findWrapper.eq(TableMetadata::getStorageEngine, storageEngine)
                   .eq(TableMetadata::getDatabaseName, table.getDatabaseName())
                   .eq(TableMetadata::getTableName, table.getTableName());
        TableMetadata existing = getOne(findWrapper, false);

        if (existing != null) {
            LambdaQueryWrapper<ColumnMetadata> colDelWrapper = new LambdaQueryWrapper<>();
            colDelWrapper.eq(ColumnMetadata::getTableMetadataId, existing.getId());
            columnMetadataMapper.delete(colDelWrapper);

            table.setId(existing.getId());
            table.setStorageEngine(storageEngine);
            table.setSyncTime(new Date());
            table.setDeleted(0);
            updateById(table);
        } else {
            table.setStorageEngine(storageEngine);
            table.setSyncTime(new Date());
            table.setDeleted(0);
            save(table);
        }

        if (columns != null) {
            for (ColumnMetadata column : columns) {
                column.setTableMetadataId(table.getId());
                column.setStorageEngine(storageEngine);
                column.setDatabaseName(table.getDatabaseName());
                column.setTableName(table.getTableName());
                column.setSyncTime(new Date());
                columnMetadataMapper.insert(column);
            }
        }
    }

    @Transactional
    public void deleteTableByNames(StorageEngineType storageEngine, String databaseName, String tableName) {
        LambdaQueryWrapper<TableMetadata> findWrapper = new LambdaQueryWrapper<>();
        findWrapper.eq(TableMetadata::getStorageEngine, storageEngine)
                   .eq(TableMetadata::getDatabaseName, databaseName)
                   .eq(TableMetadata::getTableName, tableName);
        TableMetadata existing = getOne(findWrapper, false);
        if (existing != null) {
            LambdaQueryWrapper<ColumnMetadata> colDelWrapper = new LambdaQueryWrapper<>();
            colDelWrapper.eq(ColumnMetadata::getTableMetadataId, existing.getId());
            columnMetadataMapper.delete(colDelWrapper);
            removeById(existing.getId());
        }
    }

    @Transactional
    public void deleteTableByEngineAndNames(String storageEngine, String databaseName, String tableName) {
        LambdaQueryWrapper<TableMetadata> findWrapper = new LambdaQueryWrapper<>();
        findWrapper.eq(TableMetadata::getStorageEngine, storageEngine)
                   .eq(TableMetadata::getDatabaseName, databaseName)
                   .eq(TableMetadata::getTableName, tableName);
        TableMetadata existing = getOne(findWrapper, false);
        if (existing != null) {
            LambdaQueryWrapper<ColumnMetadata> colDelWrapper = new LambdaQueryWrapper<>();
            colDelWrapper.eq(ColumnMetadata::getTableMetadataId, existing.getId());
            columnMetadataMapper.delete(colDelWrapper);
            removeById(existing.getId());
        }
    }

    @Transactional
    public void updateTableComment(Long id, String comment) {
        TableMetadata table = getById(id);
        if (table != null) {
            table.setTableComment(comment);
            updateById(table);
        }
    }

    @Transactional
    public void updateColumnComment(Long id, String comment) {
        ColumnMetadata column = columnMetadataMapper.selectById(id);
        if (column != null) {
            column.setColumnComment(comment);
            columnMetadataMapper.updateById(column);
        }
    }

    public long countByDatasourceId(Long datasourceId) {
        LambdaQueryWrapper<TableMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TableMetadata::getDatasourceId, datasourceId);
        return count(wrapper);
    }

    public Page<TableDetailVO> getTableDetailList(int current, int size, Long datasourceId,
                                                   String databaseName, String tableName, String keyword) {
        Page<TableMetadata> tablePage = getTableList(current, size, datasourceId, databaseName, tableName, keyword);

        Page<TableDetailVO> detailPage = new Page<>(tablePage.getCurrent(), tablePage.getSize(), tablePage.getTotal());
        List<TableDetailVO> detailList = new ArrayList<>();

        for (TableMetadata table : tablePage.getRecords()) {
            List<ColumnMetadata> columns = getColumnsByTableId(table.getId());
            detailList.add(new TableDetailVO(table, columns));
        }

        detailPage.setRecords(detailList);
        return detailPage;
    }

    public TableDetailVO getTableDetailById(Long id) {
        TableMetadata table = getTableById(id);
        if (table == null) {
            return null;
        }
        List<ColumnMetadata> columns = getColumnsByTableId(id);
        return new TableDetailVO(table, columns);
    }
}
