package com.bigdata.common.security;

import com.bigdata.modules.development.mapper.DevProjectMapper;
import com.bigdata.modules.system.entity.SysTablePermission;
import com.bigdata.modules.system.entity.SysTablePermissionColumn;
import com.bigdata.modules.system.entity.SysTablePermissionRow;
import com.bigdata.modules.system.entity.SysRoleTablePermission;
import com.bigdata.modules.system.mapper.SysFilePermissionMapper;
import com.bigdata.modules.system.mapper.SysRoleFilePermissionMapper;
import com.bigdata.modules.system.mapper.SysRoleProjectPermissionMapper;
import com.bigdata.modules.system.mapper.SysRoleTablePermissionMapper;
import com.bigdata.modules.system.mapper.SysTablePermissionColumnMapper;
import com.bigdata.modules.system.mapper.SysTablePermissionMapper;
import com.bigdata.modules.system.mapper.SysTablePermissionRowMapper;
import com.bigdata.modules.system.mapper.SysTenantRoleMapper;
import com.bigdata.modules.system.mapper.SysTenantUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 行 / 列级权限的合并语义验证（风险项 R4）。
 * <p>
 * 旧实现是「任一命中权限未对该表做限制，即整体放宽」（行过滤直接返回 null、列白名单取并集），
 * 多角色并集时宽权限会静默覆盖窄权限。现要求取交集收紧：某条权限不额外限制，
 * 只是它自己不加约束，不能把别的权限已经收窄的约束放开。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataAccessPermissionMergeSemanticsTest {

    private static final Long USER_ID = 9001L;
    private static final Long TENANT_ID = 77L;
    private static final String DS = "pg-test";
    private static final String DB = "testdb";
    private static final String TABLE = "orders";

    @Mock
    private SysTenantUserMapper sysTenantUserMapper;
    @Mock
    private SysTenantRoleMapper sysTenantRoleMapper;
    @Mock
    private SysRoleFilePermissionMapper sysRoleFilePermissionMapper;
    @Mock
    private SysRoleTablePermissionMapper sysRoleTablePermissionMapper;
    @Mock
    private SysRoleProjectPermissionMapper sysRoleProjectPermissionMapper;
    @Mock
    private SysFilePermissionMapper sysFilePermissionMapper;
    @Mock
    private SysTablePermissionMapper sysTablePermissionMapper;
    @Mock
    private SysTablePermissionColumnMapper tablePermissionColumnMapper;
    @Mock
    private SysTablePermissionRowMapper tablePermissionRowMapper;
    @Mock
    private DevProjectMapper devProjectMapper;

    @InjectMocks
    private DataAccessPermissionService service;

    @BeforeEach
    void setUp() {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_ANALYST"));
        CustomUserDetails userDetails = new CustomUserDetails(
                USER_ID, "analyst", TENANT_ID, List.of("ROLE_ANALYST"), false, authorities, false);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 两个角色各自绑定了同一张表的两条权限
        when(sysTenantUserMapper.selectTenantIdByUserId(USER_ID)).thenReturn(TENANT_ID);
        when(sysTenantRoleMapper.selectRoleIdsByTenantId(TENANT_ID)).thenReturn(List.of(10L, 11L));
        when(sysRoleTablePermissionMapper.selectByRoleIds(List.of(10L, 11L))).thenReturn(List.of(
                roleBinding(100L), roleBinding(101L)));
        when(sysTablePermissionMapper.selectBatchIds(any())).thenReturn(List.of(
                permission(100L, null), permission(101L, null)));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private SysRoleTablePermission roleBinding(Long permissionId) {
        SysRoleTablePermission binding = new SysRoleTablePermission();
        binding.setTablePermissionId(permissionId);
        return binding;
    }

    private SysTablePermission permission(Long id, String maskingRule) {
        SysTablePermission permission = new SysTablePermission();
        permission.setId(id);
        permission.setDatasourceName(DS);
        permission.setDatabaseName(DB);
        permission.setTableNames("*");
        permission.setCanSelect(1);
        permission.setDataMaskingRule(maskingRule);
        return permission;
    }

    private SysTablePermissionRow row(Long permissionId, String filter) {
        SysTablePermissionRow row = new SysTablePermissionRow();
        row.setTablePermissionId(permissionId);
        row.setTableName(TABLE);
        row.setRowFilterSql(filter);
        return row;
    }

    private SysTablePermissionColumn column(Long permissionId, String name) {
        SysTablePermissionColumn column = new SysTablePermissionColumn();
        column.setTablePermissionId(permissionId);
        column.setTableName(TABLE);
        column.setColumnName(name);
        return column;
    }

    // ==================== 行级 ====================

    @Test
    void rowFilterSurvivesPermissionWithoutRowBinding() {
        // 权限100 配了行过滤，权限101 没配：不能因为「有一条没配」就整体放宽
        when(tablePermissionRowMapper.selectByPermissionIdAndTable(100L, TABLE))
                .thenReturn(List.of(row(100L, "region = 'cn'")));
        when(tablePermissionRowMapper.selectByPermissionIdAndTable(101L, TABLE))
                .thenReturn(List.of());

        String merged = service.getMergedRowFilterSql(DS, DB, TABLE);

        assertNotNull(merged, "有一条权限配置了行过滤，就不应整体放宽为不过滤");
        assertTrue(merged.contains("region = 'cn'"), merged);
    }

    @Test
    void rowFiltersFromMultiplePermissionsAreAnded() {
        when(tablePermissionRowMapper.selectByPermissionIdAndTable(100L, TABLE))
                .thenReturn(List.of(row(100L, "region = 'cn'")));
        when(tablePermissionRowMapper.selectByPermissionIdAndTable(101L, TABLE))
                .thenReturn(List.of(row(101L, "status = 'ok'")));

        String merged = service.getMergedRowFilterSql(DS, DB, TABLE);

        assertNotNull(merged);
        assertTrue(merged.contains("region = 'cn'") && merged.contains("status = 'ok'"), merged);
        assertTrue(merged.contains(" AND "), merged);
    }

    @Test
    void noRowFilterWhenNoPermissionConfiguresIt() {
        when(tablePermissionRowMapper.selectByPermissionIdAndTable(any(), eq(TABLE)))
                .thenReturn(List.of());

        assertNull(service.getMergedRowFilterSql(DS, DB, TABLE),
                "所有命中权限都没配行过滤时才应返回 null（不限制）");
    }

    // ==================== 列级 ====================

    @Test
    void columnWhitelistSurvivesPermissionWithoutColumnBinding() {
        when(tablePermissionColumnMapper.selectByPermissionIdAndTable(100L, TABLE))
                .thenReturn(List.of(column(100L, "id"), column(100L, "amount")));
        when(tablePermissionColumnMapper.selectByPermissionIdAndTable(101L, TABLE))
                .thenReturn(List.of());

        Set<String> allowed = service.getAllowedColumns(DS, DB, TABLE);

        assertEquals(Set.of("id", "amount"), allowed,
                "有一条权限未限制列，不能把另一条权限已收窄的列白名单放开");
    }

    @Test
    void columnWhitelistsAreIntersected() {
        when(tablePermissionColumnMapper.selectByPermissionIdAndTable(100L, TABLE))
                .thenReturn(List.of(column(100L, "id"), column(100L, "amount"), column(100L, "region")));
        when(tablePermissionColumnMapper.selectByPermissionIdAndTable(101L, TABLE))
                .thenReturn(List.of(column(101L, "amount"), column(101L, "id")));

        Set<String> allowed = service.getAllowedColumns(DS, DB, TABLE);

        assertEquals(Set.of("id", "amount"), allowed,
                "多权限的列白名单应取交集（收紧），而不是取并集（放宽）");
    }

    @Test
    void columnWhitelistIsEmptyWhenIntersectionIsEmpty() {
        when(tablePermissionColumnMapper.selectByPermissionIdAndTable(100L, TABLE))
                .thenReturn(List.of(column(100L, "id")));
        when(tablePermissionColumnMapper.selectByPermissionIdAndTable(101L, TABLE))
                .thenReturn(List.of(column(101L, "region")));

        Set<String> allowed = service.getAllowedColumns(DS, DB, TABLE);

        assertNotNull(allowed);
        assertTrue(allowed.isEmpty(), "交集为空时应返回空集合（不授权任何列），而不是 null（不限制）");
    }

    @Test
    void noColumnRestrictionWhenNoPermissionConfiguresIt() {
        when(tablePermissionColumnMapper.selectByPermissionIdAndTable(any(), eq(TABLE)))
                .thenReturn(List.of());

        assertNull(service.getAllowedColumns(DS, DB, TABLE));
    }

    // ==================== 脱敏规则 ====================

    @Test
    void maskingRulesAreMergedPerColumnTakingStricter() {
        when(tablePermissionRowMapper.selectByPermissionIdAndTable(any(), eq(TABLE))).thenReturn(List.of());
        when(tablePermissionColumnMapper.selectByPermissionIdAndTable(any(), eq(TABLE))).thenReturn(List.of());
        when(sysTablePermissionMapper.selectBatchIds(any())).thenReturn(List.of(
                permission(100L, "{\"phone\":\"partial\",\"email\":\"partial\"}"),
                permission(101L, "{\"phone\":\"full\",\"idcard\":\"idcard\"}")));

        SysTablePermission merged = service.getMergedTablePermission(DS, DB, TABLE);

        assertNotNull(merged);
        String rule = merged.getDataMaskingRule();
        assertTrue(rule.contains("\"phone\":\"full\""), "同一列应取更严格的规则: " + rule);
        assertTrue(rule.contains("email"), "列应取并集，不能丢规则: " + rule);
        assertTrue(rule.contains("idcard"), "列应取并集，不能丢规则: " + rule);
    }

    @Test
    void maskingRuleMergeKeepsExistingOnUnparsableInput() {
        assertEquals("{\"phone\":\"full\"}", service.mergeDataMaskingRule("{\"phone\":\"full\"}", "not-json"));
        assertEquals("{\"phone\":\"full\"}", service.mergeDataMaskingRule("{\"phone\":\"full\"}", null));
        assertEquals("{\"a\":\"full\"}", service.mergeDataMaskingRule(null, "{\"a\":\"full\"}"));
    }
}
