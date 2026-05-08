package com.bigdata.modules.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.metadata.entity.DdlExecutionLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DdlExecutionLogMapper extends BaseMapper<DdlExecutionLog> {
}
