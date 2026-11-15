package com.bigdata.common.security;

import com.bigdata.modules.system.entity.SysRole;
import com.bigdata.modules.system.entity.SysUser;
import com.bigdata.modules.system.mapper.SysRoleMapper;
import com.bigdata.modules.system.mapper.SysTenantRoleMapper;
import com.bigdata.modules.system.mapper.SysTenantUserMapper;
import com.bigdata.modules.system.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 管理员判定的角色口径必须与数据库一致。
 *
 * <p>数据库与初始化脚本里的角色码是 {@code SUPER_ADMIN} / {@code OPS_ENGINEER}
 * （不带 ROLE_ 前缀）；JWT 权限串才是 {@code ROLE_+角色码}。修复前
 * {@code isAdmin(Long)} 拿 {@code "ROLE_SUPER_ADMIN"} 去比数据库 role_code，
 * 恒不成立，只剩 sys_user.is_admin 一条路。</p>
 */
@DisplayName("Common模块 - 管理员角色口径")
class DataAccessPermissionRoleCodeTest {

    private DataAccessPermissionService service;
    private SysUserMapper sysUserMapper;
    private SysTenantUserMapper sysTenantUserMapper;
    private SysTenantRoleMapper sysTenantRoleMapper;
    private SysRoleMapper sysRoleMapper;

    @BeforeEach
    void setUp() {
        service = new DataAccessPermissionService();
        sysUserMapper = mock(SysUserMapper.class);
        sysTenantUserMapper = mock(SysTenantUserMapper.class);
        sysTenantRoleMapper = mock(SysTenantRoleMapper.class);
        sysRoleMapper = mock(SysRoleMapper.class);
        ReflectionTestUtils.setField(service, "sysUserMapper", sysUserMapper);
        ReflectionTestUtils.setField(service, "sysTenantUserMapper", sysTenantUserMapper);
        ReflectionTestUtils.setField(service, "sysTenantRoleMapper", sysTenantRoleMapper);
        ReflectionTestUtils.setField(service, "sysRoleMapper", sysRoleMapper);
    }

    @Test
    @DisplayName("is_admin=0 但租户角色为 SUPER_ADMIN：按数据库角色码判定为管理员")
    void recognizesSuperAdminRoleCodeFromDatabase() {
        stubUserWithRoles("SUPER_ADMIN");

        assertTrue(service.isAdmin(9L), "数据库角色码 SUPER_ADMIN 必须被识别");
    }

    @Test
    @DisplayName("普通业务角色不因角色判定被放行")
    void doesNotTreatBusinessRoleAsAdmin() {
        stubUserWithRoles("DATA_ANALYST");

        assertFalse(service.isAdmin(9L));
    }

    @Test
    @DisplayName("sys_user.is_admin=1 仍然直接判定为管理员")
    void honorsUserAdminFlag() {
        SysUser user = new SysUser();
        user.setIsAdmin(1);
        when(sysUserMapper.selectById(any())).thenReturn(user);

        assertTrue(service.isAdmin(9L));
    }

    private void stubUserWithRoles(String roleCode) {
        SysUser user = new SysUser();
        user.setIsAdmin(0);
        when(sysUserMapper.selectById(any())).thenReturn(user);
        when(sysTenantUserMapper.selectTenantIdByUserId(any())).thenReturn(1L);
        when(sysTenantRoleMapper.selectRoleIdsByTenantId(any())).thenReturn(List.of(2L));
        SysRole role = new SysRole();
        role.setRoleCode(roleCode);
        when(sysRoleMapper.selectBatchIds(any())).thenReturn(List.of(role));
    }
}
