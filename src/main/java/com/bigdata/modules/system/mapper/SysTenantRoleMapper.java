package com.bigdata.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.system.entity.SysTenantRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SysTenantRoleMapper extends BaseMapper<SysTenantRole> {

    List<SysTenantRole> selectByTenantIds(@Param("tenantIds") List<Long> tenantIds);

    List<Long> selectRoleIdsByTenantId(@Param("tenantId") Long tenantId);

    List<Long> selectTenantIdsByRoleId(@Param("roleId") Long roleId);

    int deleteByTenantId(@Param("tenantId") Long tenantId);

    int deleteByRoleId(@Param("roleId") Long roleId);

    void batchInsertTenantRoles(@Param("list") List<SysTenantRole> list);
}