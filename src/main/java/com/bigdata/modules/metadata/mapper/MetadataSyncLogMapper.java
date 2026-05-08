package com.bigdata.modules.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.metadata.entity.MetadataSyncLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MetadataSyncLogMapper extends BaseMapper<MetadataSyncLog> {
}
