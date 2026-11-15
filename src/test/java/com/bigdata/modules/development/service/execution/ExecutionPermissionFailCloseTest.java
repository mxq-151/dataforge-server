package com.bigdata.modules.development.service.execution;

import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.system.entity.SysUser;
import com.bigdata.modules.system.mapper.SysTenantRoleMapper;
import com.bigdata.modules.system.mapper.SysTenantUserMapper;
import com.bigdata.modules.system.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 权限校验的 fail-close 与覆盖范围验证（风险项 R4）。
 * <p>
 * 旧实现有两个可绕过点，这里逐条守住：
 * <ol>
 *   <li>整段脚本交给解析器 → 多语句脚本必然解析失败 → 「打 debug 日志后放行」；</li>
 *   <li>表引用只从顶层 fromItem 提取 → JOIN 右侧、子查询里的表完全不检查。</li>
 * </ol>
 * 现在要求：以 DML 关键字开头但无法解析的语句默认拒绝；脚本按分号逐条校验；
 * JOIN / 子查询 / CTE 里的表都要检查。
 * </p>
 */
class ExecutionPermissionFailCloseTest {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutionPermissionFailCloseTest.class);

    private static final String DENIED_TABLE = "secret_t";

    private DataAccessPermissionService permissionService;
    private SysUserMapper sysUserMapper;
    private SysTenantUserMapper sysTenantUserMapper;
    private TestStrategy strategy;

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
        permissionService = mock(DataAccessPermissionService.class);
        sysUserMapper = mock(SysUserMapper.class);
        sysTenantUserMapper = mock(SysTenantUserMapper.class);

        // 非超级管理员、无DDL权限：让 hasDdlPermission 一路返回 false
        when(sysUserMapper.selectById(any())).thenReturn((SysUser) null);
        when(sysTenantUserMapper.selectTenantIdByUserId(any())).thenReturn(null);

        // 只有 secret_t 被拒绝，其余表放行
        when(permissionService.hasTablePermission(any(), any(), anyString(), anyString()))
                .thenAnswer(invocation -> !DENIED_TABLE.equals(invocation.getArgument(2)));

        strategy = new TestStrategy();
        strategy.dataAccessPermissionService = permissionService;
        strategy.sysUserMapper = sysUserMapper;
        strategy.sysTenantUserMapper = sysTenantUserMapper;
        strategy.sysTenantRoleMapper = mock(SysTenantRoleMapper.class);
        strategy.executionLogListener = mock(ExecutionLogListener.class);
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
        context.setSubmitByName("tester");
        context.setDatasourceConfigs(List.of(config));
        return context;
    }

    @Test
    void allowsQueryOnAuthorizedTable() {
        assertDoesNotThrow(() -> strategy.checkPermission(contextWith("SELECT id FROM allowed_t")));
    }

    @Test
    void rejectsQueryOnDeniedTable() {
        SecurityException ex = assertThrows(SecurityException.class,
                () -> strategy.checkPermission(contextWith("SELECT id FROM secret_t")));
        assertTrue(ex.getMessage().contains("secret_t"), ex.getMessage());
    }

    /** 多语句脚本：旧实现整体解析失败后放行，现在必须逐条校验。 */
    @Test
    void checksEveryStatementInMultiStatementScript() {
        SecurityException ex = assertThrows(SecurityException.class, () -> strategy.checkPermission(
                contextWith("SELECT id FROM allowed_t;\nSELECT id FROM secret_t;")));
        assertTrue(ex.getMessage().contains("secret_t"), ex.getMessage());
    }

    /** JOIN 右侧的表：JSqlParser 4.9 下旧反射实现漏检。 */
    @Test
    void checksJoinedTables() {
        SecurityException ex = assertThrows(SecurityException.class, () -> strategy.checkPermission(
                contextWith("SELECT a.id FROM allowed_t a JOIN secret_t b ON a.id = b.id")));
        assertTrue(ex.getMessage().contains("secret_t"), ex.getMessage());
    }

    /** FROM 子句里的派生表。 */
    @Test
    void checksTablesInDerivedTable() {
        assertThrows(SecurityException.class, () -> strategy.checkPermission(
                contextWith("SELECT * FROM (SELECT * FROM secret_t) x")));
    }

    /** WHERE 子查询里的表。 */
    @Test
    void checksTablesInWhereSubQuery() {
        assertThrows(SecurityException.class, () -> strategy.checkPermission(
                contextWith("SELECT * FROM allowed_t WHERE id IN (SELECT id FROM secret_t)")));
    }

    /** CTE 里读的表。 */
    @Test
    void checksTablesInCte() {
        assertThrows(SecurityException.class, () -> strategy.checkPermission(
                contextWith("WITH c AS (SELECT * FROM secret_t) SELECT * FROM c")));
    }

    /** INSERT ... SELECT：来源表需要 SELECT 权限。 */
    @Test
    void checksInsertSourceTable() {
        assertThrows(SecurityException.class, () -> strategy.checkPermission(
                contextWith("INSERT INTO allowed_t SELECT * FROM secret_t")));
    }

    /** 以 DML 关键字开头但解析失败：默认拒绝。 */
    @Test
    void rejectsUnparseableDmlStatement() {
        SecurityException ex = assertThrows(SecurityException.class, () -> strategy.checkPermission(
                contextWith("SELECT * FROM (unclosed")));
        assertTrue(ex.getMessage().contains("无法被解析"), ex.getMessage());
    }

    /** 非 DML 的脚本命令解析失败时不应误伤。 */
    @Test
    void doesNotRejectUnparseableNonDmlStatement() {
        assertDoesNotThrow(() -> strategy.checkPermission(
                contextWith("SET hive.exec.dynamic.partition=true")));
    }

    /** DDL 关键字开头但平台无法解析：非运维/管理员默认拒绝。 */
    @Test
    void rejectsUnparseableDdlKeywordForNonOps() {
        SecurityException ex = assertThrows(SecurityException.class, () -> strategy.checkPermission(
                contextWith("GRANT SELECT ON TABLE allowed_t TO analyst")));
        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains("权限不足"), ex.getMessage());
    }

    /** 非 SQL 代码类型不进入 SQL 语句级校验（避免误判标识符）。 */
    @Test
    void skipsNonSqlCodeTypes() {
        CodeExecutionContext context = contextWith("select = {}  # python dict");
        context.setCodeType(CodeType.PYTHON);
        assertDoesNotThrow(() -> strategy.checkPermission(context));
    }

    /** 空白脚本直接放行。 */
    @Test
    void skipsBlankScript() {
        assertDoesNotThrow(() -> strategy.checkPermission(contextWith("   \n  ")));
    }

    /** 只命中放行表时不应抛异常，且校验确实逐表执行。 */
    @Test
    void allowsJoinOnAuthorizedTables() {
        assertDoesNotThrow(() -> strategy.checkPermission(
                contextWith("SELECT a.id FROM allowed_t a JOIN other_t b ON a.id = b.id")));
        assertEquals(true, permissionService.hasTablePermission("pg-test", "testdb", "other_t", "SELECT"));
    }

    // ==================== 列级权限的下发前改写（风险项 R4） ====================

    @Test
    void setsEffectiveCodeContentWhenRewriteApplies() {
        strategy.sqlPermissionRewriter = new SqlPermissionRewriter(permissionService);
        when(permissionService.getAllowedColumns(any(), any(), anyString())).thenReturn(Set.of("id"));

        CodeExecutionContext context = contextWith("SELECT id, status FROM allowed_t");
        strategy.checkPermission(context);

        assertTrue(context.isCodeRewritten(), "命中列级权限时应把改写结果写入 effectiveCodeContent");
        assertTrue(context.getExecutionCodeContent().contains("SELECT id FROM allowed_t"),
                context.getExecutionCodeContent());
        assertEquals("SELECT id, status FROM allowed_t", context.getCodeContent(),
                "用户原始代码不应被覆盖（日志/审计/回显仍展示原文）");
    }

    @Test
    void leavesEffectiveCodeContentUnsetWhenNoConstraint() {
        strategy.sqlPermissionRewriter = new SqlPermissionRewriter(permissionService);
        when(permissionService.getAllowedColumns(any(), any(), anyString())).thenReturn(null);

        CodeExecutionContext context = contextWith("SELECT id FROM allowed_t");
        strategy.checkPermission(context);

        assertFalse(context.isCodeRewritten());
        assertEquals("SELECT id FROM allowed_t", context.getExecutionCodeContent());
    }
}
