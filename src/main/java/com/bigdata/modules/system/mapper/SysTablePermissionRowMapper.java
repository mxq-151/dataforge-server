package com.bigdata.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.system.entity.SysTablePermissionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SysTablePermissionRowMapper extends BaseMapper<SysTablePermissionRow> {

    List<SysTablePermissionRow> selectByPermissionIds(@Param("permissionIds") List<Long> permissionIds);

    List<SysTablePermissionRow> selectByPermissionIdAndTable(@Param("permissionId") Long permissionId,
                                                             @Param("tableName") String tableName);

    int deleteByPermissionId(@Param("permissionId") Long permissionId);

    void batchInsert(@Param("list") List<SysTablePermissionRow> list);
}