package com.bigdata.modules.development.service.execution;

import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证行级过滤在最终返回的 rows 列表上原地生效（调用方持有的同一引用被裁剪）。
 * <p>
 * 背景：旧实现 applyRowFilter 通过 stream().collect() 返回新列表，而后在
 * applyRowFilterAndMasking 里 local 变量重建，过滤结果被丢弃，导致外层 execute()
 * 返回的仍是未过滤的行。本例通过真实走 applyRowFilterAndMasking，断言传入的
 * rows 引用本身数量被裁剪，以证明修复有效。
 * </p>
 */
class AbstractBaseExecutionStrategyRowFilterTest {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractBaseExecutionStrategyRowFilterTest.class);

    /** 最小可测策略子类：仅实现抽象方法，注入 mock 权限服务 */
    private static class TestStrategy extends AbstractBaseExecutionStrategy {
        TestStrategy(DataAccessPermissionService dataAccessPermissionService) {
            this.dataAccessPermissionService = dataAccessPermissionService;
        }

        @Override
        protected Logger getLog() {
            return LOG;
        }

        @Override
        public CodeExecutionResult execute(CodeExecutionContext context) {
            return null; // 本测试不进入真实执行
        }

        @Override
        public DatasourceType getEngineType() {
            return DatasourceType.POSTGRESQL;
        }
    }

    private CodeExecutionContext buildContext(String datasourceName, String databaseName) {
        DatasourceConfig config = new DatasourceConfig();
        config.setDatasourceName(datasourceName);
        CodeExecutionContext context = mock(CodeExecutionContext.class);
        when(context.getPrimaryDatasourceConfig()).thenReturn(config);
        when(context.getPrimaryDatasourceDatabaseName()).thenReturn(databaseName);
        return context;
    }

    @Test
    void rowFilterTrimsCallerRowsListInPlace() {
        DataAccessPermissionService permissionService = mock(DataAccessPermissionService.class);
        // 行过滤：只保留 status = 'ok'；其它权限返回空，避免脱敏/列裁剪干扰
        when(permissionService.getMergedRowFilterSql(anyString(), anyString(), anyString()))
                .thenReturn("status = 'ok'");
        when(permissionService.getMergedTablePermission(anyString(), anyString(), anyString())).thenReturn(null);
        when(permissionService.getAllowedColumns(anyString(), anyString(), anyString())).thenReturn(null);

        TestStrategy strategy = new TestStrategy(permissionService);

        List<String> columns = new ArrayList<>(Arrays.asList("id", "status"));
        List<List<String>> rows = new ArrayList<>(Arrays.asList(
                Arrays.asList("1", "ok"),
                Arrays.asList("2", "bad"),
                Arrays.asList("3", "ok")
        ));

        String sql = "SELECT id, status FROM orders_t";
        CodeExecutionContext context = buildContext("pg-test", "testdb");

        strategy.applyRowFilterAndMasking(rows, columns, sql, context);

        // 必须作用于调用方同一份列表对象，而不是返回一个新列表
        assertFalse(rows.isEmpty(), "过滤后调用方持有的 rows 不应为空（不应是丢弃过滤结果的新列表）");
        assertTrue(rows.size() == 2, "应只剩 2 行 status='ok'，实际=" + rows.size());
        assertEquals(Arrays.asList("1", "ok"), rows.get(0));
        assertEquals(Arrays.asList("3", "ok"), rows.get(1));

        // 额外验证直接调用 applyRowFilter 时返回同一引用（原地修改）
        List<List<String>> single = new ArrayList<>(Arrays.asList(
                Arrays.asList("a", "x"),
                Arrays.asList("b", "y")));
        List<String> cols = new ArrayList<>(Arrays.asList("id", "status"));
        List<List<String>> returned = strategy.applyRowFilter(single, cols, "status = 'x'");
        assertSame(single, returned, "applyRowFilter 应返回与入参同一引用（原地修改）");
        assertEquals(1, single.size());
    }
}