package com.bigdata.modules.development.service.execution;

import com.bigdata.common.security.CustomUserDetails;
import com.bigdata.common.utils.AesUtils;
import com.bigdata.modules.development.dto.CodeExecutionRequest;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.ConnectionType;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.devops.mapper.DatasourceConfigMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL 建表语法校验 - 真实数据集成测试
 * <p>
 * 前置环境（与 docker-compose 中 dataforge-allinone 一致）：
 * <ul>
 *   <li>平台库 MySQL 127.0.0.1:3306/dataforge（测试数据源配置写入此库，事务回滚）</li>
 *   <li>真实 PostgreSQL 127.0.0.1:5432/test_db（postgres/123456，仅做语法校验）</li>
 * </ul>
 * 校验策略：POSTGRESQL 走 buildValidateSql 的 BEGIN...ROLLBACK 事务包裹，
 * 语法错误在 parse/analyze 阶段抛出，DDL 在事务回滚后不落盘。
 */
@SpringBootTest
@Transactional
@Rollback
@DisplayName("Development - PostgreSQL建表语法校验 真实数据集成测试")
class PgSyntaxValidationRealDataTest {

    private static final String PG_URL = "jdbc:postgresql://127.0.0.1:5432/test_db";
    private static final String PG_DB = "test_db";
    private static final String PG_USER = "postgres";
    private static final String PG_PASSWORD = "123456";

    @Autowired
    private CodeExecutionService codeExecutionService;

    @Autowired
    private DatasourceConfigMapper datasourceConfigMapper;

    @Autowired
    private AesUtils aesUtils;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        List<SimpleGrantedAuthority> authorities = Arrays.asList(
                new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
        CustomUserDetails userDetails = new CustomUserDetails(
                1L, "admin", 1L, Arrays.asList("ROLE_SUPER_ADMIN"), true, authorities, true);
        Authentication authentication = new UsernamePasswordAuthenticationToken(userDetails, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 向平台库插入指向真实 PostgreSQL 的 datasource_config（密码 AES 加密存储） */
    private DatasourceConfig insertPgDatasource() {
        DatasourceConfig config = new DatasourceConfig();
        config.setDatasourceName("pg_syntax_check");
        config.setDatasourceType(DatasourceType.POSTGRESQL);
        config.setConnectionType(ConnectionType.JDBC);
        config.setUrl(PG_URL);
        config.setDatabaseName(Arrays.asList(PG_DB));
        config.setUsername(PG_USER);
        config.setPassword(aesUtils.encryptStored(PG_PASSWORD));
        config.setConnectionStatus(1);
        config.setDeleted(0);
        datasourceConfigMapper.insert(config);
        assertNotNull(config.getId(), "数据源配置应写入成功");
        return config;
    }

    private CodeExecutionRequest request(String sql) {
        CodeExecutionRequest req = new CodeExecutionRequest();
        req.setCodeContent(sql);
        req.setCodeType("SQL");
        req.setEngineType("postgresql");
        req.setDatasources(Arrays.asList("pg_syntax_check"));
        return req;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> errors(Map<String, Object> result) {
        Object e = result.get("errors");
        return e == null ? List.of() : (List<Map<String, Object>>) e;
    }

    /** 通过真实 PG 连接确认表是否落盘（验证事务回滚） */
    private boolean tableExists(String tableName) throws Exception {
        try (Connection conn = DriverManager.getConnection(PG_URL, PG_USER, PG_PASSWORD);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT 1 FROM information_schema.tables " +
                             "WHERE table_schema = 'public' AND table_name = '" + tableName + "'")) {
            return rs.next();
        }
    }

    @Nested
    @DisplayName("场景1: 合法建表校验")
    class ValidCreateTableTests {

        @Test
        @DisplayName("✅ 合法 CREATE TABLE - valid=true")
        void validCreateTable_passes() {
            insertPgDatasource();
            Map<String, Object> result = codeExecutionService.validateSqlSyntax(request(
                    "CREATE TABLE t_syntax_ok (id INT PRIMARY KEY, name VARCHAR(50), amount NUMERIC(10,2))"));
            assertEquals(true, result.get("valid"));
            assertEquals("POSTGRESQL", result.get("engine"));
            assertTrue(errors(result).isEmpty());
            System.out.println("  合法建表校验结果: valid=" + result.get("valid"));
        }

        @Test
        @DisplayName("✅ 合法 CREATE TABLE 校验后不落盘（事务回滚生效）")
        void validCreateTable_notPersisted() throws Exception {
            insertPgDatasource();
            String tableName = "t_syntax_not_persist";
            assertFalse(tableExists(tableName), "校验前表不应存在");
            Map<String, Object> result = codeExecutionService.validateSqlSyntax(request(
                    "CREATE TABLE " + tableName + " (id INT PRIMARY KEY, name VARCHAR(50))"));
            assertEquals(true, result.get("valid"));
            assertFalse(tableExists(tableName), "校验后表不应被真正创建（应已回滚）");
            System.out.println("  校验后 information_schema 确认表未落盘");
        }

        @Test
        @DisplayName("✅ 合法 SELECT - valid=true（BEGIN...ROLLBACK 包裹下查询仍可用）")
        void validSelect_passes() {
            insertPgDatasource();
            Map<String, Object> result = codeExecutionService.validateSqlSyntax(request("SELECT 1"));
            assertEquals(true, result.get("valid"));
            assertTrue(errors(result).isEmpty());
        }

        @Test
        @DisplayName("✅ CREATE TABLE AS SELECT - valid=true")
        void validCreateTableAsSelect_passes() {
            insertPgDatasource();
            Map<String, Object> result = codeExecutionService.validateSqlSyntax(request(
                    "CREATE TABLE t_syntax_ctas AS SELECT 1 AS id"));
            assertEquals(true, result.get("valid"));
            assertTrue(errors(result).isEmpty());
        }
    }

    @Nested
    @DisplayName("场景2: 非法建表校验")
    class InvalidCreateTableTests {

        @Test
        @DisplayName("❌ 语法错误 CREATE TABLE - valid=false 且记录错误")
        void invalidCreateTable_reportsError() {
            insertPgDatasource();
            Map<String, Object> result = codeExecutionService.validateSqlSyntax(request(
                    "CREATE TABLE t_bad (id INT, , name VARCHAR(50))"));
            assertEquals(false, result.get("valid"));
            assertEquals(1, errors(result).size());
            Map<String, Object> err = errors(result).get(0);
            assertEquals(1, err.get("index"));
            assertEquals("CREATE TABLE t_bad (id INT, , name VARCHAR(50))", err.get("sql"));
            assertNotNull(err.get("message"));
            assertFalse(String.valueOf(err.get("message")).isBlank());
            System.out.println("  语法错误信息: " + err.get("message"));
        }

        @Test
        @DisplayName("❌ 未知表 SELECT - valid=false（运行时错误被捕获）")
        void invalidSelect_unknownTable() {
            insertPgDatasource();
            Map<String, Object> result = codeExecutionService.validateSqlSyntax(request(
                    "SELECT * FROM not_exist_table_xyz"));
            assertEquals(false, result.get("valid"));
            assertEquals(1, errors(result).size());
            System.out.println("  未知表错误信息: " + errors(result).get(0).get("message"));
        }
    }

    @Nested
    @DisplayName("场景3: 多语句与边界")
    class MultiStatementTests {

        @Test
        @DisplayName("✅ 多语句：中间出错不中断，错误索引正确")
        void multiStatement_errorIndex_continueNext() {
            insertPgDatasource();
            Map<String, Object> result = codeExecutionService.validateSqlSyntax(request(
                    "CREATE TABLE t_multi_1 (id INT); SELECT * FROM not_exist_table_xyz; CREATE TABLE t_multi_2 (id INT)"));
            assertEquals(false, result.get("valid"));
            assertEquals(3, result.get("statementCount"));
            List<Map<String, Object>> errs = errors(result);
            assertEquals(1, errs.size(), "仅中间一条语句应报错（证明出错后继续校验下一条）");
            assertEquals(2, errs.get(0).get("index"));
        }

        @Test
        @DisplayName("✅ 空 SQL - valid=false 且提示语明确")
        void emptySql_reportsError() {
            insertPgDatasource();
            Map<String, Object> result = codeExecutionService.validateSqlSyntax(request("   "));
            assertEquals(false, result.get("valid"));
            assertEquals("SQL语句不能为空", result.get("message"));
        }
    }
}
