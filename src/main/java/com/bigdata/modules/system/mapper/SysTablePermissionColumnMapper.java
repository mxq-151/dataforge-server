package com.bigdata.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.system.entity.SysTablePermissionColumn;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SysTablePermissionColumnMapper extends BaseMapper<SysTablePermissionColumn> {

    List<SysTablePermissionColumn> selectByPermissionIds(@Param("permissionIds") List<Long> permissionIds);

    List<SysTablePermissionColumn> selectByPermissionIdAndTable(@Param("permissionId") Long permissionId,
                                                                @Param("tableName") String tableName);

    int deleteByPermissionId(@Param("permissionId") Long permissionId);

    void batchInsert(@Param("list") List<SysTablePermissionColumn> list);
}