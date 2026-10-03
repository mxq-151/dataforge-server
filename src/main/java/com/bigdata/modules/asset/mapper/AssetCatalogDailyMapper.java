package com.bigdata.modules.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.asset.entity.AssetCatalogDaily;
import org.apache.ibatis.annotations.Mapper;

/**
 * AssetCatalogDaily Mapper。
 *
 * <p>与 asset 模块其余 Mapper 一致，只继承 BaseMapper、不声明自定义方法：
 * 全部走 MyBatis-Plus 生成的可移植 SQL，避免为多方言各维护一份 XML。</p>
 *
 * @author dataforge
 */
@Mapper
public interface AssetCatalogDailyMapper extends BaseMapper<AssetCatalogDaily> {
}
