package com.bigdata.modules.development.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.development.entity.ProgramParams;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProgramParamsMapper extends BaseMapper<ProgramParams> {

    /**
     * 数据库原生 SQL upsert（按联合主键 project_id + file_path 冲突时更新）。
     * 方言实现见 mapper/{mysql,mariadb,postgresql,h2}/ProgramParamsMapper.xml。
     *
     * @param entity 程序参数实体
     * @return 影响行数（MySQL ODKU：插入=1，更新=2，值相同无变化=0）
     */
    int upsert(@Param("entity") ProgramParams entity);
}
