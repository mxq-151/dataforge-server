package com.bigdata.modules.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.metadata.entity.TableMetadata;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.bigdata.modules.devops.enums.StorageEngineType;

@Mapper
public interface TableMetadataMapper extends BaseMapper<TableMetadata> {


    @Insert("INSERT INTO table_metadata (" +
            "datasource_id, storage_engine, catalog_name, database_name, table_name, " +
            "table_type, table_comment, " +
            "partition_columns, storage_format, compression, location, row_count, size_bytes, " +
            "table_create_time, hot_days, cold_days, keep_days, " +
            "sync_time, deleted" +
            ") VALUES (" +
            "#{t.datasourceId}, #{t.storageEngine}, #{t.catalogName}, #{t.databaseName}, #{t.tableName}, " +
            "#{t.tableType}, #{t.tableComment}, " +
            "#{t.partitionColumns}, #{t.storageFormat}, #{t.compression}, #{t.location}, #{t.rowCount}, #{t.sizeBytes}, " +
            "#{t.tableCreateTime}, #{t.hotDays}, #{t.coldDays}, #{t.keepDays}, " +
            "#{t.syncTime}, #{t.deleted}" +
            ") " +
            "ON DUPLICATE KEY UPDATE " +
            "catalog_name = #{t.catalogName}, " +
            "table_type = #{t.tableType}, " +
            "table_comment = #{t.tableComment}, " +
            "partition_columns = #{t.partitionColumns}, " +
            "storage_format = #{t.storageFormat}, " +
            "compression = #{t.compression}, " +
            "location = #{t.location}, " +
            "row_count = #{t.rowCount}, " +
            "size_bytes = #{t.sizeBytes}, " +
            "table_create_time = #{t.tableCreateTime}, " +
            "hot_days = #{t.hotDays}, " +
            "cold_days = #{t.coldDays}, " +
            "keep_days = #{t.keepDays}, " +
            "sync_time = #{t.syncTime}, " +
            "deleted = #{t.deleted}")
    int insertOrUpdate(@Param("t") TableMetadata table);

    @Select("SELECT * FROM table_metadata WHERE storage_engine = #{storageEngine} AND database_name = #{databaseName} AND table_name = #{tableName}")
    TableMetadata getTableMetadata(StorageEngineType storageEngine, String databaseName, String tableName);
}
