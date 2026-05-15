package com.bigdata.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.system.entity.SysTenantUser;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface SysTenantUserMapper extends BaseMapper<SysTenantUser> {

    @Select("SELECT * FROM sys_tenant_user WHERE tenant_id = #{tenantId}")
    List<SysTenantUser> selectByTenantId(@Param("tenantId") Long tenantId);

    @Select("SELECT * FROM sys_tenant_user WHERE user_id = #{userId}")
    SysTenantUser selectByUserId(@Param("userId") Long userId);

    @Select("SELECT user_id FROM sys_tenant_user WHERE tenant_id = #{tenantId}")
    List<Long> selectUserIdsByTenantId(@Param("tenantId") Long tenantId);

    @Select("SELECT tenant_id FROM sys_tenant_user WHERE user_id = #{userId}")
    Long selectTenantIdByUserId(@Param("userId") Long userId);

    @Delete("DELETE FROM sys_tenant_user WHERE tenant_id = #{tenantId}")
    int deleteByTenantId(@Param("tenantId") Long tenantId);

    @Delete("DELETE FROM sys_tenant_user WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") Long userId);
}
