package com.bigdata.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.system.entity.SysTenantRole;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface SysTenantRoleMapper extends BaseMapper<SysTenantRole> {

    @Select("<script>" +
            "SELECT * FROM sys_tenant_role WHERE tenant_id IN " +
            "<foreach item='itemId' collection='tenantIds' open='(' separator=',' close=')'>" +
            "#{itemId}" +
            "</foreach>" +
            "</script>")
    List<SysTenantRole> selectByTenantIds(@Param("tenantIds") List<Long> tenantIds);

    @Select("SELECT role_id FROM sys_tenant_role WHERE tenant_id = #{tenantId}")
    List<Long> selectRoleIdsByTenantId(@Param("tenantId") Long tenantId);

    @Select("SELECT tenant_id FROM sys_tenant_role WHERE role_id = #{roleId}")
    List<Long> selectTenantIdsByRoleId(@Param("roleId") Long roleId);

    @Delete("DELETE FROM sys_tenant_role WHERE tenant_id = #{tenantId}")
    int deleteByTenantId(@Param("tenantId") Long tenantId);

    @Delete("DELETE FROM sys_tenant_role WHERE role_id = #{roleId}")
    int deleteByRoleId(@Param("roleId") Long roleId);

    @Insert("<script>" +
            "INSERT INTO sys_tenant_role(tenant_id, role_id, create_time) VALUES " +
            "<foreach item='item' collection='list' separator=','>" +
            "(#{item.tenantId}, #{item.roleId}, #{item.createTime})" +
            "</foreach>" +
            "</script>")
    void batchInsertTenantRoles(@Param("list") List<SysTenantRole> list);
}
