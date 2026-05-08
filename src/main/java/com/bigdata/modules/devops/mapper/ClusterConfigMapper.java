package com.bigdata.modules.devops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.devops.entity.ClusterConfig;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ClusterConfigMapper extends BaseMapper<ClusterConfig> {
}
