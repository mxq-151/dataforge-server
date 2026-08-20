package com.bigdata.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.system.entity.SysTenantUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SysTenantUserMapper extends BaseMapper<SysTenantUser> {

    List<SysTenantUser> selectByTenantId(@Param("tenantId") Long tenantId);

    SysTenantUser selectByUserId(@Param("userId") Long userId);

    List<Long> selectUserIdsByTenantId(@Param("tenantId") Long tenantId);

    Long selectTenantIdByUserId(@Param("userId") Long userId);

    int deleteByTenantId(@Param("tenantId") Long tenantId);

    int deleteByUserId(@Param("userId") Long userId);
}