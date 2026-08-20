package com.bigdata.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.system.entity.SysRoleTablePermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SysRoleTablePermissionMapper extends BaseMapper<SysRoleTablePermission> {

    List<Long> selectRoleIdsByPermissionId(@Param("permissionId") Long permissionId);

    List<SysRoleTablePermission> selectByPermissionIds(@Param("permissionIds") List<Long> permissionIds);

    List<SysRoleTablePermission> selectByRoleIds(@Param("roleIds") List<Long> roleIds);

    int deleteByPermissionId(@Param("permissionId") Long permissionId);

    void batchInsert(@Param("list") List<SysRoleTablePermission> list);

    void batchInsertRoleTablePermissions(@Param("roleId") Long roleId, @Param("permissionIds") List<Long> permissionIds, @Param("createTime") LocalDateTime createTime);

    void insertRoleTablePermission(@Param("roleId") Long roleId, @Param("permissionId") Long permissionId, @Param("createTime") LocalDateTime createTime);

    int deleteByRoleId(@Param("roleId") Long roleId);

    List<Long> selectTablePermissionIdsByRoleId(@Param("roleId") Long roleId);
}