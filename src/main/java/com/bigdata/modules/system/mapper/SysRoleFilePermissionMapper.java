package com.bigdata.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.system.entity.SysRoleFilePermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SysRoleFilePermissionMapper extends BaseMapper<SysRoleFilePermission> {

    List<Long> selectRoleIdsByPermissionId(@Param("permissionId") Long permissionId);

    List<SysRoleFilePermission> selectByPermissionIds(@Param("permissionIds") List<Long> permissionIds);

    List<SysRoleFilePermission> selectByRoleIds(@Param("roleIds") List<Long> roleIds);

    int deleteByPermissionId(@Param("permissionId") Long permissionId);

    void batchInsert(@Param("list") List<SysRoleFilePermission> list);

    void batchInsertRoleFilePermissions(@Param("roleId") Long roleId, @Param("permissionIds") List<Long> permissionIds, @Param("createTime") LocalDateTime createTime);

    void insertRoleFilePermission(@Param("roleId") Long roleId, @Param("permissionId") Long permissionId, @Param("createTime") LocalDateTime createTime);

    int deleteByRoleId(@Param("roleId") Long roleId);

    List<Long> selectFilePermissionIdsByRoleId(@Param("roleId") Long roleId);
}