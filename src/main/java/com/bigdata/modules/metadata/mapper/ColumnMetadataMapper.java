package com.bigdata.modules.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ColumnMetadataMapper extends BaseMapper<ColumnMetadata> {


    @Insert("INSERT INTO column_metadata (" +
            "table_metadata_id, storage_engine, database_name, table_name, " +
            "column_name, column_type, column_comment, " +
            "is_partition, is_primary_key, ordinal_position, nullable, default_value, sync_time" +
            ") VALUES (" +
            "#{col.tableMetadataId}, #{col.storageEngine}, #{col.databaseName}, #{col.tableName}, " +
            "#{col.columnName}, #{col.columnType}, #{col.columnComment}, " +
            "#{col.isPartition}, #{col.isPrimaryKey}, #{col.ordinalPosition}, #{col.nullable}, #{col.defaultValue}, #{col.syncTime}" +
            ") " +
            "ON DUPLICATE KEY UPDATE " +
            "column_type = #{col.columnType}, " +
            "column_comment = #{col.columnComment}, " +
            "is_partition = #{col.isPartition}, " +
            "is_primary_key = #{col.isPrimaryKey}, " +
            "ordinal_position = #{col.ordinalPosition}, " +
            "nullable = #{col.nullable}, " +
            "default_value = #{col.defaultValue}, " +
            "sync_time = #{col.syncTime}")
    int insertOrUpdate(@Param("col") ColumnMetadata column);
}
