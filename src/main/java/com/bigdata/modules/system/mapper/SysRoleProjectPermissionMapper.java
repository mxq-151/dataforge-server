package com.bigdata.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.system.entity.SysRoleProjectPermission;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface SysRoleProjectPermissionMapper extends BaseMapper<SysRoleProjectPermission> {

    @Select("SELECT * FROM sys_role_project_permission WHERE role_id = #{roleId} AND project_id = #{projectId}")
    SysRoleProjectPermission selectByRoleIdAndProjectId(@Param("roleId") Long roleId, @Param("projectId") Long projectId);

    @Select("SELECT * FROM sys_role_project_permission WHERE role_id = #{roleId}")
    List<SysRoleProjectPermission> selectByRoleId(@Param("roleId") Long roleId);

    @Select("SELECT * FROM sys_role_project_permission WHERE project_id = #{projectId}")
    List<SysRoleProjectPermission> selectByProjectId(@Param("projectId") Long projectId);

    @Delete("DELETE FROM sys_role_project_permission WHERE role_id = #{roleId} AND project_id = #{projectId}")
    int deleteByRoleIdAndProjectId(@Param("roleId") Long roleId, @Param("projectId") Long projectId);

    @Delete("DELETE FROM sys_role_project_permission WHERE role_id = #{roleId}")
    int deleteByRoleId(@Param("roleId") Long roleId);

    @Delete("DELETE FROM sys_role_project_permission WHERE project_id = #{projectId}")
    int deleteByProjectId(@Param("projectId") Long projectId);
}
