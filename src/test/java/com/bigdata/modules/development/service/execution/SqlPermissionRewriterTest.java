package com.bigdata.modules.development.service.execution;

import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SqlPermissionRewriter} 的行为验证（风险项 R4）。
 * <p>
 * 覆盖两个方向：
 * <ul>
 *   <li><b>改写生效</b>：列白名单被裁剪、注释与分隔符被保留。</li>
 *   <li><b>fail-close</b>：命中限制但无法安全改写时抛出 SecurityException，而不是原样放行。</li>
 * </ul>
 */
class SqlPermissionRewriterTest {

    private DataAccessPermissionService permissionService;
    private SqlPermissionRewriter rewriter;

    @BeforeEach
    void setUp() {
        permissionService = mock(DataAccessPermissionService.class);
        // 默认：无任何权限约束（未命中限制时改写器必须返回 null，保持原样下发）
        when(permissionService.getAllowedColumns(any(), any(), any())).thenReturn(null);
        rewriter = new SqlPermissionRewriter(permissionService);
    }

    private String rewrite(String sql) {
        return rewriter.rewriteScript(sql, null, "testdb");
    }

    // ==================== 列级权限 ====================

    @Test
    void expandsStarProjectionToAllowedColumns() {
        when(permissionService.getAllowedColumns(any(), any(), eq("orders")))
                .thenReturn(Set.of("id", "amount"));

        String out = rewrite("SELECT * FROM orders");

        assertNotNull(out);
        assertEquals("SELECT amount, id FROM orders", out);
    }

    @Test
    void dropsColumnsOutsideWhitelist() {
        when(permissionService.getAllowedColumns(any(), any(), eq("orders")))
                .thenReturn(Set.of("id"));

        String out = rewrite("SELECT id, salary FROM orders");

        assertNotNull(out);
        assertEquals("SELECT id FROM orders", out);
    }

    @Test
    void rejectsWhenProjectionHasNoAllowedColumn() {
        when(permissionService.getAllowedColumns(any(), any(), eq("orders")))
                .thenReturn(Set.of("id"));

        SecurityException ex = assertThrows(SecurityException.class, () ->
                rewrite("SELECT salary FROM orders"));
        assertTrue(ex.getMessage().contains("无交集"), ex.getMessage());
    }

    @Test
    void rejectsExpressionReferencingUnauthorizedColumn() {
        when(permissionService.getAllowedColumns(any(), any(), eq("orders")))
                .thenReturn(Set.of("id"));

        SecurityException ex = assertThrows(SecurityException.class, () ->
                rewrite("SELECT md5(salary) FROM orders"));
        assertTrue(ex.getMessage().contains("salary"), ex.getMessage());
    }

    @Test
    void rejectsColumnPruningInsideSetOperationBranch() {
        when(permissionService.getAllowedColumns(any(), any(), eq("orders")))
                .thenReturn(Set.of("id"));

        SecurityException ex = assertThrows(SecurityException.class, () ->
                rewrite("SELECT * FROM orders UNION ALL SELECT * FROM other"));
        assertTrue(ex.getMessage().contains("无法安全裁剪列"), ex.getMessage());
    }

    @Test
    void rejectsColumnPruningOnMultiTableQuery() {
        when(permissionService.getAllowedColumns(any(), any(), eq("orders")))
                .thenReturn(Set.of("id"));

        SecurityException ex = assertThrows(SecurityException.class, () ->
                rewrite("SELECT a.*, b.v FROM orders a JOIN customers b ON a.cid = b.id"));
        assertTrue(ex.getMessage().contains("多表"), ex.getMessage());
    }

    @Test
    void rejectsEmptyColumnWhitelist() {
        when(permissionService.getAllowedColumns(any(), any(), eq("orders")))
                .thenReturn(Set.of());

        assertThrows(SecurityException.class, () -> rewrite("SELECT * FROM orders"));
    }

    // ==================== 脚本级行为 ====================

    @Test
    void returnsNullWhenNoPermissionConstraint() {
        assertNull(rewrite("SELECT * FROM orders"));
    }

    @Test
    void returnsNullForNonSqlCodeType() {
        CodeExecutionContext context = new CodeExecutionContext();
        context.setCodeType(CodeType.PYTHON);
        context.setCodeContent("print('hi')");
        assertNull(rewriter.rewrite(context));
    }

    @Test
    void rewritesOnlyTheRestrictedStatementAndKeepsTheRestVerbatim() {
        when(permissionService.getAllowedColumns(any(), any(), eq("orders")))
                .thenReturn(Set.of("id"));

        String script = "-- 前置注释\nSELECT * FROM orders;\n\n-- 第二条\nSELECT * FROM other;";
        String out = rewrite(script);

        assertNotNull(out);
        assertTrue(out.startsWith("-- 前置注释\n"), "前置注释应保留: " + out);
        assertTrue(out.contains("-- 第二条"), "后续注释应保留: " + out);
        assertTrue(out.contains("SELECT * FROM other"), "未命中的语句应原样保留: " + out);
        assertTrue(out.contains("SELECT id FROM orders"), "命中的语句应被改写: " + out);
        assertEquals(2, out.chars().filter(c -> c == ';').count(), "分隔符数量应不变: " + out);
    }

    @Test
    void skipsNonParseableScriptCommands() {
        // SET 不是 SQL 语句，无法解析；应跳过而不报错
        assertNull(rewrite("SET hive.exec.dynamic.partition=true"));
    }
}
