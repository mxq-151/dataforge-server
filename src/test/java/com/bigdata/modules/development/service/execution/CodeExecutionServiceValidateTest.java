package com.bigdata.modules.development.service.execution;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.development.dto.CodeExecutionRequest;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.devops.service.DatasourceConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link CodeExecutionService#validateSqlSyntax} 单元测试。
 * <p>
 * 通过 mock Connection/Statement 模拟各引擎的校验行为，不依赖真实数据库：
 * <ul>
 *   <li>MySQL：execute("PREPARE stmt FROM '...'")，命中 unsupportedSnippets 抛 1295(跳过)，命中 syntaxErrorSnippets 抛 1064(语法错误)</li>
 *   <li>Doris：execute("EXPLAIN ...")</li>
 *   <li>ClickHouse：execute("EXPLAIN SYNTAX ...")</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SQL语法校验 - CodeExecutionService.validateSqlSyntax 单元测试")
class CodeExecutionServiceValidateTest {

    @Mock
    private DatasourceConfigService datasourceConfigService;

    @Mock
    private Connection connection;

    @Mock
    private Statement statement;

    @InjectMocks
    private CodeExecutionService codeExecutionService;

    /** 命中即抛 MySQL 1295(PREPARE 不支持)，模拟 USE 等会话语句 */
    private final Set<String> unsupportedSnippets = new HashSet<>();
    /** 命中即抛 MySQL 1064(语法错误) */
    private final Set<String> syntaxErrorSnippets = new HashSet<>();
    /** 记录所有传入 statement.execute 的校验SQL，用于断言 buildValidateSql 的产物 */
    private final List<String> executedValidateSqls = new ArrayList<>();

    @BeforeEach
    void setUp() throws SQLException {
        unsupportedSnippets.clear();
        syntaxErrorSnippets.clear();
        executedValidateSqls.clear();

        when(connection.createStatement()).thenReturn(statement);
        // drainResults 立即终止
        when(statement.getMoreResults()).thenReturn(false);
        when(statement.getUpdateCount()).thenReturn(-1);
        when(statement.execute(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            executedValidateSqls.add(sql);
            for (String s : unsupportedSnippets) {
                if (sql.contains(s)) {
                    throw new SQLException("This command is not supported in the prepared statement protocol yet", "HY000", 1295);
                }
            }
            for (String s : syntaxErrorSnippets) {
                if (sql.contains(s)) {
                    throw new SQLException("You have an error in your SQL syntax near 'BAD'", "42000", 1064);
                }
            }
            return false;
        });
    }

    // ==================== 参数与数据源校验 ====================

    @Test
    @DisplayName("空SQL - 返回 valid=false")
    void emptySql_returnsInvalid() {
        Map<String, Object> result = codeExecutionService.validateSqlSyntax(request("   ", "mysql-ds"));
        assertEquals(false, result.get("valid"));
        assertTrue(((String) result.get("message")).contains("不能为空"));
    }

    @Test
    @DisplayName("未指定数据源 - 抛 BusinessException")
    void noDatasource_throwsBusinessException() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> codeExecutionService.validateSqlSyntax(request("SELECT 1", null)));
        assertTrue(ex.getMessage().contains("数据源"));
    }

    @Test
    @DisplayName("数据源不存在 - 抛 BusinessException")
    void datasourceNotFound_throwsBusinessException() {
        when(datasourceConfigService.getDatasourceByName("missing-ds")).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> codeExecutionService.validateSqlSyntax(request("SELECT 1", "missing-ds")));
        assertTrue(ex.getMessage().contains("数据源"));
    }

    @Test
    @DisplayName("数据源未配置存储引擎 - 抛 BusinessException")
    void noStorageEngine_throwsBusinessException() {
        DatasourceConfig c = newConfig("no-engine-ds", null, "db1");
        when(datasourceConfigService.getDatasourceByName("no-engine-ds")).thenReturn(c);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> codeExecutionService.validateSqlSyntax(request("SELECT 1", "no-engine-ds")));
        assertTrue(ex.getMessage().contains("存储引擎"));
    }

    // ==================== MySQL 校验 ====================

    @Test
    @DisplayName("MySQL 单条合法SELECT - valid=true 且使用 PREPARE")
    void mysql_validSelect_usesPrepare() throws SQLException {
        prepareDatasource(newConfig("mysql-ds", DatasourceType.MYSQL, "test_db"));
        Map<String, Object> result = codeExecutionService.validateSqlSyntax(request("SELECT 1", "mysql-ds"));

        assertEquals(true, result.get("valid"));
        assertEquals("MYSQL", result.get("engine"));
        assertEquals(0, result.get("skipped"));
        assertTrue(errors(result).isEmpty());
        assertTrue(executedValidateSqls.stream().anyMatch(s -> s.startsWith("PREPARE stmt FROM")));
    }

    @Test
    @DisplayName("MySQL 多语句含一处语法错误 - valid=false 且错误索引正确")
    void mysql_multiStatement_oneSyntaxError() throws SQLException {
        syntaxErrorSnippets.add("SELEC *");
        prepareDatasource(newConfig("mysql-ds", DatasourceType.MYSQL, "test_db"));

        Map<String, Object> result = codeExecutionService.validateSqlSyntax(
                request("SELECT 1; SELEC * FROM t; SELECT 2", "mysql-ds"));

        assertEquals(false, result.get("valid"));
        List<Map<String, Object>> errors = errors(result);
        assertEquals(1, errors.size());
        assertEquals(2, errors.get(0).get("index"));
        assertEquals("SELEC * FROM t", errors.get(0).get("sql"));
    }

    @Test
    @DisplayName("MySQL PREPARE不支持的语句(USE) - 跳过且 valid=true")
    void mysql_unsupportedStatement_skipped() throws SQLException {
        unsupportedSnippets.add("USE ");
        prepareDatasource(newConfig("mysql-ds", DatasourceType.MYSQL, "test_db"));

        Map<String, Object> result = codeExecutionService.validateSqlSyntax(request("USE db", "mysql-ds"));

        assertEquals(true, result.get("valid"));
        assertEquals(1, result.get("skipped"));
        assertTrue(errors(result).isEmpty());
        assertTrue(((String) result.get("message")).contains("跳过"));
    }

    @Test
    @DisplayName("MySQL 混合: 合法 + 不支持 + 语法错误")
    void mysql_mixed_validUnsupportedSyntaxError() throws SQLException {
        unsupportedSnippets.add("USE ");
        syntaxErrorSnippets.add("SELEC *");
        prepareDatasource(newConfig("mysql-ds", DatasourceType.MYSQL, "test_db"));

        Map<String, Object> result = codeExecutionService.validateSqlSyntax(
                request("SELECT 1; USE db; SELEC * FROM t", "mysql-ds"));

        assertEquals(false, result.get("valid"));
        assertEquals(1, result.get("skipped"));
        List<Map<String, Object>> errors = errors(result);
        assertEquals(1, errors.size());
        assertEquals(3, errors.get(0).get("index"));
    }

    @Test
    @DisplayName("MySQL 单引号转义 - 语句内单引号翻倍")
    void mysql_singleQuoteEscaped() throws SQLException {
        prepareDatasource(newConfig("mysql-ds", DatasourceType.MYSQL, "test_db"));
        codeExecutionService.validateSqlSyntax(request("SELECT 'O''Brien'", "mysql-ds"));

        String validateSql = executedValidateSqls.stream()
                .filter(s -> s.startsWith("PREPARE stmt FROM"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未捕获到 PREPARE 校验SQL"));
        // 原语句 SELECT 'O''Brien' 中 4 个单引号均翻倍，再由外层 '...' 包裹
        assertEquals("PREPARE stmt FROM 'SELECT ''O''''Brien'''", validateSql);
    }

    @Test
    @DisplayName("MySQL 多条全合法 - valid=true 且 statementCount 正确")
    void mysql_multiStatement_allValid() throws SQLException {
        prepareDatasource(newConfig("mysql-ds", DatasourceType.MYSQL, "test_db"));
        Map<String, Object> result = codeExecutionService.validateSqlSyntax(
                request("SELECT 1; SELECT 2; SELECT 3", "mysql-ds"));

        assertEquals(true, result.get("valid"));
        assertEquals(3, result.get("statementCount"));
        assertEquals(0, result.get("skipped"));
        assertTrue(errors(result).isEmpty());
    }

    @Test
    @DisplayName("MySQL 末尾分号 - 不产生空语句")
    void mysql_trailingSemicolon_noEmptyStatement() throws SQLException {
        prepareDatasource(newConfig("mysql-ds", DatasourceType.MYSQL, "test_db"));
        Map<String, Object> result = codeExecutionService.validateSqlSyntax(
                request("SELECT 1;", "mysql-ds"));

        assertEquals(true, result.get("valid"));
        assertEquals(1, result.get("statementCount"));
    }

    // ==================== 各引擎 buildValidateSql 产物 ====================

    @ParameterizedTest(name = "引擎 {0} - 校验SQL构造正确")
    @EnumSource(DatasourceType.class)
    @DisplayName("各引擎 - buildValidateSql 产物正确")
    void allEngines_buildCorrectValidateSql(DatasourceType engine) throws SQLException {
        String dsName = "ds-" + engine.name();
        prepareDatasource(newConfig(dsName, engine, "test_db"));

        Map<String, Object> result = codeExecutionService.validateSqlSyntax(request("SELECT 1", dsName));

        assertEquals(true, result.get("valid"), engine + " 应校验通过");
        assertEquals(engine.name(), result.get("engine"));

        String validateSql = executedValidateSqls.stream()
                .filter(s -> !s.startsWith("DEALLOCATE"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未捕获到 " + engine + " 的校验SQL"));

        switch (engine) {
            case MYSQL:
                assertTrue(validateSql.startsWith("PREPARE stmt FROM"), engine + " 应使用 PREPARE");
                break;
            case CLICKHOUSE:
                assertTrue(validateSql.startsWith("EXPLAIN SYNTAX"), engine + " 应使用 EXPLAIN SYNTAX");
                break;
            default:
                assertTrue(validateSql.startsWith("EXPLAIN ") && !validateSql.startsWith("EXPLAIN SYNTAX"),
                        engine + " 应使用 EXPLAIN(非 SYNTAX)");
                break;
        }
    }

    // ==================== 拆分与注释处理 ====================

    @Test
    @DisplayName("纯注释语句 - 不执行校验且 valid=true")
    void commentOnly_notExecuted() throws SQLException {
        prepareDatasource(newConfig("mysql-ds", DatasourceType.MYSQL, "test_db"));

        Map<String, Object> result = codeExecutionService.validateSqlSyntax(
                request("-- this is a comment", "mysql-ds"));

        assertEquals(true, result.get("valid"));
        assertTrue(executedValidateSqls.isEmpty());
    }

    @Test
    @DisplayName("字符串字面量中的分号不拆分 - 保持单条语句")
    void semicolonInStringLiteral_notSplit() throws SQLException {
        prepareDatasource(newConfig("mysql-ds", DatasourceType.MYSQL, "test_db"));

        Map<String, Object> result = codeExecutionService.validateSqlSyntax(
                request("SELECT 'a;b;c' AS x", "mysql-ds"));

        assertEquals(true, result.get("valid"));
        assertEquals(1, result.get("statementCount"));
        long prepareCalls = executedValidateSqls.stream()
                .filter(s -> s.startsWith("PREPARE stmt FROM")).count();
        assertEquals(1, prepareCalls);
    }

    @Test
    @DisplayName("块注释跨行 - 注释内分号不拆分")
    void blockCommentSpanningLines_notSplit() throws SQLException {
        prepareDatasource(newConfig("mysql-ds", DatasourceType.MYSQL, "test_db"));

        Map<String, Object> result = codeExecutionService.validateSqlSyntax(
                request("/* this; is\na comment */ SELECT 1", "mysql-ds"));

        assertEquals(true, result.get("valid"));
        assertEquals(1, result.get("statementCount"));
    }

    // ==================== 连接异常 ====================

    @Test
    @DisplayName("连接数据源失败 - 返回 valid=false")
    void connectionFailure_returnsInvalid() throws SQLException {
        DatasourceConfig c = newConfig("mysql-ds", DatasourceType.MYSQL, "test_db");
        when(datasourceConfigService.getDatasourceByName("mysql-ds")).thenReturn(c);
        when(datasourceConfigService.getConnection(eq(c.getId()), any()))
                .thenThrow(new SQLException("connection refused"));

        Map<String, Object> result = codeExecutionService.validateSqlSyntax(request("SELECT 1", "mysql-ds"));

        assertEquals(false, result.get("valid"));
        assertTrue(((String) result.get("message")).contains("连接数据源失败"));
    }

    // ==================== 辅助方法 ====================

    private DatasourceConfig newConfig(String name, DatasourceType engine, String db) {
        DatasourceConfig c = new DatasourceConfig();
        c.setId(1L);
        c.setDatasourceName(name);
        c.setDatasourceType(engine);
        if (db != null) {
            c.setDatabaseName(Collections.singletonList(db));
        }
        return c;
    }

    private void prepareDatasource(DatasourceConfig config) throws SQLException {
        String dbName = (config.getDatabaseName() != null && !config.getDatabaseName().isEmpty())
                ? config.getDatabaseName().get(0) : null;
        when(datasourceConfigService.getDatasourceByName(config.getDatasourceName())).thenReturn(config);
        when(datasourceConfigService.getConnection(eq(config.getId()), eq(dbName))).thenReturn(connection);
    }

    private CodeExecutionRequest request(String sql, String datasourceName) {
        CodeExecutionRequest r = new CodeExecutionRequest();
        r.setCodeType("sql");
        r.setEngineType("mysql");
        r.setCodeContent(sql);
        r.setDatasources(datasourceName == null ? null : Collections.singletonList(datasourceName));
        return r;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> errors(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("errors");
    }
}
