package com.bigdata.modules.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.metadata.entity.TableMetadata;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.bigdata.modules.devops.enums.DatasourceType;

@Mapper
public interface TableMetadataMapper extends BaseMapper<TableMetadata> {

    /**
     * upsert 语义：按唯一索引冲突时更新（方言见 mapper/mysql/TableMetadataMapper.xml）
     */
    int insertOrUpdate(@Param("t") TableMetadata table);

    TableMetadata getTableMetadata(DatasourceType storageEngine, String databaseName, String tableName);
}