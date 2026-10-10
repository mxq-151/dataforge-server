package com.bigdata.modules.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bigdata.modules.asset.entity.AssetCatalogDaily;
import com.bigdata.modules.asset.mapper.AssetCatalogDailyMapper;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.ColumnMetadataMapper;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TableMetadataService extends ServiceImpl<TableMetadataMapper, TableMetadata> {

    @Autowired
    private ColumnMetadataMapper columnMetadataMapper;

    @Autowired
    private AssetCatalogDailyMapper assetCatalogDailyMapper;


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
            String[] words=keyword.split(",");
            for(String word:words)
            {
                wrapper.and(w -> w.like(TableMetadata::getTableName, word)
                    .or().like(TableMetadata::getTableComment, word));
            }
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

    public List<ColumnMetadata> getColumnsByNames(DatasourceType storageEngine, String databaseName, String tableName) {
        LambdaQueryWrapper<ColumnMetadata> wrapper = new LambdaQueryWrapper<>();
        // 未指定存储引擎时（storageEngine 为空）省略该过滤条件，按库+表查询
        if (storageEngine != null) {
            wrapper.eq(ColumnMetadata::getStorageEngine, storageEngine);
        }
        wrapper.eq(ColumnMetadata::getDatabaseName, databaseName)
               .eq(ColumnMetadata::getTableName, tableName)
               .orderByAsc(ColumnMetadata::getOrdinalPosition);
        return columnMetadataMapper.selectList(wrapper);
    }

    @Transactional
    public void syncMetadataFromDatasource(DatasourceType storageEngine,String databaseName, List<TableMetadata> tables,
                                            Map<String, List<ColumnMetadata>> columnsMap) {
        safeIncrementalSync(storageEngine, databaseName, tables, columnsMap);
    }

    @Transactional
    public void safeIncrementalSync(DatasourceType storageEngine,String databaseName, List<TableMetadata> tables,
                                     Map<String, List<ColumnMetadata>> columnsMap) {
        Set<String> existingTableKeys = getExistingTableKeys(storageEngine, databaseName);
        Set<String> newTableKeys = new HashSet<>();

        for (TableMetadata table : tables) {
            String tableKey = table.getStorageEngine() + DELIMITER + table.getDatabaseName() + DELIMITER + table.getTableName();
            newTableKeys.add(tableKey);
            syncSingleTable(storageEngine, table, columnsMap.getOrDefault(tableKey, Collections.emptyList()));
        }

        existingTableKeys.removeAll(newTableKeys);
        if (!existingTableKeys.isEmpty()) {
            batchDeleteTablesByEngineAndNames(existingTableKeys);
        }
    }

    private Set<String> getExistingTableKeys(DatasourceType storageEngine,String databaseName) {
        LambdaQueryWrapper<TableMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TableMetadata::getStorageEngine, storageEngine)
               .eq(TableMetadata::getDatabaseName, databaseName);

        List<TableMetadata> existingTables = list(wrapper);
        return existingTables.stream()
                .map(t -> t.getStorageEngine() + DELIMITER + t.getDatabaseName() + DELIMITER + t.getTableName())
                .collect(Collectors.toSet());
    }

    @Transactional
    public void syncSingleTable(DatasourceType storageEngine, TableMetadata table, List<ColumnMetadata> columns) {
        String newTableFingerprint = computeTableFingerprint(table);
        String newColumnsFingerprint = computeColumnsFingerprint(columns);

        LambdaQueryWrapper<TableMetadata> findWrapper = new LambdaQueryWrapper<>();
        findWrapper.eq(TableMetadata::getStorageEngine, storageEngine)
                   .eq(TableMetadata::getDatabaseName, table.getDatabaseName())
                   .eq(TableMetadata::getTableName, table.getTableName());
        TableMetadata existing = getOne(findWrapper, false);

        boolean tableUnchanged = existing != null && newTableFingerprint.equals(existing.getTableFingerprint());
        boolean columnsUnchanged = existing != null && newColumnsFingerprint.equals(existing.getColumnsFingerprint());

        // 表与字段指纹均未变更：跳过更新与字段重建，保留已有数据
        if (tableUnchanged && columnsUnchanged) {
            return;
        }

        if (existing != null) {
            table.setId(existing.getId());
            table.setStorageEngine(storageEngine);
            table.setSyncTime(new Date());
            table.setTableFingerprint(newTableFingerprint);
            table.setColumnsFingerprint(newColumnsFingerprint);
            updateById(table);
        } else {
            table.setId(null);
            table.setStorageEngine(storageEngine);
            table.setSyncTime(new Date());
            table.setTableFingerprint(newTableFingerprint);
            table.setColumnsFingerprint(newColumnsFingerprint);
            save(table);
        }

        // 字段指纹未变更：跳过字段删除与重建
        if (columnsUnchanged) {
            return;
        }

        if (existing != null) {
            LambdaQueryWrapper<ColumnMetadata> colDelWrapper = new LambdaQueryWrapper<>();
            colDelWrapper.eq(ColumnMetadata::getTableMetadataId, existing.getId());
            columnMetadataMapper.delete(colDelWrapper);
        }

        if (columns != null && !columns.isEmpty()) {
            for (ColumnMetadata column : columns) {
                column.setTableMetadataId(table.getId());
                column.setStorageEngine(storageEngine);
                column.setDatabaseName(table.getDatabaseName());
                column.setTableName(table.getTableName());
                column.setSyncTime(new Date());
            }
            columnMetadataMapper.batchInsertOrUpdate(columns);
        }
    }

    /**
     * 计算表结构指纹：对表级结构元数据拼接后取MD5。
     * 不含主键、同步时间、生命周期策略等本地/易变字段，以便表结构未变时跳过表更新。
     */
    private String computeTableFingerprint(TableMetadata table) {
        StringBuilder sb = new StringBuilder();
        sb.append(Objects.toString(table.getTableType(), "")).append(DELIMITER);
        sb.append(Objects.toString(table.getTableComment(), "")).append(DELIMITER);
        sb.append(Objects.toString(table.getTableCreateTime(), "")).append(DELIMITER);
        sb.append(Objects.toString(table.getLastModifyTime(), "")).append(DELIMITER);
        sb.append(Objects.toString(table.getPartitionColumns(), "")).append(DELIMITER);
        sb.append(Objects.toString(table.getStorageFormat(), "")).append(DELIMITER);
        sb.append(Objects.toString(table.getCompression(), "")).append(DELIMITER);
        sb.append(Objects.toString(table.getLocation(), "")).append(DELIMITER);
        sb.append(Objects.toString(table.getRowCount(), "")).append(DELIMITER);
        sb.append(Objects.toString(table.getSizeBytes(), "")).append(DELIMITER);
        return md5Hex(sb.toString());
    }

    /**
     * 计算字段结构指纹：对字段列表结构信息(按 ordinalPosition 排序)拼接后取MD5。
     * 字段结构未变时跳过字段删除与批量重建。
     */
    private String computeColumnsFingerprint(List<ColumnMetadata> columns) {
        StringBuilder sb = new StringBuilder();
        List<ColumnMetadata> sorted = (columns == null) ? Collections.emptyList()
                : columns.stream()
                         .sorted(Comparator.comparingInt(c -> c.getOrdinalPosition() == null ? 0 : c.getOrdinalPosition()))
                         .collect(Collectors.toList());
        for (ColumnMetadata c : sorted) {
            sb.append(Objects.toString(c.getColumnName(), "")).append('|');
            sb.append(Objects.toString(c.getColumnType(), "")).append('|');
            sb.append(Objects.toString(c.getColumnComment(), "")).append('|');
            sb.append(Objects.toString(c.getIsPartition(), "")).append('|');
            sb.append(Objects.toString(c.getIsPrimaryKey(), "")).append('|');
            sb.append(Objects.toString(c.getOrdinalPosition(), "")).append('|');
            sb.append(Objects.toString(c.getNullable(), "")).append('|');
            sb.append(Objects.toString(c.getDefaultValue(), "")).append(DELIMITER);
        }
        return md5Hex(sb.toString());
    }

    private String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    @Transactional
    public void deleteTableByNames(DatasourceType storageEngine, String databaseName, String tableName) {
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
            pruneCatalogSnapshot(List.of(existing.getId()));
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
            pruneCatalogSnapshot(List.of(existing.getId()));
        }
    }

    /**
     * 删除指定数据源下、远程已不存在的孤表元数据(同时删除其字段元数据)。
     * 通过 (databaseName, tableName) 与远程表键集合比对，未在集合中的视为孤表。
     * 同时覆盖两种场景：表被远程删除、表所在数据库被远程删除。
     *
     * @param datasourceId     数据源ID
     * @param storageEngine     存储引擎
     * @param remoteTableKeys   远程存在的表键集合(databaseName + DELIMITER + tableName)
     * @return 被删除的孤表数量
     */
    @Transactional
    public int deleteOrphanTablesByDatasource(Long datasourceId, DatasourceType storageEngine, Set<String> remoteTableKeys) {
        if (datasourceId == null || storageEngine == null) return 0;

        LambdaQueryWrapper<TableMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TableMetadata::getDatasourceId, datasourceId)
               .eq(TableMetadata::getStorageEngine, storageEngine);
        List<TableMetadata> localTables = list(wrapper);
        if (localTables.isEmpty()) return 0;

        List<Long> orphanIds = localTables.stream()
                .filter(t -> {
                    String key = t.getDatabaseName() + DELIMITER + t.getTableName();
                    return !remoteTableKeys.contains(key);
                })
                .map(TableMetadata::getId)
                .collect(Collectors.toList());

        if (orphanIds.isEmpty()) return 0;

        LambdaQueryWrapper<ColumnMetadata> colDelWrapper = new LambdaQueryWrapper<>();
        colDelWrapper.in(ColumnMetadata::getTableMetadataId, orphanIds);
        columnMetadataMapper.delete(colDelWrapper);

        removeByIds(orphanIds);
        pruneCatalogSnapshot(orphanIds);
        return orphanIds.size();
    }

    @Transactional
    public void batchDeleteTablesByEngineAndNames(Set<String> tableKeys) {
        if (tableKeys == null || tableKeys.isEmpty()) return;

        List<String> storageEngines = new ArrayList<>();
        List<String> dbNames = new ArrayList<>();
        List<String> tblNames = new ArrayList<>();
        for (String key : tableKeys) {
            String[] parts = key.split(DELIMITER);
            if (parts.length == 3) {
                storageEngines.add(parts[0]);
                dbNames.add(parts[1]);
                tblNames.add(parts[2]);
            }
        }
        if (storageEngines.isEmpty()) return;

        LambdaQueryWrapper<TableMetadata> findWrapper = new LambdaQueryWrapper<>();
        findWrapper.and(w -> {
            for (int i = 0; i < storageEngines.size(); i++) {
                if (i > 0) w.or();
                w.eq(TableMetadata::getStorageEngine, storageEngines.get(i))
                 .eq(TableMetadata::getDatabaseName, dbNames.get(i))
                 .eq(TableMetadata::getTableName, tblNames.get(i));
            }
        });
        List<TableMetadata> existingTables = list(findWrapper);
        if (existingTables.isEmpty()) return;

        List<Long> tableIds = existingTables.stream().map(TableMetadata::getId).collect(Collectors.toList());

        LambdaQueryWrapper<ColumnMetadata> colDelWrapper = new LambdaQueryWrapper<>();
        colDelWrapper.in(ColumnMetadata::getTableMetadataId, tableIds);
        columnMetadataMapper.delete(colDelWrapper);

        removeByIds(tableIds);
        pruneCatalogSnapshot(tableIds);
    }

    /**
     * 清理资产目录快照里已删除表的残留行。
     *
     * <p>目录树 / 检索 / 盘点 / 治理项读的是 T+1 快照 {@code asset_catalog_daily}，
     * 而快照每天才重建一次。表元数据被删除（DROP DDL、全量同步清理孤表、按名批量删除）后，
     * 若不同步清理快照，被删的表会继续出现在资产目录里，直到次日快照重新生成。
     * 这里在删除元数据的同一事务里把对应快照行一并删掉，使目录即时反映删除，无需等 T+1。</p>
     */
    private void pruneCatalogSnapshot(Collection<Long> tableMetadataIds) {
        if (tableMetadataIds == null || tableMetadataIds.isEmpty()) {
            return;
        }
        assetCatalogDailyMapper.delete(new LambdaQueryWrapper<AssetCatalogDaily>()
                .in(AssetCatalogDaily::getTableMetadataId, tableMetadataIds));
    }
}