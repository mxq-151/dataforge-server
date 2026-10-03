package com.bigdata.modules.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.asset.entity.SchedulerTaskRunDaily;
import org.apache.ibatis.annotations.Mapper;

/**
 * SchedulerTaskRunDaily Mapper。
 *
 * <p>与 asset 模块其余 Mapper 一致，只继承 BaseMapper、不声明自定义方法。</p>
 *
 * @author dataforge
 */
@Mapper
public interface SchedulerTaskRunDailyMapper extends BaseMapper<SchedulerTaskRunDaily> {
}
