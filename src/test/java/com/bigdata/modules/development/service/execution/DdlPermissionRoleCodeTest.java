package com.bigdata.modules.development.service.execution;

import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.system.entity.SysRole;
import com.bigdata.modules.system.entity.SysUser;
import com.bigdata.modules.system.mapper.SysRoleMapper;
import com.bigdata.modules.system.mapper.SysTenantRoleMapper;
import com.bigdata.modules.system.mapper.SysTenantUserMapper;
import com.bigdata.modules.system.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DDL 权限的角色口径。
 *
 * <p>修复前比较的是 {@code "admin"} / {@code "ops"}，而库里的角色码是
 * {@code SUPER_ADMIN} / {@code OPS_ENGINEER}，分支恒不成立 ——
 * 运维工程师即使被授予 OPS_ENGINEER 也拿不到 DDL 权限，与报错文案
 * 「超级管理员和运维人员才能执行DDL」矛盾。</p>
 */
@DisplayName("Development模块 - DDL 权限角色口径")
class DdlPermissionRoleCodeTest {

    private static final Logger LOG = LoggerFactory.getLogger(DdlPermissionRoleCodeTest.class);

    private TestStrategy strategy;
    private SysUserMapper sysUserMapper;
    private SysTenantUserMapper sysTenantUserMapper;
    private SysTenantRoleMapper sysTenantRoleMapper;
    private SysRoleMapper sysRoleMapper;

    private static class TestStrategy extends AbstractBaseExecutionStrategy {
        @Override
        protected Logger getLog() {
            return LOG;
        }

        @Override
        public CodeExecutionResult execute(CodeExecutionContext context) {
            return null;
        }

        @Override
        public DatasourceType getEngineType() {
            return DatasourceType.POSTGRESQL;
        }
    }

    @BeforeEach
    void setUp() {
        strategy = new TestStrategy();
        strategy.dataAccessPermissionService = mock(DataAccessPermissionService.class);
        sysUserMapper = mock(SysUserMapper.class);
        sysTenantUserMapper = mock(SysTenantUserMapper.class);
        sysTenantRoleMapper = mock(SysTenantRoleMapper.class);
        sysRoleMapper = mock(SysRoleMapper.class);
        strategy.sysUserMapper = sysUserMapper;
        strategy.sysTenantUserMapper = sysTenantUserMapper;
        strategy.sysTenantRoleMapper = sysTenantRoleMapper;
        strategy.sysRoleMapper = sysRoleMapper;
        strategy.executionLogListener = mock(ExecutionLogListener.class);
    }

    @Test
    @DisplayName("OPS_ENGINEER 角色拥有 DDL 权限")
    void opsEngineerHasDdlPermission() {
        stubUserWithRole("OPS_ENGINEER");

        assertTrue(strategy.hasDdlPermission(1001L));
        assertDoesNotThrow(() -> strategy.checkPermission(contextWith("CREATE TABLE demo_t (id INT)")));
    }

    @Test
    @DisplayName("SUPER_ADMIN 角色（数据库写法，无 ROLE_ 前缀）拥有 DDL 权限")
    void superAdminCodeHasDdlPermission() {
        stubUserWithRole("SUPER_ADMIN");

        assertTrue(strategy.hasDdlPermission(1001L));
    }

    @Test
    @DisplayName("普通业务角色没有 DDL 权限，仍按 fail-close 拒绝")
    void businessRoleHasNoDdlPermission() {
        stubUserWithRole("DATA_ANALYST");

        assertFalse(strategy.hasDdlPermission(1001L));
        org.junit.jupiter.api.Assertions.assertThrows(SecurityException.class,
                () -> strategy.checkPermission(contextWith("CREATE TABLE demo_t (id INT)")));
    }

    private void stubUserWithRole(String roleCode) {
        SysUser user = new SysUser();
        user.setIsAdmin(0);
        when(sysUserMapper.selectById(any())).thenReturn(user);
        when(sysTenantUserMapper.selectTenantIdByUserId(any())).thenReturn(1L);
        when(sysTenantRoleMapper.selectRoleIdsByTenantId(any())).thenReturn(List.of(2L));
        SysRole role = new SysRole();
        role.setRoleCode(roleCode);
        role.setRoleName(roleCode);
        when(sysRoleMapper.selectBatchIds(any())).thenReturn(List.of(role));
    }

    private CodeExecutionContext contextWith(String sql) {
        DatasourceConfig config = new DatasourceConfig();
        config.setDatasourceName("pg-test");
        config.setDatasourceType(DatasourceType.POSTGRESQL);
        config.setDatabaseName(List.of("testdb"));

        CodeExecutionContext context = new CodeExecutionContext();
        context.setCodeType(CodeType.SQL);
        context.setEngineType(DatasourceType.POSTGRESQL);
        context.setCodeContent(sql);
        context.setSubmitBy(1001L);
        context.setSubmitByName("ops");
        context.setDatasourceConfigs(List.of(config));
        return context;
    }
}
