package com.bigdata.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.system.entity.SysRoleMenuPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SysRoleMenuPermissionMapper extends BaseMapper<SysRoleMenuPermission> {

    List<SysRoleMenuPermission> selectRolePermission();

    /** 方言见 mapper/mysql/SysRoleMenuPermissionMapper.xml（INSERT IGNORE） */
    void insertPermission(@Param("permission") SysRoleMenuPermission permission);

    int deleteByPermissionId(@Param("permissionId") Long permissionId);

    void batchInsertRoleMenuPermissions(@Param("roleId") Long roleId, @Param("permissionIds") List<Long> permissionIds, @Param("createTime") LocalDateTime createTime);

    void insertRoleMenuPermission(@Param("roleId") Long roleId, @Param("permissionId") Long permissionId, @Param("createTime") LocalDateTime createTime);

    int deleteByRoleId(@Param("roleId") Long roleId);

    List<Long> selectMenuPermissionIdsByRoleId(@Param("roleId") Long roleId);

    List<Long> selectMenuPermissionIdsByRoleIds(@Param("roleIds") List<Long> roleIds);
}