package com.bigdata.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.system.entity.SysRoleMenuPermission;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SysRoleMenuPermissionMapper extends BaseMapper<SysRoleMenuPermission> {

    @Select("SELECT * FROM sys_role_menu_permission")
    List<SysRoleMenuPermission> selectRolePermission();

    @Insert("insert IGNORE INTO sys_role_menu_permission(role_id,menu_permission_id,create_time) values(#{permission.roleId},#{permission.menuPermissionId},now())")
    void insertPermission(@Param("permission") SysRoleMenuPermission permission);

    @Delete("DELETE FROM sys_role_menu_permission WHERE menu_permission_id = #{permissionId}")
    int deleteByPermissionId(@Param("permissionId") Long permissionId);

    @Insert("<script>" +
            "INSERT INTO sys_role_menu_permission(role_id, menu_permission_id, create_time) VALUES " +
            "<foreach item='permId' collection='permissionIds' separator=','>" +
            "(#{roleId}, #{permId}, #{createTime})" +
            "</foreach>" +
            "</script>")
    void batchInsertRoleMenuPermissions(@Param("roleId") Long roleId, @Param("permissionIds") List<Long> permissionIds, @Param("createTime") LocalDateTime createTime);

    @Insert("INSERT INTO sys_role_menu_permission(role_id, menu_permission_id, create_time) VALUES(#{roleId}, #{permissionId}, #{createTime})")
    void insertRoleMenuPermission(@Param("roleId") Long roleId, @Param("permissionId") Long permissionId, @Param("createTime") LocalDateTime createTime);

    @Delete("DELETE FROM sys_role_menu_permission WHERE role_id = #{roleId}")
    int deleteByRoleId(@Param("roleId") Long roleId);

    @Select("SELECT menu_permission_id FROM sys_role_menu_permission WHERE role_id = #{roleId}")
    List<Long> selectMenuPermissionIdsByRoleId(@Param("roleId") Long roleId);

    @Select("<script>" +
            "SELECT menu_permission_id FROM sys_role_menu_permission WHERE role_id IN " +
            "<foreach item='roleId' collection='roleIds' open='(' separator=',' close=')'>" +
            "#{roleId}" +
            "</foreach>" +
            "</script>")
    List<Long> selectMenuPermissionIdsByRoleIds(@Param("roleIds") List<Long> roleIds);
}
