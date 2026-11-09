package com.bigdata.modules.integration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.integration.entity.IntegrationTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IntegrationTaskMapper extends BaseMapper<IntegrationTask> {
}
