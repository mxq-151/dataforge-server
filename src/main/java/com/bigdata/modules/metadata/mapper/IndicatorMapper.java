package com.bigdata.modules.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.metadata.entity.Indicator;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IndicatorMapper extends BaseMapper<Indicator> {

    /**
     * 按 code 唯一键 upsert（方言见 mapper/mysql/IndicatorMapper.xml）
     */
    int upsertByCode(Indicator indicator);
}