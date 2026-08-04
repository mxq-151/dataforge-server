package com.bigdata.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.system.entity.SysRoleFilePermission;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SysRoleFilePermissionMapper extends BaseMapper<SysRoleFilePermission> {

    @Select("SELECT role_id FROM sys_role_file_permission WHERE file_permission_id = #{permissionId}")
    List<Long> selectRoleIdsByPermissionId(@Param("permissionId") Long permissionId);

    @Select("<script>" +
            "SELECT file_permission_id AS filePermissionId, role_id AS roleId FROM sys_role_file_permission WHERE file_permission_id IN " +
            "<foreach item='permId' collection='permissionIds' open='(' separator=',' close=')'>" +
            "#{permId}" +
            "</foreach>" +
            "</script>")
    List<SysRoleFilePermission> selectByPermissionIds(@Param("permissionIds") List<Long> permissionIds);

    @Select("<script>" +
            "SELECT file_permission_id AS filePermissionId, role_id AS roleId FROM sys_role_file_permission WHERE role_id IN " +
            "<foreach item='roleId' collection='roleIds' open='(' separator=',' close=')'>" +
            "#{roleId}" +
            "</foreach>" +
            "</script>")
    List<SysRoleFilePermission> selectByRoleIds(@Param("roleIds") List<Long> roleIds);

    @Delete("DELETE FROM sys_role_file_permission WHERE file_permission_id = #{permissionId}")
    int deleteByPermissionId(@Param("permissionId") Long permissionId);

    @Insert("<script>" +
            "INSERT INTO sys_role_file_permission(role_id, file_permission_id, create_time) VALUES " +
            "<foreach item='item' collection='list' separator=','>" +
            "(#{item.roleId}, #{item.filePermissionId}, #{item.createTime})" +
            "</foreach>" +
            "</script>")
    void batchInsert(@Param("list") List<SysRoleFilePermission> list);

    @Insert("<script>" +
            "INSERT INTO sys_role_file_permission(role_id, file_permission_id, create_time) VALUES " +
            "<foreach item='permId' collection='permissionIds' separator=','>" +
            "(#{roleId}, #{permId}, #{createTime})" +
            "</foreach>" +
            "</script>")
    void batchInsertRoleFilePermissions(@Param("roleId") Long roleId, @Param("permissionIds") List<Long> permissionIds, @Param("createTime") LocalDateTime createTime);

    @Insert("INSERT INTO sys_role_file_permission(role_id, file_permission_id, create_time) VALUES(#{roleId}, #{permissionId}, #{createTime})")
    void insertRoleFilePermission(@Param("roleId") Long roleId, @Param("permissionId") Long permissionId, @Param("createTime") LocalDateTime createTime);

    @Delete("DELETE FROM sys_role_file_permission WHERE role_id = #{roleId}")
    int deleteByRoleId(@Param("roleId") Long roleId);

    @Select("SELECT file_permission_id FROM sys_role_file_permission WHERE role_id = #{roleId}")
    List<Long> selectFilePermissionIdsByRoleId(@Param("roleId") Long roleId);
}
