package com.bigdata.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.system.entity.SysRoleTablePermission;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface SysRoleTablePermissionMapper extends BaseMapper<SysRoleTablePermission> {

    @Select("SELECT role_id FROM sys_role_table_permission WHERE table_permission_id = #{permissionId}")
    List<Long> selectRoleIdsByPermissionId(@Param("permissionId") Long permissionId);

    @Select("<script>" +
            "SELECT table_permission_id AS permissionId, role_id AS roleId FROM sys_role_table_permission WHERE table_permission_id IN " +
            "<foreach item='permId' collection='permissionIds' open='(' separator=',' close=')'>" +
            "#{permId}" +
            "</foreach>" +
            "</script>")
    List<SysRoleTablePermission> selectByPermissionIds(@Param("permissionIds") List<Long> permissionIds);

    @Delete("DELETE FROM sys_role_table_permission WHERE table_permission_id = #{permissionId}")
    int deleteByPermissionId(@Param("permissionId") Long permissionId);

    @Insert("<script>" +
            "INSERT INTO sys_role_table_permission(role_id, table_permission_id, create_time) VALUES " +
            "<foreach item='item' collection='list' separator=','>" +
            "(#{item.roleId}, #{item.tablePermissionId}, #{item.createTime})" +
            "</foreach>" +
            "</script>")
    void batchInsert(@Param("list") List<SysRoleTablePermission> list);
}
