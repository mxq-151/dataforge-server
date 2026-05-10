package com.bigdata.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.system.entity.SysUserRole;
import org.apache.ibatis.annotations.*;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {

    @Select("<script>" +
            "SELECT * FROM sys_user_role WHERE user_id IN " +
            "<foreach item='itemId' collection='userIds' open='(' separator=',' close=')'>" +
            "#{itemId}" +
            "</foreach>" +
            "</script>")
    List<SysUserRole> selectByUserIds(@Param("userIds") List<Long> userIds);

    @Select("SELECT role_id FROM sys_user_role WHERE user_id = #{userId}")
    List<Long> selectRoleIdsByUserId(@Param("userId") Long userId);

    @Select("SELECT user_id FROM sys_user_role WHERE role_id = #{roleId}")
    List<Long> selectUserIdsByRoleId(@Param("roleId") Long roleId);

    @Delete("DELETE FROM sys_user_role WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") Long userId);

    @Delete("DELETE FROM sys_user_role WHERE role_id = #{roleId}")
    int deleteByRoleId(@Param("roleId") Long roleId);

    @Insert("INSERT INTO sys_role_menu_permission(role_id, menu_permission_id, create_time) VALUES(#{roleId}, #{permissionId}, #{createTime})")
    void insertRoleMenuPermission(@Param("roleId") Long roleId, @Param("permissionId") Long permissionId, @Param("createTime") LocalDateTime createTime);

    @Insert("INSERT INTO sys_role_button_permission(role_id, button_permission_id, create_time) VALUES(#{roleId}, #{permissionId}, #{createTime})")
    void insertRoleButtonPermission(@Param("roleId") Long roleId, @Param("permissionId") Long permissionId, @Param("createTime") LocalDateTime createTime);

    @Insert("INSERT INTO sys_role_file_permission(role_id, file_permission_id, create_time) VALUES(#{roleId}, #{permissionId}, #{createTime})")
    void insertRoleFilePermission(@Param("roleId") Long roleId, @Param("permissionId") Long permissionId, @Param("createTime") LocalDateTime createTime);

    @Insert("INSERT INTO sys_role_table_permission(role_id, table_permission_id, create_time) VALUES(#{roleId}, #{permissionId}, #{createTime})")
    void insertRoleTablePermission(@Param("roleId") Long roleId, @Param("permissionId") Long permissionId, @Param("createTime") LocalDateTime createTime);

    @Delete("DELETE FROM sys_role_menu_permission WHERE role_id = #{roleId}")
    int deleteRoleMenuPermissions(@Param("roleId") Long roleId);

    @Delete("DELETE FROM sys_role_button_permission WHERE role_id = #{roleId}")
    int deleteRoleButtonPermissions(@Param("roleId") Long roleId);

    @Delete("DELETE FROM sys_role_file_permission WHERE role_id = #{roleId}")
    int deleteRoleFilePermissions(@Param("roleId") Long roleId);

    @Delete("DELETE FROM sys_role_table_permission WHERE role_id = #{roleId}")
    int deleteRoleTablePermissions(@Param("roleId") Long roleId);

    @Select("SELECT menu_permission_id FROM sys_role_menu_permission WHERE role_id = #{roleId}")
    List<Long> selectMenuPermissionIdsByRoleId(@Param("roleId") Long roleId);

    @Select("SELECT button_permission_id FROM sys_role_button_permission WHERE role_id = #{roleId}")
    List<Long> selectButtonPermissionIdsByRoleId(@Param("roleId") Long roleId);

    @Select("SELECT file_permission_id FROM sys_role_file_permission WHERE role_id = #{roleId}")
    List<Long> selectFilePermissionIdsByRoleId(@Param("roleId") Long roleId);

    @Select("SELECT table_permission_id FROM sys_role_table_permission WHERE role_id = #{roleId}")
    List<Long> selectTablePermissionIdsByRoleId(@Param("roleId") Long roleId);
}
