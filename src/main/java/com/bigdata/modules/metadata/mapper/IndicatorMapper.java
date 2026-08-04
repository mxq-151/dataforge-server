package com.bigdata.modules.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.metadata.entity.Indicator;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IndicatorMapper extends BaseMapper<Indicator> {

    @Insert("INSERT INTO indicators (project_code, topic, name, code, business_definition, calc_expr, " +
            "default_filter, alert_config, business_owner, tech_owner, refresh_interval, refresh_unit, " +
            "refresh_desc, visibility) " +
            "VALUES (#{projectCode}, #{topic}, #{name}, #{code}, #{businessDefinition}, #{calcExpr}, " +
            "#{defaultFilter}, #{alertConfig}, #{businessOwner}, #{techOwner}, #{refreshInterval}, #{refreshUnit}, " +
            "#{refreshDesc}, #{visibility}) " +
            "ON DUPLICATE KEY UPDATE " +
            "project_code = VALUES(project_code), topic = VALUES(topic), name = VALUES(name), " +
            "business_definition = VALUES(business_definition), calc_expr = VALUES(calc_expr), " +
            "default_filter = VALUES(default_filter), alert_config = VALUES(alert_config), " +
            "business_owner = VALUES(business_owner), tech_owner = VALUES(tech_owner), " +
            "refresh_interval = VALUES(refresh_interval), refresh_unit = VALUES(refresh_unit), " +
            "refresh_desc = VALUES(refresh_desc), visibility = VALUES(visibility), " +
            "updated_at = NOW()")
    int upsertByCode(Indicator indicator);
}
