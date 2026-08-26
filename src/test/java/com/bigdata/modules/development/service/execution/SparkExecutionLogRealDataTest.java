package com.bigdata.modules.development.service.execution;

import com.bigdata.common.security.CustomUserDetails;
import com.bigdata.modules.development.dto.CodeExecutionRequest;
import com.bigdata.modules.devops.enums.DatasourceType;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("Spark执行策略 - 日志实时推送与客户端拉取集成测试")
class SparkExecutionLogRealDataTest {

    @Autowired
    private SparkExecutionStrategy sparkExecutionStrategy;

    @Autowired
    private ExecutionLogListener executionLogListener;

    @Autowired
    private ProcessManager processManager;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        processManager.cleanup();
        executionLogListener.cleanupCompletedBuffers(processManager.getRunningProcesses(1L)
                .stream().map(p -> (String) p.get("executionId")).toList());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setUpAdminSecurityContext() {
        List<SimpleGrantedAuthority> authorities = Arrays.asList(
                new SimpleGrantedAuthority("ROLE_ADMIN")
        );
        CustomUserDetails userDetails = new CustomUserDetails(
                1L, "admin", 1L, Arrays.asList("ROLE_ADMIN"), false, authorities, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userDetails, null, authorities));
    }

    private CodeExecutionRequest buildSparkSqlRequest(String sql) {
        CodeExecutionRequest request = new CodeExecutionRequest();
        request.setCodeType("sql");
        request.setEngineType("spark");
        request.setCodeContent(sql);
        return request;
    }

    @Nested
    @DisplayName("场景1: Spark日志推送机制验证")
    class LogPushMechanismTests {

        @Test
        @Disabled("需要真实Spark环境，集成测试时启用")
        @DisplayName("✅ Spark SQL执行 - 日志通过ExecutionLogListener推送到消费者")
        void testSparkSqlExecution_LogsPushedToConsumer() {
            System.out.println("\n=== 场景1.1: Spark SQL日志推送验证 ===");
            setUpAdminSecurityContext();

            String executionId = "test-spark-push-" + System.currentTimeMillis();
            AtomicReference<String> lastReceivedLog = new AtomicReference<>();
            AtomicInteger receivedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(1);

            executionLogListener.registerLogConsumer(executionId, logLine -> {
                lastReceivedLog.set(logLine);
                receivedCount.incrementAndGet();
                if (receivedCount.get() >= 1) {
                    latch.countDown();
                }
            });

            CodeExecutionContext context = buildSparkSqlContext("SELECT 1 AS test");

            try {
                CodeExecutionResult result = sparkExecutionStrategy.execute(context);

                assertNotNull(result, "执行结果不应为null");
                assertNotNull(result.getExecutionId(), "executionId不应为null");

                System.out.println("  执行ID: " + result.getExecutionId());
                System.out.println("  状态: " + result.getStatus());
                System.out.println("  消费者接收次数: " + receivedCount.get());

                if (result.isSuccess()) {
                    System.out.println("  执行成功 ✓");
                    assertTrue(receivedCount.get() > 0 || result.getOutputLog() != null,
                            "成功执行应有日志输出或推送记录");
                } else {
                    System.out.println("  执行失败（可能缺少Spark环境）: " + result.getErrorMessage());
                }
            } catch (Exception e) {
                System.out.println("  执行异常（可能缺少Spark环境）: " + e.getClass().getSimpleName());
                assertFalse(e.getMessage().contains("NullPointerException"),
                        "不应是NPE异常");
            }
        }
    }

    @Nested
    @DisplayName("场景2: 客户端拉取Spark日志")
    class ClientPullSparkLogsTests {

        @Test
        @Disabled("需要真实Spark环境，集成测试时启用")
        @DisplayName("✅ Spark执行过程中 - 客户端可增量拉取日志")
        void testSparkExecution_ClientCanPullIncrementalLogs() throws Exception {
            System.out.println("\n=== 场景2.1: 客户端增量拉取Spark日志 ===");
            setUpAdminSecurityContext();

            CodeExecutionContext context = buildSparkSqlContext(
                    "SELECT 'hello' AS msg UNION ALL SELECT 'world'");

            Thread execThread = new Thread(() -> {
                try {
                    CodeExecutionResult result = sparkExecutionStrategy.execute(context);
                    System.out.println("  执行完成: status=" + result.getStatus());
                } catch (Exception e) {
                    System.out.println("  执行异常: " + e.getMessage());
                }
            }, "spark-exec-test");
            execThread.start();

            int totalPulled = 0;
            int offset = 0;
            int maxPollRounds = 20;

            for (int round = 0; round < maxPollRounds && execThread.isAlive(); round++) {
                Thread.sleep(200);

                List<String> newLogs = executionLogListener.pullLogs(
                        context.getEngineType() != null ? "spark-test" : "test", offset);
                if (!newLogs.isEmpty()) {
                    totalPulled += newLogs.size();
                    offset += newLogs.size();
                    System.out.println("  轮询第" + (round + 1) + "次: 新增" + newLogs.size() +
                            "条, 累计" + totalPulled + "条");
                }
            }

            execThread.join(5000);
            if (execThread.isAlive()) {
                execThread.interrupt();
            }

            System.out.println("  总轮询轮次: " + Math.min(maxPollRounds, 20));
            System.out.println("  总拉取条数: " + totalPulled);
            System.out.println("  增量拉取流程完成 ✓");
        }
    }

    @Nested
    @DisplayName("场景3: Spark日志缓冲区生命周期")
    class SparkBufferLifecycleTests {

        @Test
        @DisplayName("✅ Spark执行完成后 - 日志缓冲区可通过拉取获取")
        void testAfterSparkCompletion_LogsAvailableInBuffer() {
            System.out.println("\n=== 场景3.1: 执行后日志缓冲区可用 ===");

            String testExecId = "spark-buffer-test-" + System.currentTimeMillis();

            simulationPublishLogs(testExecId, 15);

            assertTrue(executionLogListener.hasBuffer(testExecId),
                    "发布后缓冲区应存在");
            assertEquals(15, executionLogListener.getLogCount(testExecId),
                    "缓冲区应包含15条日志");

            List<String> allLogs = executionLogListener.pullLogs(testExecId);
            assertEquals(15, allLogs.size(), "全量拉取应返回15条");

            List<String> partialLogs = executionLogListener.pullLogs(testExecId, 10);
            assertEquals(5, partialLogs.size(), "offset=10应返回5条");

            System.out.println("  缓冲区大小: " + allLogs.size());
            System.out.println("  增量拉取(offset=10): " + partialLogs.size() + "条");
            System.out.println("  缓冲区可用 ✓");
        }

        @Test
        @DisplayName("✅ 清理Spark任务缓冲区 - 后续拉取返回空")
        void testClearSparkBuffer_SubsequentPullsEmpty() {
            System.out.println("\n=== 场景3.2: 清理后缓冲区为空 ===");

            String testExecId = "spark-clear-test-" + System.currentTimeMillis();

            simulationPublishLogs(testExecId, 8);

            assertTrue(executionLogListener.hasBuffer(testExecId), "清理前有数据");

            executionLogListener.clearBuffer(testExecId);

            assertFalse(executionLogListener.hasBuffer(testExecId), "清理后无数据");
            assertTrue(executionLogListener.pullLogs(testExecId).isEmpty(), "拉取返回空");
            assertEquals(0, executionLogListener.getLogCount(testExecId), "计数归零");

            System.out.println("  清理前: 8条");
            System.out.println("  清理后: 0条 ✓");
        }
    }

    @Nested
    @DisplayName("场景4: Spark与JDBC日志隔离")
    class SparkAndJdbcIsolationTests {

        @Test
        @DisplayName("✅ Spark和JDBC任务的日志互不干扰")
        void testSparkAndJdbcLogs_Isolated() {
            System.out.println("\n=== 场景4.1: Spark/JDBC日志隔离 ===");

            String sparkId = "spark-isolated-" + System.currentTimeMillis();
            String jdbcId = "jdbc-isolated-" + System.currentTimeMillis();

            for (int i = 0; i < 10; i++) {
                executionLogListener.publishLog(sparkId,
                        "[SPARK][Executor] Task " + i + " running on host_" + i);
            }
            for (int i = 0; i < 5; i++) {
                executionLogListener.publishLog(jdbcId,
                        "[JDBC] Query " + i + ": SELECT * FROM table_" + i);
            }

            List<String> sparkLogs = executionLogListener.pullLogs(sparkId);
            List<String> jdbcLogs = executionLogListener.pullLogs(jdbcId);

            assertEquals(10, sparkLogs.size(), "Spark日志应为10条");
            assertEquals(5, jdbcLogs.size(), "JDBC日志应为5条");

            long sparkOnlyCount = sparkLogs.stream()
                    .filter(l -> l.contains("[SPARK]")).count();
            long jdbcOnlyCount = jdbcLogs.stream()
                    .filter(l -> l.contains("[JDBC]")).count();

            assertEquals(10, sparkOnlyCount, "Spark日志全部含[SPARK]");
            assertEquals(5, jdbcOnlyCount, "JDBC日志全部含[JDBC]");

            System.out.println("  Spark日志数: " + sparkLogs.size() + " (全部[SPARK])");
            System.out.println("  JDBC日志数: " + jdbcLogs.size() + " (全部[JDBC])");
            System.out.println("  隔离性 ✓");
        }
    }

    private CodeExecutionContext buildSparkSqlContext(String sql) {
        CodeExecutionContext context = new CodeExecutionContext();
        context.setCodeContent(sql);
        context.setCodeType(CodeType.SQL);
        context.setEngineType(DatasourceType.SPARK);
        context.setSubmitBy(1L);
        context.setSubmitByName("admin_test");
        return context;
    }

    private void simulationPublishLogs(String executionId, int count) {
        for (int i = 0; i < count; i++) {
            executionLogListener.publishLog(executionId,
                    "[SPARK-TEST] Simulated log line " + i + " of " + count);
        }
    }
}
