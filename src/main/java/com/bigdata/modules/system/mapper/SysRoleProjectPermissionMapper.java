package com.bigdata.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.system.entity.SysRoleProjectPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SysRoleProjectPermissionMapper extends BaseMapper<SysRoleProjectPermission> {

    SysRoleProjectPermission selectByRoleIdAndProjectId(@Param("roleId") Long roleId, @Param("projectId") Long projectId);

    List<SysRoleProjectPermission> selectByRoleId(@Param("roleId") Long roleId);

    List<SysRoleProjectPermission> selectByRoleIds(@Param("roleIds") List<Long> roleIds);

    List<SysRoleProjectPermission> selectByProjectId(@Param("projectId") Long projectId);

    int deleteByRoleIdAndProjectId(@Param("roleId") Long roleId, @Param("projectId") Long projectId);

    int deleteByRoleId(@Param("roleId") Long roleId);

    int deleteByProjectId(@Param("projectId") Long projectId);
}