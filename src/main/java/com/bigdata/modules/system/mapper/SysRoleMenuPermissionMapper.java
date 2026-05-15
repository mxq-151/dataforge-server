package com.bigdata.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.system.entity.SysRoleMenuPermission;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysRoleMenuPermissionMapper extends BaseMapper<SysRoleMenuPermission> {

    @Select("SELECT * FROM sys_role_menu_permission")
    List<SysRoleMenuPermission> selectRolePermission();

    @Insert("insert IGNORE INTO sys_role_menu_permission(role_id,menu_permission_id,create_time) values(#{permission.roleId},#{permission.menuPermissionId},now())")
    void insertPermission(@Param("permission") SysRoleMenuPermission permission);

    @Delete("DELETE FROM sys_role_menu_permission WHERE menu_permission_id = #{permissionId}")
    int deleteByPermissionId(@Param("permissionId") Long permissionId);
}
