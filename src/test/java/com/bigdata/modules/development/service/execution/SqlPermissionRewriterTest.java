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
 *   <li><b>改写生效</b>：行级过滤被注入、列白名单被裁剪、多表下条件列被限定、注释与分隔符被保留。</li>
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
        when(permissionService.getMergedRowFilterSql(any(), any(), any())).thenReturn(null);
        when(permissionService.getAllowedColumns(any(), any(), any())).thenReturn(null);
        rewriter = new SqlPermissionRewriter(permissionService);
    }

    private String rewrite(String sql) {
        return rewriter.rewriteScript(sql, null, "testdb");
    }

    // ==================== 行级过滤 ====================

    @Test
    void injectsRowFilterIntoWhereWhenAbsent() {
        when(permissionService.getMergedRowFilterSql(any(), any(), eq("orders")))
                .thenReturn("region = 'cn'");

        String out = rewrite("SELECT * FROM orders");

        assertNotNull(out);
        assertTrue(out.contains("WHERE"), "应注入 WHERE 子句: " + out);
        assertTrue(out.contains("region = 'cn'"), "应包含行过滤条件: " + out);
        assertTrue(out.startsWith("SELECT * FROM orders"), "原语句结构应保留: " + out);
    }

    @Test
    void andsRowFilterWithExistingWhere() {
        when(permissionService.getMergedRowFilterSql(any(), any(), eq("orders")))
                .thenReturn("region = 'cn'");

        String out = rewrite("SELECT id FROM orders WHERE status = 'ok'");

        assertNotNull(out);
        assertTrue(out.contains("status = 'ok'"), "原 WHERE 条件应保留: " + out);
        assertTrue(out.contains("region = 'cn'"), "行过滤条件应被追加: " + out);
        assertTrue(out.contains(" AND "), "应用 AND 组合: " + out);
    }

    @Test
    void acceptsWherePrefixedFilterSql() {
        when(permissionService.getMergedRowFilterSql(any(), any(), eq("orders")))
                .thenReturn("WHERE region = 'cn'");

        String out = rewrite("SELECT id FROM orders");

        assertNotNull(out);
        assertTrue(out.contains("WHERE (region = 'cn')"), "WHERE 前缀应被正确剥离再重建: " + out);
        assertEquals(out.indexOf("WHERE"), out.lastIndexOf("WHERE"), "不应出现重复的 WHERE: " + out);
    }

    @Test
    void qualifiesFilterColumnsOnMultiTableQuery() {
        when(permissionService.getMergedRowFilterSql(any(), any(), eq("orders")))
                .thenReturn("region = 'cn'");

        String out = rewrite("SELECT a.id, b.v FROM orders a JOIN customers b ON a.cid = b.id");

        assertNotNull(out);
        assertTrue(out.contains("a.region = 'cn'"), "多表时条件列应被别名限定: " + out);
    }

    @Test
    void rejectsRowFilterInjectionOnOuterJoin() {
        when(permissionService.getMergedRowFilterSql(any(), any(), eq("orders")))
                .thenReturn("region = 'cn'");

        SecurityException ex = assertThrows(SecurityException.class, () ->
                rewrite("SELECT a.id, b.v FROM orders a LEFT JOIN customers b ON a.cid = b.id"));
        assertTrue(ex.getMessage().contains("外连接") || ex.getMessage().contains("连接语义"),
                "拒绝原因应说明外连接语义问题: " + ex.getMessage());
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
        when(permissionService.getMergedRowFilterSql(any(), any(), eq("orders")))
                .thenReturn("region = 'cn'");

        String script = "-- 前置注释\nSELECT * FROM orders;\n\n-- 第二条\nSELECT * FROM other;";
        String out = rewrite(script);

        assertNotNull(out);
        assertTrue(out.startsWith("-- 前置注释\n"), "前置注释应保留: " + out);
        assertTrue(out.contains("-- 第二条"), "后续注释应保留: " + out);
        assertTrue(out.contains("SELECT * FROM other"), "未命中的语句应原样保留: " + out);
        assertTrue(out.contains("region = 'cn'"), "命中的语句应被改写: " + out);
        assertEquals(2, out.chars().filter(c -> c == ';').count(), "分隔符数量应不变: " + out);
    }

    @Test
    void skipsNonParseableScriptCommands() {
        when(permissionService.getMergedRowFilterSql(any(), any(), any())).thenReturn("region = 'cn'");

        // SET 不是 SQL 语句，无法解析；应跳过而不报错
        assertNull(rewrite("SET hive.exec.dynamic.partition=true"));
    }

    @Test
    void rewritesSubQueryInFromClause() {
        when(permissionService.getMergedRowFilterSql(any(), any(), eq("orders")))
                .thenReturn("region = 'cn'");

        String out = rewrite("SELECT * FROM (SELECT * FROM orders) x");

        assertNotNull(out);
        assertTrue(out.contains("region = 'cn'"), "派生表内部的表也应被注入过滤: " + out);
    }

    /**
     * JSqlParser 对残缺条件很宽松（{@code "region ="} 会被静默解析成裸列 {@code region}，
     * 注入后等于把「限定 region」放宽成「只要 region 非空」），必须按默认拒绝处理。
     */
    @Test
    void rejectsRowFilterConditionSilentlySimplifiedByParser() {
        when(permissionService.getMergedRowFilterSql(any(), any(), eq("orders")))
                .thenReturn("region =");

        SecurityException ex = assertThrows(SecurityException.class, () ->
                rewrite("SELECT * FROM orders"));
        assertTrue(ex.getMessage().contains("行级过滤条件无法解析"), ex.getMessage());
    }

    @Test
    void rejectsRowFilterConditionThatCannotBeParsed() {
        when(permissionService.getMergedRowFilterSql(any(), any(), eq("orders")))
                .thenReturn("region in (");

        SecurityException ex = assertThrows(SecurityException.class, () ->
                rewrite("SELECT * FROM orders"));
        assertTrue(ex.getMessage().contains("行级过滤条件无法解析"), ex.getMessage());
    }

    /** 常见合法写法必须原样通过（往返一致性校验不能误伤）。 */
    @Test
    void acceptsCommonRowFilterForms() {
        for (String filter : new String[]{
                "tenant_id in ('1','2')",
                "region <> 'x'",
                "a IS NOT NULL",
                "dt LIKE '2024%'",
                "amount BETWEEN 1 AND 100",
                "t.region = 'cn'",
                "date_format(dt,'yyyyMMdd') = '20240101'"}) {
            org.mockito.Mockito.reset(permissionService);
            when(permissionService.getMergedRowFilterSql(any(), any(), eq("orders"))).thenReturn(filter);
            when(permissionService.getAllowedColumns(any(), any(), any())).thenReturn(null);

            String out = rewrite("SELECT id FROM orders");
            assertNotNull(out, "合法过滤条件不应被拒绝: " + filter);
            assertTrue(out.contains("WHERE"), filter + " -> " + out);
        }
    }
}
