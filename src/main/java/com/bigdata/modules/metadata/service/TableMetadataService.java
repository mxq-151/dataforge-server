package com.bigdata.modules.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bigdata.modules.devops.enums.StorageEngineType;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.ColumnMetadataMapper;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
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

    public List<ColumnMetadata> getColumnsByTableId(Long tableMetadataId) {
        LambdaQueryWrapper<ColumnMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ColumnMetadata::getTableMetadataId, tableMetadataId)
               .orderByAsc(ColumnMetadata::getOrdinalPosition);
        return columnMetadataMapper.selectList(wrapper);
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
}