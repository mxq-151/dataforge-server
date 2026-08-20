package com.bigdata.modules.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ColumnMetadataMapper extends BaseMapper<ColumnMetadata> {

    /**
     * upsert 语义：按唯一索引冲突时更新（方言见 mapper/mysql/ColumnMetadataMapper.xml）
     */
    int insertOrUpdate(@Param("col") ColumnMetadata column);

    /**
     * 批量 upsert（方言见 mapper/mysql/ColumnMetadataMapper.xml）
     */
    void batchInsertOrUpdate(@Param("list") List<ColumnMetadata> columns);
}