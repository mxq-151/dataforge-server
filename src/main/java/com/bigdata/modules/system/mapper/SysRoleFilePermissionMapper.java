package com.bigdata.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.system.entity.SysRoleFilePermission;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysRoleFilePermissionMapper extends BaseMapper<SysRoleFilePermission> {

    @Select("SELECT role_id FROM sys_role_file_permission WHERE file_permission_id = #{permissionId}")
    List<Long> selectRoleIdsByPermissionId(@Param("permissionId") Long permissionId);

    @Delete("DELETE FROM sys_role_file_permission WHERE file_permission_id = #{permissionId}")
    int deleteByPermissionId(@Param("permissionId") Long permissionId);
}
