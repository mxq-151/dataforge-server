package com.bigdata.modules.development.service.execution.hbase;

import com.bigdata.modules.devops.enums.StorageEngineType;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.service.TableMetadataService;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.hadoop.hbase.client.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HBaseSchema extends AbstractSchema {

    private static final Logger log = LoggerFactory.getLogger(HBaseSchema.class);
    private static final String DEFAULT_COLUMN_FAMILY = "info";

    private final Connection connection;
    private final TableMetadataService tableMetadataService;
    private final Long datasourceId;
    private final Map<String, HBaseTableMeta> registeredTables;

    public HBaseSchema(Connection connection, TableMetadataService tableMetadataService, Long datasourceId) {
        this.connection = connection;
        this.tableMetadataService = tableMetadataService;
        this.datasourceId = datasourceId;
        this.registeredTables = new HashMap<>();
    }

    public void loadMetadataFromDb() {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TableMetadata> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        wrapper.eq(TableMetadata::getStorageEngine, StorageEngineType.HBASE)
               .eq(TableMetadata::getDatasourceId, datasourceId)
               .eq(TableMetadata::getDeleted, 0);
        List<TableMetadata> tables = tableMetadataService.list(wrapper);

        for (TableMetadata tableMeta : tables) {
            HBaseTableMeta meta = new HBaseTableMeta();
            meta.setTableName(tableMeta.getTableName());
            meta.getColumnFamilies().add(DEFAULT_COLUMN_FAMILY);

            List<ColumnMetadata> columns = tableMetadataService.getColumnsByTableId(tableMeta.getId());
            for (ColumnMetadata col : columns) {
                if (col.getIsPrimaryKey() != null && col.getIsPrimaryKey() == 1) {
                    continue;
                }
                String colName = col.getColumnName();
                if (colName.startsWith(DEFAULT_COLUMN_FAMILY + ".")) {
                    meta.addColumn(colName, col.getColumnType());
                } else if (!"rowkey".equalsIgnoreCase(colName)) {
                    meta.addColumn(DEFAULT_COLUMN_FAMILY + "." + colName, col.getColumnType());
                } else {
                    meta.addColumn(colName, col.getColumnType());
                }
            }

            registeredTables.put(tableMeta.getTableName().toUpperCase(), meta);
            log.info("从元数据加载HBase表: {} ({}列, datasourceId={})",
                    tableMeta.getTableName(), meta.getColumnNames().size(), datasourceId);
        }
        log.info("从元数据加载{}个HBase表 (datasourceId={})", registeredTables.size(), datasourceId);
    }

    public void registerTable(String tableName, HBaseTableMeta meta) {
        if (meta.getColumnFamilies().isEmpty()) {
            meta.getColumnFamilies().add(DEFAULT_COLUMN_FAMILY);
        }
        registeredTables.put(tableName.toUpperCase(), meta);
        log.info("注册HBase表: {}", tableName);
    }

    @Override
    protected Map<String, Table> getTableMap() {
        Map<String, Table> tableMap = new HashMap<>();
        for (Map.Entry<String, HBaseTableMeta> entry : registeredTables.entrySet()) {
            tableMap.put(entry.getKey(), new HBaseTable(connection, entry.getValue()));
        }
        return tableMap;
    }
}