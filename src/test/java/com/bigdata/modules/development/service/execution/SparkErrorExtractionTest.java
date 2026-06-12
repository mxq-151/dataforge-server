package com.bigdata.modules.development.service.execution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SparkExecutionStrategy 错误信息提取单元测试。
 */
@ExtendWith(MockitoExtension.class)
class SparkErrorExtractionTest {

    @Mock
    private SparkExecutionStrategy strategy;

    private Method extractCoreErrorMethod;
    private Method countLinesMethod;

    @BeforeEach
    void setUp() throws Exception {
        extractCoreErrorMethod = SparkExecutionStrategy.class.getDeclaredMethod("extractCoreError", String.class);
        extractCoreErrorMethod.setAccessible(true);

        countLinesMethod = SparkExecutionStrategy.class.getDeclaredMethod("countLines", String.class);
        countLinesMethod.setAccessible(true);
    }

    private String extractCoreError(String rawOutput) throws Exception {
        return (String) extractCoreErrorMethod.invoke(strategy, rawOutput);
    }

    // ==================== 场景1: Exception in thread ====================

    @Nested
    class ExceptionInThreadTests {

        @Test
        void testExceptionInThread() throws Exception {
            String sparkOutput = buildSparkOutput(
                    "24/06/12 17:09:37 INFO SparkContext: Running Spark version 4.1.1",
                    "24/06/12 17:09:37 INFO SparkContext: Successfully started",
                    "",
                    "Exception in thread \"main\" org.apache.spark.SparkException: " +
                            "Master spark://localhost:7077 is not responding",
                    "\tat org.apache.spark.SparkContext.<init>(SparkContext.scala:500)",
                    "\tat org.apache.spark.SparkContext$.main(SparkContext.scala:1200)",
                    "\t... 3 more",
                    "Caused by: java.net.ConnectException: Connection refused"
            );

            String error = extractCoreError(sparkOutput);

            assertNotNull(error);
            assertTrue(error.contains("Exception in thread"), "应包含异常头");
            assertTrue(error.contains("SparkException"), "应包含异常类名");
            assertTrue(error.contains("Master spark://localhost:7077 is not responding"),
                    "应包含核心错误消息");
            assertTrue(error.contains("Caused by"), "应包含根因");
        }
    }

    // ==================== 场景2: Caused by 根因 ====================

    @Nested
    class CausedByTests {

        @Test
        void testCausedBy() throws Exception {
            String sparkOutput = buildSparkOutput(
                    "24/06/12 17:09:37 WARN Utils: Truncated server string",
                    "24/06/12 17:09:37 ERROR SparkSubmit: Application finished with failed state",
                    "",
                    "Caused by: java.sql.SQLException: No suitable driver found for kyuubi",
                    "\tat java.sql.DriverManager.getConnection(DriverManager.java:698)",
                    "\t... 5 more"
            );

            String error = extractCoreError(sparkOutput);

            assertNotNull(error);
            assertTrue(error.contains("Caused by"), "应以Caused by开头");
            assertTrue(error.contains("SQLException"), "应包含SQL异常");
            assertTrue(error.contains("No suitable driver found for kyuubi"), "应包含核心消息");
        }
    }

    // ==================== 场景3: AnalysisException ====================

    @Nested
    class AnalysisExceptionTests {

        @Test
        void testTableNotFound() throws Exception {
            String sparkOutput = buildSparkOutput(
                    "24/06/12 17:09:37 INFO SparkSession: Created session",
                    "24/06/12 17:09:37 INFO Context: Starting job",
                    "",
                    "org.apache.spark.sql.AnalysisException: [TABLE_OR_VIEW_NOT_FOUND] " +
                            "The table or view `tmall_dw.ods_behavior_log2` cannot be found.",
                    "Verify the spelling and correctness of the schema and catalog.",
                    "\tat org.apache.spark.sql.catalyst.analysis.package$AnalysisErrorAt.tableNotFound(package.scala:91)",
                    "\tat org.apache.spark.sql.catalyst.analysis.CheckAnalysis.$anonfun$checkAnalysis0$2(CheckAnalysis.scala:303)"
            );

            String error = extractCoreError(sparkOutput);

            assertNotNull(error);
            assertTrue(error.contains("AnalysisException"), "应包含AnalysisException");
            assertTrue(error.contains("TABLE_OR_VIEW_NOT_FOUND"), "应包含错误码");
            assertTrue(error.contains("ods_behavior_log2"), "应包含表名");
        }

        @Test
        void testSyntaxError() throws Exception {
            String sparkOutput = buildSparkOutput(
                    "24/06/12 17:09:38 INFO SparkSession: Parsing SQL...",
                    "",
                    "org.apache.spark.sql.catalyst.parser.ParseException:",
                    "\nmismatched input 'FROM' expecting {<EOF>, ...}",
                    "\n== SQL ==",
                    "\nSELEC * FORM ods_product",
                    "\tat org.apache.spark.sql.execution.SparkSqlParser.parse(SparkSqlParser.scala:50)"
            );

            String error = extractCoreError(sparkOutput);

            assertNotNull(error);
            assertTrue(error.contains("ParseException"), "应包含ParseException");
            assertTrue(error.contains("mismatched input"), "应包含语法错误详情");
        }
    }

    // ==================== 场景4: ERROR日志行提取 ====================

    @Nested
    class ErrorLogLineTests {

        @Test
        void testErrorLogLinesOnly() throws Exception {
            String sparkOutput = buildSparkOutput(
                    "24/06/12 17:09:37 INFO SparkContext: Submitting application",
                    "24/06/12 17:09:37 WARN Utils: Service 'sparkDriver' could not register",
                    "24/06/12 17:09:37 ERROR SparkSubmit: Failed to submit application",
                    "24/06/12 17:09:37 ERROR SparkSubmit: Application report state: FAILED",
                    "24/06/12 17:09:37 FATAL SparkSubmit: Exiting with code 1"
            );

            String error = extractCoreError(sparkOutput);

            assertNotNull(error);
            assertTrue(error.contains("Failed to submit application"), "应包含失败原因");
            assertTrue(error.contains("FAILED"), "应包含状态");
        }
    }

    // ==================== 场景5: 兜底 - 最后N行 ====================

    @Nested
    class FallbackLastLinesTests {

        @Test
        void testFallbackToLastLines() throws Exception {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 80; i++) {
                sb.append(String.format("24/06/12 17:09:%02d INFO SomeClass: Processing item %d%n", i % 60, i));
            }
            sb.append("\n");
            sb.append("Application failed with exit code 1\n");
            sb.append("Please check logs above for details\n");

            String error = extractCoreError(sb.toString());

            assertNotNull(error);
            assertFalse(error.length() > sb.toString().length(), "不应超过原始长度");
            assertTrue(error.contains("exit code 1") || error.contains("details"),
                    "兜底时应包含末尾的关键信息");
        }
    }

    // ==================== 场景6: 空输入边界情况 ====================

    @Nested
    class EdgeCaseTests {

        @Test
        void testNullInput() throws Exception {
            String error = extractCoreError(null);
            assertEquals("Spark执行失败（无输出信息）", error);
        }

        @Test
        void testEmptyInput() throws Exception {
            String error = extractCoreError("");
            assertEquals("Spark执行失败（无输出信息）", error);
        }

        @Test
        void testBlankInput() throws Exception {
            String error = extractCoreError("   \n  \n  ");
            assertEquals("Spark执行失败（无输出信息）", error);
        }

        @Test
        void testSingleNonErrorLine() throws Exception {
            String error = extractCoreError("just a simple message");
            assertEquals("just a simple message", error);
        }

        @Test
        void testCountLines() throws Exception {
            int lines = (Integer) countLinesMethod.invoke(strategy, "line1\nline2\nline3\n");
            assertEquals(3, lines);

            int empty = (Integer) countLinesMethod.invoke(strategy, "");
            assertEquals(0, empty);

            int nullCount = (Integer) countLinesMethod.invoke(strategy, (String) null);
            assertEquals(0, nullCount);
        }
    }

    // ==================== 场景7: 实际业务场景 ====================

    @Nested
    class RealWorldScenarioTests {

        @Test
        void testKyuubiConnectionRefused() throws Exception {
            String sparkOutput = buildSparkOutput(
                    "24/06/12 17:09:37 INFO SparkSubmit: Parsing arguments",
                    "24/06/12 17:09:37 INFO SparkSubmit: --conf spark.181_host.url=http://kyuubi.com:10099",
                    "24/06/12 17:09:37 INFO SparkContext: Starting Spark context",
                    "24/06/12 17:09:37 WARN Utils: Truncated server string to: kyuubi.com:10099",
                    "",
                    "Exception in thread \"main\" java.util.concurrent.ExecutionException: " +
                            "org.apache.kyuubi.KyuubiSQLException: Error connecting to Kyuubi server at kyuubi.com:10099",
                    "\tat java.util.concurrent.FutureTask.report(FutureTask.java:122)",
                    "\t... 8 more",
                    "Caused by: java.net.ConnectException: Connection refused (Connection refused)",
                    "\t... 5 more"
            );

            String error = extractCoreError(sparkOutput);

            assertNotNull(error);
            assertTrue(error.contains("KyuubiSQLException"), "应识别Kyuubi异常");
            assertTrue(error.contains("kyuubi.com:10099"), "应包含目标地址");
            assertTrue(error.contains("Connection refused"), "应包含连接拒绝");
        }

        @Test
        void testUseStatementFailure() throws Exception {
            String sparkOutput = buildSparkOutput(
                    "24/06/12 17:09:37 INFO SparkSubmit: Submitting SQL: USE tmall_dw;",
                    "24/06/12 17:09:37 INFO SparkContext: Starting job",
                    "24/06/12 17:09:37 WARN HiveMetaStore: Retrying connect to metastore",
                    "",
                    "org.apache.spark.sql.catalyst.analysis.NoSuchDatabaseException: Database 'tmall_dw' not found",
                    "\tat org.apache.spark.sql.hive.client.HiveClientImpl.getDatabase(HiveClientImpl.scala:200)",
                    "\t... 10 more"
            );

            String error = extractCoreError(sparkOutput);

            assertNotNull(error);
            assertTrue(error.contains("NoSuchDatabaseException"), "应识别数据库不存在异常");
            assertTrue(error.contains("tmall_dw"), "应包含数据库名");
        }
    }

    private static String buildSparkOutput(String... lines) {
        return String.join("\n", lines);
    }
}
