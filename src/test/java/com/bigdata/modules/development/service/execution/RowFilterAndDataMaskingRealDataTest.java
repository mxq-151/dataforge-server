package com.bigdata.modules.development.service.execution;

import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.modules.devops.enums.DatasourceType;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RowFilterAndDataMaskingRealDataTest {

    @Autowired
    private DataSource dataSource;

    private TestableExecutionStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new TestableExecutionStrategy();
        System.out.println("========================================");
        System.out.println("  RowFilterAndDataMaskingRealDataTest 启动");
        System.out.println("========================================");
    }

    public static class TestableExecutionStrategy extends AbstractJdbcExecutionStrategy {

        @Override
        protected Logger getLog() {
            return LoggerFactory.getLogger(TestableExecutionStrategy.class);
        }

        @Override
        protected String getEngineName() {
            return "TEST";
        }

        @Override
        protected String getEngineCode() {
            return "TEST";
        }

        @Override
        protected void applySessionSettings(Statement stmt, CodeExecutionContext context) throws Exception {
        }

        @Override
        protected DatasourceType resolveStorageEngineType(CodeExecutionContext context) {
            return DatasourceType.MYSQL;
        }

        @Override
        public DatasourceType getEngineType() {
            return DatasourceType.DORIS;
        }

        public List<List<String>> publicApplyRowFilter(List<List<String>> rows, List<String> columns, String rowFilterSql) {
            return applyRowFilter(rows, columns, rowFilterSql);
        }

        public boolean publicEvaluateRowFilterExpression(List<String> row, List<String> columns, Map<String, Integer> colIndexMap, String filterSql) {
            return evaluateRowFilterExpression(row, columns, colIndexMap, filterSql);
        }

        public void publicApplyDataMasking(List<List<String>> rows, List<String> columns, String maskingRule) {
            applyDataMasking(rows, columns, maskingRule);
        }

        public String publicApplyMask(String original, String maskType, List<Object> maskParams) {
            return applyMask(original, maskType, maskParams);
        }

        public AbstractJdbcExecutionStrategy.SelectPermissionInfo publicExtractSelectPermissionInfo(String sql, String datasourceName, String defaultDatabase) {
            return extractSelectPermissionInfo(sql, datasourceName, defaultDatabase);
        }
    }

    @Nested
    @DisplayName("场景1: 行级过滤 - 等值条件 (=)")
    class RowFilterEqualityTest {

        @Test
        void testEqualityFilter_StatusEquals1() {
            List<String> columns = Arrays.asList("id", "name", "status", "dept");
            List<List<String>> rows = new ArrayList<>();
            rows.add(Arrays.asList("1", "Alice", "1", "IT"));
            rows.add(Arrays.asList("2", "Bob", "0", "HR"));
            rows.add(Arrays.asList("3", "Charlie", "1", "Finance"));

            String filterSql = "WHERE status = 1";

            System.out.println("\n--- 场景1: 等值过滤 status = 1 ---");
            System.out.println("输入列: " + columns);
            System.out.println("输入行数: " + rows.size());
            for (List<String> row : rows) {
                System.out.println("  行: " + row);
            }
            System.out.println("过滤条件: " + filterSql);

            List<List<String>> result = strategy.publicApplyRowFilter(rows, columns, filterSql);

            System.out.println("输出行数: " + result.size());
            for (List<String> row : result) {
                System.out.println("  行: " + row);
            }

            assertEquals(2, result.size(), "等值过滤 status=1 应保留2行(Alice和Charlie)");
            assertEquals("Alice", result.get(0).get(1), "第一行应为Alice");
            assertEquals("Charlie", result.get(1).get(1), "第二行应为Charlie");
        }
    }

    @Nested
    @DisplayName("场景2: 行级过滤 - 不等条件 (!=, <>)")
    class RowFilterInequalityTest {

        @Test
        void testInequalityFilter_StatusNotEqual0() {
            List<String> columns = Arrays.asList("id", "name", "status", "dept");
            List<List<String>> rows = new ArrayList<>();
            rows.add(Arrays.asList("1", "Alice", "1", "IT"));
            rows.add(Arrays.asList("2", "Bob", "0", "HR"));
            rows.add(Arrays.asList("3", "Charlie", "1", "Finance"));

            String filterSql = "WHERE status != 0";

            System.out.println("\n--- 场景2: 不等过滤 status != 0 ---");
            System.out.println("输入列: " + columns);
            System.out.println("输入行数: " + rows.size());
            for (List<String> row : rows) {
                System.out.println("  行: " + row);
            }
            System.out.println("过滤条件: " + filterSql);

            List<List<String>> result = strategy.publicApplyRowFilter(rows, columns, filterSql);

            System.out.println("输出行数: " + result.size());
            for (List<String> row : result) {
                System.out.println("  行: " + row);
            }

            assertEquals(2, result.size(), "不等过滤 status!=0 应保留2行(status=1的行)");
            assertEquals("Alice", result.get(0).get(1), "第一行应为Alice");
            assertEquals("Charlie", result.get(1).get(1), "第二行应为Charlie");
        }

        @Test
        void testInequalityFilter_DiamondOperator() {
            List<String> columns = Arrays.asList("id", "name", "status");
            List<List<String>> rows = new ArrayList<>();
            rows.add(Arrays.asList("1", "A", "1"));
            rows.add(Arrays.asList("2", "B", "0"));

            String filterSql = "WHERE status <> 0";

            System.out.println("\n--- 场景2b: <> 操作符测试 ---");
            System.out.println("过滤条件: " + filterSql);

            List<List<String>> result = strategy.publicApplyRowFilter(rows, columns, filterSql);

            System.out.println("输出行数: " + result.size());
            for (List<String> row : result) {
                System.out.println("  行: " + row);
            }

            assertEquals(1, result.size(), "<>操作符应与!=等效");
            assertEquals("A", result.get(0).get(1));
        }
    }

    @Nested
    @DisplayName("场景3: 行级过滤 - 数值比较 (>, <, >=, <=)")
    class RowFilterNumericComparisonTest {

        @Test
        void testGreaterOrEqual_AgeGte25() {
            List<String> columns = Arrays.asList("id", "name", "age");
            List<List<String>> rows = new ArrayList<>();
            rows.add(Arrays.asList("1", "A", "18"));
            rows.add(Arrays.asList("2", "B", "25"));
            rows.add(Arrays.asList("3", "C", "30"));
            rows.add(Arrays.asList("4", "D", "45"));

            String filterSql = "WHERE age >= 25";

            System.out.println("\n--- 场景3: 数值比较 age >= 25 ---");
            System.out.println("输入列: " + columns);
            System.out.println("输入行数: " + rows.size());
            for (List<String> row : rows) {
                System.out.println("  行: " + row);
            }
            System.out.println("过滤条件: " + filterSql);

            List<List<String>> result = strategy.publicApplyRowFilter(rows, columns, filterSql);

            System.out.println("输出行数: " + result.size());
            for (List<String> row : result) {
                System.out.println("  行: " + row);
            }

            assertEquals(3, result.size(), "age>=25应保留3行(B,C,D)");
            assertEquals("B", result.get(0).get(1), "第一行应为B(age=25)");
            assertEquals("C", result.get(1).get(1), "第二行应为C(age=30)");
            assertEquals("D", result.get(2).get(1), "第三行应为D(age=45)");
        }

        @Test
        void testGreaterThan_AgeGt20() {
            List<String> columns = Arrays.asList("id", "name", "age");
            List<List<String>> rows = new ArrayList<>();
            rows.add(Arrays.asList("1", "A", "18"));
            rows.add(Arrays.asList("2", "B", "25"));
            rows.add(Arrays.asList("3", "C", "30"));

            String filterSql = "WHERE age > 20";

            System.out.println("\n--- 场景3b: > 操作符 age > 20 ---");
            System.out.println("过滤条件: " + filterSql);

            List<List<String>> result = strategy.publicApplyRowFilter(rows, columns, filterSql);

            System.out.println("输出行数: " + result.size());
            for (List<String> row : result) {
                System.out.println("  行: " + row);
            }

            assertEquals(2, result.size(), "age>20应保留2行(B和C)");
        }

        @Test
        void testLessThan_AgeLt30() {
            List<String> columns = Arrays.asList("id", "name", "age");
            List<List<String>> rows = new ArrayList<>();
            rows.add(Arrays.asList("1", "A", "18"));
            rows.add(Arrays.asList("2", "B", "25"));
            rows.add(Arrays.asList("3", "C", "30"));

            String filterSql = "WHERE age < 30";

            System.out.println("\n--- 场景3c: < 操作符 age < 30 ---");
            System.out.println("过滤条件: " + filterSql);

            List<List<String>> result = strategy.publicApplyRowFilter(rows, columns, filterSql);

            System.out.println("输出行数: " + result.size());
            for (List<String> row : result) {
                System.out.println("  行: " + row);
            }

            assertEquals(2, result.size(), "age<30应保留2行(A和B)");
        }

        @Test
        void testLessOrEqual_AgeLte25() {
            List<String> columns = Arrays.asList("id", "name", "age");
            List<List<String>> rows = new ArrayList<>();
            rows.add(Arrays.asList("1", "A", "18"));
            rows.add(Arrays.asList("2", "B", "25"));
            rows.add(Arrays.asList("3", "C", "30"));

            String filterSql = "WHERE age <= 25";

            System.out.println("\n--- 场景3d: <= 操作符 age <= 25 ---");
            System.out.println("过滤条件: " + filterSql);

            List<List<String>> result = strategy.publicApplyRowFilter(rows, columns, filterSql);

            System.out.println("输出行数: " + result.size());
            for (List<String> row : result) {
                System.out.println("  行: " + row);
            }

            assertEquals(2, result.size(), "age<=25应保留2行(A和B)");
        }
    }

    @Nested
    @DisplayName("场景4: 行级过滤 - LIKE 条件")
    class RowFilterLikeTest {

        @Test
        void testLikeCondition_AppPrefix() {
            List<String> columns = Arrays.asList("id", "name");
            List<List<String>> rows = new ArrayList<>();
            rows.add(Arrays.asList("1", "apple"));
            rows.add(Arrays.asList("2", "application"));
            rows.add(Arrays.asList("3", "banana"));
            rows.add(Arrays.asList("4", "apex"));

            String filterSql = "WHERE name LIKE 'app%'";

            System.out.println("\n--- 场景4: LIKE条件 name LIKE 'app%' ---");
            System.out.println("输入列: " + columns);
            System.out.println("输入行数: " + rows.size());
            for (List<String> row : rows) {
                System.out.println("  行: " + row);
            }
            System.out.println("过滤条件: " + filterSql);

            List<List<String>> result = strategy.publicApplyRowFilter(rows, columns, filterSql);

            System.out.println("输出行数: " + result.size());
            for (List<String> row : result) {
                System.out.println("  行: " + row);
            }

            assertEquals(2, result.size(), "LIKE 'app%'应匹配apple和application");
            assertEquals("apple", result.get(0).get(1), "第一行应为apple");
            assertEquals("application", result.get(1).get(1), "第二行应为application");
        }
    }

    @Nested
    @DisplayName("场景5: 行级过滤 - IN 条件")
    class RowFilterInTest {

        @Test
        void testInCondition_DeptInITFinance() {
            List<String> columns = Arrays.asList("id", "name", "dept");
            List<List<String>> rows = new ArrayList<>();
            rows.add(Arrays.asList("1", "A", "IT"));
            rows.add(Arrays.asList("2", "B", "HR"));
            rows.add(Arrays.asList("3", "C", "Finance"));
            rows.add(Arrays.asList("4", "D", "IT"));

            String filterSql = "WHERE dept IN ('IT', 'Finance')";

            System.out.println("\n--- 场景5: IN条件 dept IN ('IT', 'Finance') ---");
            System.out.println("输入列: " + columns);
            System.out.println("输入行数: " + rows.size());
            for (List<String> row : rows) {
                System.out.println("  行: " + row);
            }
            System.out.println("过滤条件: " + filterSql);

            List<List<String>> result = strategy.publicApplyRowFilter(rows, columns, filterSql);

            System.out.println("输出行数: " + result.size());
            for (List<String> row : result) {
                System.out.println("  行: " + row);
            }

            assertEquals(3, result.size(), "IN ('IT','Finance')应保留3行(A,C,D)");
            assertEquals("A", result.get(0).get(1), "第一行应为A(IT)");
            assertEquals("C", result.get(1).get(1), "第二行应为C(Finance)");
            assertEquals("D", result.get(2).get(1), "第三行应为D(IT)");
        }
    }

    @Nested
    @DisplayName("场景6: 行级过滤 - 多条件组合 (AND逻辑)")
    class RowFilterMultiConditionTest {

        @Test
        void testAndCombination_Status1AndAgeLt30() {
            List<String> columns = Arrays.asList("id", "name", "status", "age");
            List<List<String>> rows = new ArrayList<>();
            rows.add(Arrays.asList("1", "A", "1", "20"));
            rows.add(Arrays.asList("2", "B", "1", "35"));
            rows.add(Arrays.asList("3", "C", "0", "25"));
            rows.add(Arrays.asList("4", "D", "1", "40"));

            String filterSql = "WHERE status = 1 AND age < 30";

            System.out.println("\n--- 场景6: AND组合 status = 1 AND age < 30 ---");
            System.out.println("输入列: " + columns);
            System.out.println("输入行数: " + rows.size());
            for (List<String> row : rows) {
                System.out.println("  行: " + row);
            }
            System.out.println("过滤条件: " + filterSql);

            List<List<String>> result = strategy.publicApplyRowFilter(rows, columns, filterSql);

            System.out.println("输出行数: " + result.size());
            for (List<String> row : result) {
                System.out.println("  行: " + row);
            }

            assertEquals(1, result.size(), "AND组合: 只有A同时满足status=1且age<30");
            assertEquals("A", result.get(0).get(1), "唯一满足条件的行应为A");
        }
    }

    @Nested
    @DisplayName("场景7: 行级过滤 - 空数据和边界情况")
    class RowFilterEdgeCaseTest {

        @Test
        void testEmptyRows_ShouldReturnEmptyList() {
            List<String> columns = Arrays.asList("id", "name");
            List<List<String>> rows = new ArrayList<>();

            String filterSql = "WHERE id = 1";

            System.out.println("\n--- 场景7a: 空rows列表 ---");
            System.out.println("输入rows: []");
            System.out.println("过滤条件: " + filterSql);

            List<List<String>> result = strategy.publicApplyRowFilter(rows, columns, filterSql);

            System.out.println("输出结果: " + result);
            assertNotNull(result, "空rows列表不应返回null");
            assertTrue(result.isEmpty(), "空rows列表应返回空列表");
        }

        @Test
        void testNullRows_ShouldReturnNull() {
            List<String> columns = Arrays.asList("id", "name");

            String filterSql = "WHERE id = 1";

            System.out.println("\n--- 场景7b: null rows ---");
            System.out.println("输入rows: null");
            System.out.println("过滤条件: " + filterSql);

            List<List<String>> result = strategy.publicApplyRowFilter(null, columns, filterSql);

            System.out.println("输出结果: " + result);
            assertNull(result, "null rows应返回原值(null)");
        }

        @Test
        void testBlankFilterSql_ShouldReturnOriginalData() {
            List<String> columns = Arrays.asList("id", "name");
            List<List<String>> rows = new ArrayList<>();
            rows.add(Arrays.asList("1", "Alice"));
            rows.add(Arrays.asList("2", "Bob"));

            System.out.println("\n--- 场景7c: 空/blank filterSql ---");
            System.out.println("输入行数: " + rows.size());

            List<List<String>> resultEmpty = strategy.publicApplyRowFilter(rows, columns, "");
            System.out.println("filterSql='' 结果行数: " + (resultEmpty == null ? "null" : resultEmpty.size()));
            assertSame(rows, resultEmpty, "空字符串filterSql应返回原始数据引用不变");

            List<List<String>> resultBlank = strategy.publicApplyRowFilter(rows, columns, "   ");
            System.out.println("filterSql='   ' 结果行数: " + (resultBlank == null ? "null" : resultBlank.size()));
            assertSame(rows, resultBlank, "纯空白filterSql应返回原始数据引用不变");

            List<List<String>> resultNullFilter = strategy.publicApplyRowFilter(rows, columns, null);
            System.out.println("filterSql=null 结果行数: " + (resultNullFilter == null ? "null" : resultNullFilter.size()));
            assertSame(rows, resultNullFilter, "null filterSql应返回原始数据引用不变");
        }

        @Test
        void testNonExistentColumn_ShouldIgnoreCondition() {
            List<String> columns = Arrays.asList("id", "name");
            List<List<String>> rows = new ArrayList<>();
            rows.add(Arrays.asList("1", "Alice"));
            rows.add(Arrays.asList("2", "Bob"));

            String filterSql = "WHERE nonexistent_col = 'test'";

            System.out.println("\n--- 场景7d: 列名不存在于columns中 ---");
            System.out.println("输入列: " + columns);
            System.out.println("输入行数: " + rows.size());
            System.out.println("过滤条件(不存在的列): " + filterSql);

            List<List<String>> result = strategy.publicApplyRowFilter(rows, columns, filterSql);

            System.out.println("输出行数: " + result.size());
            for (List<String> row : result) {
                System.out.println("  行: " + row);
            }

            assertEquals(2, result.size(), "不存在的列的条件应被忽略，保留所有行");
        }
    }

    @Nested
    @DisplayName("场景8: 数据脱敏 - full 类型")
    class DataMaskingFullTest {

        @Test
        void testFullMask_PhoneNumber() {
            String original = "13812345678";
            String maskType = "full";

            System.out.println("\n--- 场景8: full脱敏 ---");
            System.out.println("原始值: '" + original + "'");
            System.out.println("脱敏类型: " + maskType);

            String result = strategy.publicApplyMask(original, maskType, null);

            System.out.println("脱敏结果: '" + result + "'");

            assertEquals(original.length(), result.length(), "full脱敏结果长度应与原始值相同");
            assertTrue(result.matches("\\*+"), "full脱敏结果应全部为星号");
            assertEquals("***********", result, "13812345678的full脱敏应为11个星号");
        }
    }

    @Nested
    @DisplayName("场景9: 数据脱敏 - partial 类型 (默认首2尾2)")
    class DataMaskingPartialTest {

        @Test
        void testPartialMask_WithCustomParams() {
            String original = "张三丰abcdef";
            String maskType = "partial";
            List<Object> params = Arrays.asList(2, 2);

            System.out.println("\n--- 场景9: partial脱敏 (keepFirst=2, keepLast=2) ---");
            System.out.println("原始值: '" + original + "'");
            System.out.println("脱敏类型: " + maskType);
            System.out.println("参数: keepFirst=" + params.get(0) + ", keepLast=" + params.get(1));

            String result = strategy.publicApplyMask(original, maskType, params);

            System.out.println("脱敏结果: '" + result + "'");

            assertEquals("张三*****ef", result, "partial脱敏(2,2): 张三丰abcdef(9字符) -> 张三*****ef");
            assertEquals(original.length(), result.length(), "partial脱敏结果长度应与原始值相同");
            assertTrue(result.startsWith("张三"), "partial脱敏应保留前2个字符");
            assertTrue(result.endsWith("ef"), "partial脱敏应保留后2个字符");
        }

        @Test
        void testPartialMask_DefaultParams() {
            String original = "helloworld123456";

            System.out.println("\n--- 场景9b: partial脱敏 (默认参数) ---");
            System.out.println("原始值: '" + original + "'");

            String result = strategy.publicApplyMask(original, "partial", null);

            System.out.println("脱敏结果: '" + result + "'");
            System.out.println("预期: he***********56 (前2后2)");

            assertTrue(result.startsWith("he"), "默认partial应保留前2字符");
            assertTrue(result.endsWith("56"), "默认partial应保留后2字符");
            assertEquals(original.length(), result.length(), "长度不变");
        }
    }

    @Nested
    @DisplayName("场景10: 数据脱敏 - partial 短文本")
    class DataMaskingPartialShortTextTest {

        @Test
        void testPartialMask_ShortText_AllMasked() {
            String original = "AB";
            String maskType = "partial";

            System.out.println("\n--- 场景10: partial短文本全掩码 ---");
            System.out.println("原始值: '" + original + "' (长度=" + original.length() + ")");
            System.out.println("脱敏类型: " + maskType + ", 默认keepFirst=2, keepLast=2");
            System.out.println("说明: 长度<=keepFirst+keepLast时应全部掩码");

            String result = strategy.publicApplyMask(original, maskType, null);

            System.out.println("脱敏结果: '" + result + "'");

            assertEquals("**", result, "短文本AB的partial脱敏应为** (全掩码)");
            assertEquals(original.length(), result.length(), "长度不变");
        }

        @Test
        void testPartialMask_SingleChar() {
            String original = "X";

            System.out.println("\n--- 场景10b: 单字符partial脱敏 ---");
            System.out.println("原始值: '" + original + "'");

            String result = strategy.publicApplyMask(original, "partial", null);

            System.out.println("脱敏结果: '" + result + "'");

            assertEquals("*", result, "单字符应变为一个星号");
        }
    }

    @Nested
    @DisplayName("场景11: 数据脱敏 - email 类型")
    class DataMaskingEmailTest {

        @Test
        void testEmailMask_ValidEmail() {
            String original = "zhangsan@example.com";
            String maskType = "email";

            System.out.println("\n--- 场景11: email脱敏 ---");
            System.out.println("原始值: '" + original + "'");
            System.out.println("脱敏类型: " + maskType);

            String result = strategy.publicApplyMask(original, maskType, null);

            System.out.println("脱敏结果: '" + result + "'");

            assertEquals("z***@example.com", result,
                    "email脱敏: zhangsan@example.com -> z***@example.com");
            assertTrue(result.contains("@"), "email脱敏应保留@符号及域名部分");
            assertTrue(result.startsWith("z"), "email脱敏应保留首字符");
            assertTrue(result.endsWith("@example.com"), "email脱敏应保留域名完整");
        }

        @Test
        void testEmailMask_NoAtSymbol_FallbackToPartial() {
            String original = "notanemail";

            System.out.println("\n--- 场景11b: 无@符号回退到partial ---");
            System.out.println("原始值: '" + original + "'");

            String result = strategy.publicApplyMask(original, "email", null);

            System.out.println("脱敏结果: '" + result + "'");
            System.out.println("说明: 无@符号时email应回退到partial逻辑");

            assertNotNull(result, "无@符号不应返回null");
        }
    }

    @Nested
    @DisplayName("场景12: 数据脱敏 - phone 类型")
    class DataMaskingPhoneTest {

        @Test
        void testPhoneMask_ElevenDigits() {
            String original = "13812345678";
            String maskType = "phone";

            System.out.println("\n--- 场景12: phone脱敏 ---");
            System.out.println("原始值: '" + original + "'");
            System.out.println("脱敏类型: " + maskType);

            String result = strategy.publicApplyMask(original, maskType, null);

            System.out.println("脱敏结果: '" + result + "'");

            assertEquals("138****5678", result,
                    "phone脱敏: 13812345678 -> 138****5678");
            assertTrue(result.startsWith("138"), "phone脱敏应保留前3位");
            assertTrue(result.endsWith("5678"), "phone脱敏应保留后4位");
            assertEquals(original.length(), result.length(), "phone脱敏长度不变");
        }

        @Test
        void testPhoneMask_ShortNumber_FallbackToPartial() {
            String original = "12345";

            System.out.println("\n--- 场景12b: 短号码回退到partial ---");
            System.out.println("原始值: '" + original + "' (长度<7)");

            String result = strategy.publicApplyMask(original, "phone", null);

            System.out.println("脱敏结果: '" + result + "'");
            System.out.println("说明: 长度<7时phone应回退到partial逻辑");

            assertNotNull(result, "短号码不应返回null");
        }
    }

    @Nested
    @DisplayName("场景13: 数据脱敏 - idcard 类型")
    class DataMaskingIdCardTest {

        @Test
        void testIdCardMask_Standard18Digit() {
            String original = "110101199001011234";
            String maskType = "idcard";

            System.out.println("\n--- 场景13: idcard脱敏 ---");
            System.out.println("原始值: '" + original + "'");
            System.out.println("脱敏类型: " + maskType);

            String result = strategy.publicApplyMask(original, maskType, null);

            System.out.println("脱敏结果: '" + result + "'");

            assertEquals("1101**********1234", result,
                    "idcard脱敏: 110101199001011234 -> 1101**********1234");
            assertTrue(result.startsWith("1101"), "idcard脱敏应保留前4位");
            assertTrue(result.endsWith("1234"), "idcard脱敏应保留后4位");
            assertEquals(original.length(), result.length(), "idcard脱敏长度不变");
        }

        @Test
        void testIdCardMask_ShortId_FallbackToPartial() {
            String original = "1234567";

            System.out.println("\n--- 场景13b: 短身份证号回退到partial ---");
            System.out.println("原始值: '" + original + "' (长度<8)");

            String result = strategy.publicApplyMask(original, "idcard", null);

            System.out.println("脱敏结果: '" + result + "'");
            System.out.println("说明: 长度<8时idcard应回退到partial逻辑");

            assertNotNull(result, "短身份证号不应返回null");
        }
    }

    @Nested
    @DisplayName("场景14: 数据脱敏 - JSON规则整体应用 (applyDataMasking)")
    class DataMaskingJsonRuleTest {

        @Test
        void testApplyDataMasking_MixedRules() {
            List<String> columns = Arrays.asList("name", "phone", "email", "salary");
            List<List<String>> rows = new ArrayList<>();
            rows.add(new ArrayList<>(Arrays.asList("张三", "13800138000", "zhang@test.com", "15000")));
            rows.add(new ArrayList<>(Arrays.asList("李四", "13900139000", "li@test.com", "25000")));

            String maskingRule = "{\"phone\":\"phone\",\"email\":\"email\",\"salary\":{\"type\":\"partial\",\"params\":[0,0]}}";

            System.out.println("\n--- 场景14: JSON规则整体应用 applyDataMasking ---");
            System.out.println("输入列: " + columns);
            System.out.println("脱敏规则JSON: " + maskingRule);
            System.out.println("原始数据:");
            for (List<String> row : rows) {
                System.out.println("  " + row);
            }

            strategy.publicApplyDataMasking(rows, columns, maskingRule);

            System.out.println("\n脱敏后数据:");
            for (List<String> row : rows) {
                System.out.println("  " + row);
            }

            assertEquals("138****8000", rows.get(0).get(1),
                    "第1行phone列应被phone格式脱敏: 13800138000 -> 138****8000");
            assertEquals("139****9000", rows.get(1).get(1),
                    "第2行phone列应被phone格式脱敏: 13900139000 -> 139****9000");

            assertEquals("z***@test.com", rows.get(0).get(2),
                    "第1行email列应被email格式脱敏: zhang@test.com -> z***@test.com");
            assertEquals("l***@test.com", rows.get(1).get(2),
                    "第2行email列应被email格式脱敏: li@test.com -> l***@test.com");

            assertEquals("*****", rows.get(0).get(3),
                    "第1行salary列应被partial(0,0)全掩码: 15000 -> *****");
            assertEquals("*****", rows.get(1).get(3),
                    "第2行salary列应被partial(0,0)全掩码: 25000 -> *****");

            assertEquals("张三", rows.get(0).get(0), "name列不在规则中，应保持不变");
            assertEquals("李四", rows.get(1).get(0), "name列不在规则中，应保持不变");
        }
    }

    @Nested
    @DisplayName("场景15: SQL解析 - extractSelectPermissionInfo")
    class SqlParseSelectPermissionInfoTest {

        @Test
        void testSimpleSelect_FromOrders() {
            String sql = "SELECT * FROM orders";
            String datasourceName = "mysql_ds";
            String defaultDatabase = "bigdata_platform";

            System.out.println("\n--- 场景15a: 解析 SELECT * FROM orders ---");
            System.out.println("SQL: " + sql);
            System.out.println("datasourceName: " + datasourceName);
            System.out.println("defaultDatabase: " + defaultDatabase);

            AbstractJdbcExecutionStrategy.SelectPermissionInfo info = strategy.publicExtractSelectPermissionInfo(sql, datasourceName, defaultDatabase);

            System.out.println("解析结果:");
            if (info != null) {
                System.out.println("  operation: " + info.operation);
                System.out.println("  tableRef.tableName: " + (info.tableRef != null ? info.tableRef.tableName : "null"));
                System.out.println("  tableRef.databaseName: " + (info.tableRef != null ? info.tableRef.databaseName : "null"));
            } else {
                System.out.println("  info = null");
            }

            assertNotNull(info, "SELECT语句应成功解析出权限信息");
            assertEquals(DataAccessPermissionService.TABLE_OP_SELECT, info.operation,
                    "operation应为SELECT");
            assertNotNull(info.tableRef, "tableRef不应为null");
            assertEquals("orders", info.tableRef.tableName,
                    "tableName应为orders");
        }

        @Test
        void testQualifiedSelect_WithDatabaseAlias() {
            String sql = "SELECT a.id FROM db_test.orders a";
            String datasourceName = "mysql_ds";
            String defaultDatabase = "bigdata_platform";

            System.out.println("\n--- 场景15b: 解析 SELECT a.id FROM db_test.orders a ---");
            System.out.println("SQL: " + sql);
            System.out.println("datasourceName: " + datasourceName);
            System.out.println("defaultDatabase: " + defaultDatabase);

            AbstractJdbcExecutionStrategy.SelectPermissionInfo info = strategy.publicExtractSelectPermissionInfo(sql, datasourceName, defaultDatabase);

            System.out.println("解析结果:");
            if (info != null) {
                System.out.println("  operation: " + info.operation);
                System.out.println("  tableRef.tableName: " + (info.tableRef != null ? info.tableRef.tableName : "null"));
                System.out.println("  tableRef.databaseName: " + (info.tableRef != null ? info.tableRef.databaseName : "null"));
            } else {
                System.out.println("  info = null");
            }

            assertNotNull(info, "带数据库限定符的SELECT应成功解析");
            assertEquals("orders", info.tableRef.tableName,
                    "tableName应为orders（不含别名）");
            assertEquals("db_test", info.tableRef.databaseName,
                    "databaseName应为db_test");
        }

        @Test
        void testNonSelectStatement_InsertShouldReturnNull() {
            String sql = "INSERT INTO users (name) VALUES ('test')";
            String datasourceName = "mysql_ds";
            String defaultDatabase = "bigdata_platform";

            System.out.println("\n--- 场景15c: INSERT语句应返回null ---");
            System.out.println("SQL: " + sql);

            AbstractJdbcExecutionStrategy.SelectPermissionInfo info = strategy.publicExtractSelectPermissionInfo(sql, datasourceName, defaultDatabase);

            System.out.println("解析结果: " + (info == null ? "null" : "non-null"));

            assertNull(info, "INSERT非SELECT语句应返回null");
        }

        @Test
        void testNonSelectStatement_UpdateShouldReturnNull() {
            String sql = "UPDATE users SET name='new' WHERE id=1";
            String datasourceName = "mysql_ds";
            String defaultDatabase = "bigdata_platform";

            System.out.println("\n--- 场景15d: UPDATE语句应返回null ---");
            System.out.println("SQL: " + sql);

            AbstractJdbcExecutionStrategy.SelectPermissionInfo info = strategy.publicExtractSelectPermissionInfo(sql, datasourceName, defaultDatabase);

            System.out.println("解析结果: " + (info == null ? "null" : "non-null"));

            assertNull(info, "UPDATE非SELECT语句应返回null");
        }

        @Test
        void testNonSelectStatement_DeleteShouldReturnNull() {
            String sql = "DELETE FROM users WHERE id=1";
            String datasourceName = "mysql_ds";
            String defaultDatabase = "bigdata_platform";

            System.out.println("\n--- 场景15e: DELETE语句应返回null ---");
            System.out.println("SQL: " + sql);

            AbstractJdbcExecutionStrategy.SelectPermissionInfo info = strategy.publicExtractSelectPermissionInfo(sql, datasourceName, defaultDatabase);

            System.out.println("解析结果: " + (info == null ? "null" : "non-null"));

            assertNull(info, "DELETE非SELECT语句应返回null");
        }
    }

    @Nested
    @DisplayName("额外验证: JSqlParser表达式求值单元级别测试")
    class JSqlParserExpressionEvalTest {

        private List<String> columns = java.util.Arrays.asList("col");
        private Map<String, Integer> colIndexMap;

        @BeforeEach
        void setUp() {
            colIndexMap = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                colIndexMap.put(columns.get(i).toLowerCase(), i);
            }
        }

        @Test
        void testJSqlParser_Equality() {
            System.out.println("\n--- 额外: JSqlParser等值表达式测试 ---");

            assertTrue(strategy.publicEvaluateRowFilterExpression(
                    java.util.Arrays.asList("1"), columns, colIndexMap, "WHERE col = 1"),
                    "col=1, cellValue='1' 应为true");
            assertFalse(strategy.publicEvaluateRowFilterExpression(
                    java.util.Arrays.asList("1"), columns, colIndexMap, "WHERE col = 2"),
                    "col=2, cellValue='1' 应为false");
        }

        @Test
        void testJSqlParser_Inequality() {
            System.out.println("\n--- 额外: JSqlParser不等值表达式测试 ---");

            assertFalse(strategy.publicEvaluateRowFilterExpression(
                    java.util.Arrays.asList("1"), columns, colIndexMap, "WHERE col != 1"),
                    "col!=1, cellValue='1' 应为false");
            assertTrue(strategy.publicEvaluateRowFilterExpression(
                    java.util.Arrays.asList("1"), columns, colIndexMap, "WHERE col != 2"),
                    "col!=2, cellValue='1' 应为true");
            assertTrue(strategy.publicEvaluateRowFilterExpression(
                    java.util.Arrays.asList("1"), columns, colIndexMap, "WHERE col <> 2"),
                    "col<>2, cellValue='1' 应为true");
        }

        @Test
        void testJSqlParser_NumericComparison() {
            System.out.println("\n--- 额外: JSqlParser数值比较表达式测试 ---");

            assertTrue(strategy.publicEvaluateRowFilterExpression(
                    java.util.Arrays.asList("10"), columns, colIndexMap, "WHERE col > 5"),
                    "col>5, cellValue='10' 应为true");
            assertFalse(strategy.publicEvaluateRowFilterExpression(
                    java.util.Arrays.asList("3"), columns, colIndexMap, "WHERE col > 5"),
                    "col>5, cellValue='3' 应为false");
            assertTrue(strategy.publicEvaluateRowFilterExpression(
                    java.util.Arrays.asList("5"), columns, colIndexMap, "WHERE col >= 5"),
                    "col>=5, cellValue='5' 应为true");
            assertTrue(strategy.publicEvaluateRowFilterExpression(
                    java.util.Arrays.asList("3"), columns, colIndexMap, "WHERE col < 5"),
                    "col<5, cellValue='3' 应为true");
            assertFalse(strategy.publicEvaluateRowFilterExpression(
                    java.util.Arrays.asList("10"), columns, colIndexMap, "WHERE col < 5"),
                    "col<5, cellValue='10' 应为false");
            assertTrue(strategy.publicEvaluateRowFilterExpression(
                    java.util.Arrays.asList("5"), columns, colIndexMap, "WHERE col <= 5"),
                    "col<=5, cellValue='5' 应为true");
        }

        @Test
        void testJSqlParser_Like() {
            System.out.println("\n--- 额外: JSqlParser LIKE表达式测试 ---");

            List<String> cols = java.util.Arrays.asList("name");
            Map<String, Integer> idxMap = new LinkedHashMap<>();
            idxMap.put("name", 0);

            assertTrue(strategy.publicEvaluateRowFilterExpression(
                    java.util.Arrays.asList("apple"), cols, idxMap, "WHERE name LIKE 'app%'"),
                    "apple LIKE app% 应为true");
            assertTrue(strategy.publicEvaluateRowFilterExpression(
                    java.util.Arrays.asList("application"), cols, idxMap, "WHERE name LIKE 'app%'"),
                    "application LIKE app% 应为true");
            assertFalse(strategy.publicEvaluateRowFilterExpression(
                    java.util.Arrays.asList("banana"), cols, idxMap, "WHERE name LIKE 'app%'"),
                    "banana LIKE app% 应为false");
        }

        @Test
        void testJSqlParser_In() {
            System.out.println("\n--- 额外: JSqlParser IN表达式测试 ---");

            List<String> cols = java.util.Arrays.asList("dept");
            Map<String, Integer> idxMap = new LinkedHashMap<>();
            idxMap.put("dept", 0);

            assertTrue(strategy.publicEvaluateRowFilterExpression(
                    java.util.Arrays.asList("IT"), cols, idxMap, "WHERE dept IN ('IT','Finance')"),
                    "IT IN (IT, Finance) 应为true");
            assertTrue(strategy.publicEvaluateRowFilterExpression(
                    java.util.Arrays.asList("Finance"), cols, idxMap, "WHERE dept IN ('IT','Finance')"),
                    "Finance IN (IT, Finance) 应为true");
            assertFalse(strategy.publicEvaluateRowFilterExpression(
                    java.util.Arrays.asList("HR"), cols, idxMap, "WHERE dept IN ('IT','Finance')"),
                    "HR IN (IT, Finance) 应为false");
        }

        @Test
        void testJSqlParser_AndCombination() {
            System.out.println("\n--- 额外: JSqlParser AND组合表达式测试 ---");

            List<String> cols = java.util.Arrays.asList("status", "age");
            Map<String, Integer> idxMap = new LinkedHashMap<>();
            idxMap.put("status", 0);
            idxMap.put("age", 1);

            assertTrue(strategy.publicEvaluateRowFilterExpression(
                    java.util.Arrays.asList("1", "20"), cols, idxMap, "WHERE status = 1 AND age < 30"),
                    "status=1 AND age<20 应为true");
            assertFalse(strategy.publicEvaluateRowFilterExpression(
                    java.util.Arrays.asList("1", "35"), cols, idxMap, "WHERE status = 1 AND age < 30"),
                    "status=1 AND age=35 应为false");
            assertFalse(strategy.publicEvaluateRowFilterExpression(
                    java.util.Arrays.asList("0", "20"), cols, idxMap, "WHERE status = 1 AND age < 30"),
                    "status=0 AND age=20 应为false");
        }

        @Test
        void testJSqlParser_OrCombination() {
            System.out.println("\n--- 额外: JSqlParser OR组合表达式测试 ---");

            List<String> cols = java.util.Arrays.asList("dept");
            Map<String, Integer> idxMap = new LinkedHashMap<>();
            idxMap.put("dept", 0);

            assertTrue(strategy.publicEvaluateRowFilterExpression(
                    java.util.Arrays.asList("IT"), cols, idxMap, "WHERE dept = 'IT' OR dept = 'HR'"),
                    "IT: IT OR HR 应为true");
            assertTrue(strategy.publicEvaluateRowFilterExpression(
                    java.util.Arrays.asList("HR"), cols, idxMap, "WHERE dept = 'IT' OR dept = 'HR'"),
                    "HR: IT OR HR 应为true");
            assertFalse(strategy.publicEvaluateRowFilterExpression(
                    java.util.Arrays.asList("Finance"), cols, idxMap, "WHERE dept = 'IT' OR dept = 'HR'"),
                    "Finance: IT OR HR 应为false");
        }

        @Test
        void testJSqlParser_Parenthesis() {
            System.out.println("\n--- 额外: JSqlParser括号表达式测试 ---");

            List<String> cols = java.util.Arrays.asList("a", "b", "c");
            Map<String, Integer> idxMap = new LinkedHashMap<>();
            idxMap.put("a", 0);
            idxMap.put("b", 1);
            idxMap.put("c", 2);

            assertTrue(strategy.publicEvaluateRowFilterExpression(
                    java.util.Arrays.asList("1", "0", "1"), cols, idxMap,
                    "WHERE (a = 1 OR b = 1) AND c = 1"),
                    "(a=1 OR b=1) AND c=1, row(1,0,1) 应为true");
            assertFalse(strategy.publicEvaluateRowFilterExpression(
                    java.util.Arrays.asList("0", "0", "1"), cols, idxMap,
                    "WHERE (a = 1 OR b = 1) AND c = 1"),
                    "(a=1 OR b=1) AND c=1, row(0,0,1) 应为false");
        }
    }

    @Nested
    @DisplayName("集成验证: 通过真实MySQL连接执行端到端流程")
    class RealMySQLEndToEndTest {

        @Test
        void testRealMySQLConnectionAndBasicQuery() throws Exception {
            System.out.println("\n=== 集成验证: 真实MySQL连接 ===");

            try (Connection conn = dataSource.getConnection()) {
                System.out.println("MySQL连接成功!");

                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT VERSION()")) {
                    if (rs.next()) {
                        String version = rs.getString(1);
                        System.out.println("MySQL版本: " + version);
                        assertNotNull(version, "应获取到MySQL版本信息");
                    }
                }

                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(
                             "SELECT COUNT(*) AS cnt FROM information_schema.tables WHERE TABLE_SCHEMA = 'bigdata_platform'")) {
                    if (rs.next()) {
                        int tableCount = rs.getInt(1);
                        System.out.println("bigdata_platform数据库表数量: " + tableCount);
                        assertTrue(tableCount >= 0, "表数量应为非负整数");
                    }
                }

                System.out.println("真实MySQL连接验证通过!");
            }
        }

        @Test
        void testEndToEnd_RowFilterOnRealData() throws Exception {
            System.out.println("\n=== 集成验证: 真实数据上的行过滤端到端测试 ===");

            try (Connection conn = dataSource.getConnection()) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("DROP TABLE IF EXISTS test_row_filter_tmp");
                    stmt.execute("CREATE TABLE test_row_filter_tmp (id INT PRIMARY KEY, name VARCHAR(50), status INT, dept VARCHAR(50))");
                    stmt.execute("INSERT INTO test_row_filter_tmp VALUES (1,'Alice',1,'IT'),(2,'Bob',0,'HR'),(3,'Charlie',1,'Finance')");
                }

                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT id, name, status, dept FROM test_row_filter_tmp")) {

                    List<String> columns = new ArrayList<>();
                    int colCount = rs.getMetaData().getColumnCount();
                    for (int c = 1; c <= colCount; c++) {
                        columns.add(rs.getMetaData().getColumnLabel(c));
                    }

                    List<List<String>> rows = new ArrayList<>();
                    while (rs.next()) {
                        List<String> row = new ArrayList<>();
                        for (int c = 1; c <= colCount; c++) {
                            row.add(rs.getString(c));
                        }
                        rows.add(row);
                    }

                    System.out.println("从MySQL查询到的原始数据 (" + rows.size() + "行):");
                    for (List<String> row : rows) {
                        System.out.println("  " + row);
                    }

                    List<List<String>> filtered = strategy.publicApplyRowFilter(rows, columns, "WHERE status = 1");

                    System.out.println("过滤后的数据 (" + filtered.size() + "行):");
                    for (List<String> row : filtered) {
                        System.out.println("  " + row);
                    }

                    assertEquals(2, filtered.size(),
                            "真实MySQL数据上应用status=1过滤应保留2行");
                    assertEquals("Alice", filtered.get(0).get(1));
                    assertEquals("Charlie", filtered.get(1).get(1));
                }

                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("DROP TABLE IF EXISTS test_row_filter_tmp");
                }

                System.out.println("端到端行过滤验证通过!");
            }
        }

        @Test
        void testEndToEnd_DataMaskingOnRealData() throws Exception {
            System.out.println("\n=== 集成验证: 真实数据上的数据脱敏端到端测试 ===");

            try (Connection conn = dataSource.getConnection()) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("DROP TABLE IF EXISTS test_data_masking_tmp");
                    stmt.execute("CREATE TABLE test_data_masking_tmp (name VARCHAR(50), phone VARCHAR(20), email VARCHAR(100))");
                    stmt.execute("INSERT INTO test_data_masking_tmp VALUES ('张三','13800138000','zhang@test.com'),('李四','13900139000','li@test.com')");
                }

                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT name, phone, email FROM test_data_masking_tmp")) {

                    List<String> columns = new ArrayList<>();
                    int colCount = rs.getMetaData().getColumnCount();
                    for (int c = 1; c <= colCount; c++) {
                        columns.add(rs.getMetaData().getColumnLabel(c));
                    }

                    List<List<String>> rows = new ArrayList<>();
                    while (rs.next()) {
                        List<String> row = new ArrayList<>();
                        for (int c = 1; c <= colCount; c++) {
                            row.add(rs.getString(c));
                        }
                        rows.add(row);
                    }

                    System.out.println("从MySQL查询到的原始数据 (" + rows.size() + "行):");
                    for (List<String> row : rows) {
                        System.out.println("  " + row);
                    }

                    String maskingRule = "{\"phone\":\"phone\",\"email\":\"email\"}";
                    System.out.println("应用脱敏规则: " + maskingRule);

                    strategy.publicApplyDataMasking(rows, columns, maskingRule);

                    System.out.println("脱敏后的数据:");
                    for (List<String> row : rows) {
                        System.out.println("  " + row);
                    }

                    assertEquals("138****8000", rows.get(0).get(1),
                            "第1行phone应被脱敏");
                    assertEquals("z***@test.com", rows.get(0).get(2),
                            "第1行email应被脱敏");
                    assertEquals("张三", rows.get(0).get(0),
                            "name列保持不变");
                }

                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("DROP TABLE IF EXISTS test_data_masking_tmp");
                }

                System.out.println("端到端数据脱敏验证通过!");
            }
        }
    }
}
