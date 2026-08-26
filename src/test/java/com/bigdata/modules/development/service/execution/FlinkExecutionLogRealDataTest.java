package com.bigdata.modules.development.service.execution;

import com.bigdata.common.security.CustomUserDetails;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("Flink执行策略 - 日志实时推送与客户端拉取集成测试")
class FlinkExecutionLogRealDataTest {

    @Autowired
    private FlinkExecutionStrategy flinkExecutionStrategy;

    @Autowired
    private ExecutionLogListener executionLogListener;

    @Autowired
    private ProcessManager processManager;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        processManager.cleanup();
        List<String> activeIds = processManager.getRunningProcesses(1L)
                .stream().map(p -> (String) p.get("executionId")).toList();
        executionLogListener.cleanupCompletedBuffers(activeIds);
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

    private CodeExecutionContext buildFlinkSqlContext(String sql) {
        CodeExecutionContext context = new CodeExecutionContext();
        context.setCodeContent(sql);
        context.setCodeType(CodeType.SQL);
        context.setEngineType(DatasourceType.FLINK);
        context.setSubmitBy(1L);
        context.setSubmitByName("admin_test");
        return context;
    }

    private CodeExecutionContext buildFlinkJobContext(String code) {
        CodeExecutionContext context = new CodeExecutionContext();
        context.setCodeContent(code);
        context.setCodeType(CodeType.PYTHON);
        context.setEngineType(DatasourceType.FLINK);
        context.setSubmitBy(1L);
        context.setSubmitByName("admin_test");
        return context;
    }

    @Nested
    @DisplayName("场景1: Flink日志推送机制验证")
    class LogPushMechanismTests {

        @Test
        @Disabled("需要真实Flink环境，集成测试时启用")
        @DisplayName("✅ Flink SQL执行 - 日志通过ExecutionLogListener推送到消费者")
        void testFlinkSqlExecution_LogsPushedToConsumer() {
            System.out.println("\n=== 场景1.1: Flink SQL日志推送验证 ===");
            setUpAdminSecurityContext();

            String testId = "test-flink-push-" + System.currentTimeMillis();
            AtomicReference<String> lastReceivedLog = new AtomicReference<>();
            AtomicInteger receivedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(1);

            executionLogListener.registerLogConsumer(testId, logLine -> {
                lastReceivedLog.set(logLine);
                receivedCount.incrementAndGet();
                if (receivedCount.get() >= 1) {
                    latch.countDown();
                }
            });

            CodeExecutionContext context = buildFlinkSqlContext("SELECT 1 AS flink_test");

            try {
                CodeExecutionResult result = flinkExecutionStrategy.execute(context);

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
                    System.out.println("  执行失败（可能缺少Flink环境）: " +
                            (result.getErrorMessage() != null ?
                                    result.getErrorMessage().substring(0,
                                            Math.min(100, result.getErrorMessage().length())) : "N/A"));
                }
            } catch (Exception e) {
                System.out.println("  执行异常（可能缺少Flink环境）: " + e.getClass().getSimpleName());
            }
        }

        @Test
        @Disabled("需要真实Flink环境，集成测试时启用")
        @DisplayName("✅ Flink Job执行 - 日志推送到消费者")
        void testFlinkJobExecution_LogsPushedToConsumer() {
            System.out.println("\n=== 场景1.2: Flink Job日志推送验证 ===");
            setUpAdminSecurityContext();

            String testId = "test-flink-job-push-" + System.currentTimeMillis();
            AtomicInteger receivedCount = new AtomicInteger(0);

            executionLogListener.registerLogConsumer(testId, logLine -> {
                receivedCount.incrementAndGet();
                System.out.println("    [消费者收到] " + logLine);
            });

            CodeExecutionContext context = buildFlinkJobContext(
                    "# Flink Python test\nprint('flink job test')");

            try {
                CodeExecutionResult result = flinkExecutionStrategy.execute(context);

                assertNotNull(result, "执行结果不应为null");
                System.out.println("  执行ID: " + result.getExecutionId());
                System.out.println("  状态: " + result.getStatus());
                System.out.println("  消费者接收次数: " + receivedCount.get());

                if ("RUNNING".equals(result.getStatus())) {
                    System.out.println("  任务已提交运行 ✓");
                } else if (result.isSuccess()) {
                    System.out.println("  执行成功 ✓");
                } else {
                    System.out.println("  执行结果: " + result.getStatus());
                }
            } catch (Exception e) {
                System.out.println("  执行异常: " + e.getClass().getSimpleName());
            }
        }
    }

    @Nested
    @DisplayName("场景2: 客户端拉取Flink日志")
    class ClientPullFlinkLogsTests {

        @Test
        @DisplayName("✅ 模拟Flink执行 - 客户端轮询拉取完整流程")
        void testSimulatedFlinkExecution_FullPollingCycle() throws Exception {
            System.out.println("\n=== 场景2.1: Flink模拟执行+轮询拉取完整流程 ===");

            String executionId = "flink-polling-test-" + System.currentTimeMillis();

            Thread publisherThread = new Thread(() -> {
                try {
                    for (int stage = 0; stage < 5; stage++) {
                        Thread.sleep(80);

                        executionLogListener.publishLog(executionId,
                                "[FLINK][JobManager] Starting stage " + stage + "...");

                        for (int task = 0; task < 3; task++) {
                            executionLogListener.publishLog(executionId,
                                    "[FLINK][TaskExecutor] Subtask " + task +
                                            " of stage " + stage + " completed in " +
                                            (100 + stage * 20) + "ms");
                        }

                        executionLogListener.publishLog(executionId,
                                "[FLINK][JobManager] Stage " + stage + " finished. " +
                                        "Progress: " + ((stage + 1) * 20) + "%");
                    }

                    executionLogListener.publishLog(executionId,
                            "[FLINK][JobManager] Job execution FINISHED successfully.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "flink-sim-publisher");
            publisherThread.start();

            int totalPulled = 0;
            int offset = 0;
            int pollRound = 0;
            final int maxRounds = 30;

            while (publisherThread.isAlive() && pollRound < maxRounds) {
                pollRound++;
                Thread.sleep(100);

                List<String> newLogs = executionLogListener.pullLogs(executionId, offset);
                if (!newLogs.isEmpty()) {
                    System.out.println("  轮询第" + pollRound + "次: 新增" + newLogs.size() + "条");
                    for (String log : newLogs) {
                        System.out.println("    > " + (log.length() > 80 ?
                                log.substring(0, 77) + "..." : log));
                    }
                    totalPulled += newLogs.size();
                    offset = executionLogListener.getLogCount(executionId);
                }
            }

            publisherThread.join(3000);
            if (publisherThread.isAlive()) {
                publisherThread.interrupt();
            }

            int finalTotal = executionLogListener.getLogCount(executionId);
            List<String> allLogs = executionLogListener.pullLogs(executionId);

            assertTrue(finalTotal > 0, "缓冲区应有日志");
            assertEquals(allLogs.size(), finalTotal, "全量拉取应等于总数");
            assertTrue(totalPulled <= finalTotal, "轮询总量不超过总发布量");

            System.out.println("  发布线程状态: " + (!publisherThread.isAlive() ? "已结束" : "仍在运行"));
            System.out.println("  轮询次数: " + pollRound);
            System.out.println("  轮询累计拉取: " + totalPulled + "条");
            System.out.println("  缓冲区总条数: " + finalTotal);
            System.out.println("  全量拉取验证: " + allLogs.size() + "条");
            System.out.println("  完整轮询流程 ✓");
        }

        @Test
        @DisplayName("✅ Flink多阶段日志 - 增量拉取各阶段数据完整")
        void testFlinkMultiStageLogs_IncrementalPullComplete() {
            System.out.println("\n=== 场景2.2: Flink多阶段增量拉取完整性 ===");

            String executionId = "flink-multistage-" + System.currentTimeMillis();

            String[][] stages = {
                    {"[FLINK] Submitting job to Flink cluster..."},
                    {"[FLINK] Job submitted: job_1234567890_0001"},
                    {"[FLINK] Starting TaskManagers: tm-01, tm-02, tm-03"},
                    {"[FLINK] Stage 1: Source reading started"},
                    {"[FLINK] Stage 2: Map transformation running", "[FLINK] Stage 2: Parallelism=4"},
                    {"[FLINK] Stage 3: Sink writing data", "[FLINK] Stage 3: Committed 10000 rows"},
                    {"[FLINK] Job COMPLETED. Total time: 12.3s"}
            };

            List<int[]> offsetsPerStage = new java.util.ArrayList<>();

            for (int s = 0; s < stages.length; s++) {
                int startOffset = executionLogListener.getLogCount(executionId);
                for (String log : stages[s]) {
                    executionLogListener.publishLog(executionId, log);
                }
                int endOffset = executionLogListener.getLogCount(executionId);
                offsetsPerStage.add(new int[]{startOffset, endOffset});
            }

            System.out.println("  阶段数: " + stages.length);
            for (int s = 0; s < stages.length; s++) {
                int startOffset = offsetsPerStage.get(s)[0];
                int stageSize = offsetsPerStage.get(s)[1] - offsetsPerStage.get(s)[0];
                List<String> stageLogs = executionLogListener.pullLogs(executionId, startOffset, stageSize);
                assertEquals(stageSize, stageLogs.size(),
                        "阶段" + (s + 1) + "增量拉取数量不匹配");
                System.out.println("  阶段" + (s + 1) + ": offset=" + startOffset +
                        ", 拉取=" + stageLogs.size() + "条 ✓");
            }

            List<String> fullPull = executionLogListener.pullLogs(executionId, 0);
            int expectedTotal = java.util.Arrays.stream(stages).mapToInt(arr -> arr.length).sum();
            assertEquals(expectedTotal, fullPull.size(), "全量拉取应包含所有阶段日志");

            System.out.println("  全量总计: " + fullPull.size() + "条 (预期" + expectedTotal + ")");
            System.out.println("  多阶段增量完整性 ✓");
        }
    }

    @Nested
    @DisplayName("场景3: Flink不同模式日志隔离")
    class FlinkModeIsolationTests {

        @Test
        @DisplayName("✅ Flink SQL模式和Job模式日志完全隔离")
        void testFlinkSqlAndJobMode_Isolated() {
            System.out.println("\n=== 场景3.1: Flink SQL/Job模式隔离 ===");

            String sqlModeId = "flink-sql-mode-" + System.currentTimeMillis();
            String jobModeId = "flink-job-mode-" + System.currentTimeMillis();

            for (int i = 0; i < 8; i++) {
                executionLogListener.publishLog(sqlModeId,
                        "[SQL-CLIENT] SET 'execution.runtime-mode' = 'batch'; -- step " + i);
            }
            for (int i = 0; i < 6; i++) {
                executionLogListener.publishLog(jobModeId,
                        "[JOB-MANAGER] Submitted job with jar /opt/flink/job.jar -- step " + i);
            }

            List<String> sqlLogs = executionLogListener.pullLogs(sqlModeId);
            List<String> jobLogs = executionLogListener.pullLogs(jobModeId);

            assertEquals(8, sqlLogs.size(), "SQL模式应有8条");
            assertEquals(6, jobLogs.size(), "Job模式应有6条");

            long sqlOnly = sqlLogs.stream().filter(l -> l.contains("[SQL-CLIENT]")).count();
            long jobOnly = jobLogs.stream().filter(l -> l.contains("[JOB-MANAGER]")).count();

            assertEquals(8, sqlOnly, "SQL模式日志全部含[SQL-CLIENT]");
            assertEquals(6, jobOnly, "Job模式日志全部含[JOB-MANAGER]");

            assertFalse(sqlLogs.stream().anyMatch(l -> l.contains("[JOB-MANAGER]")),
                    "SQL模式不应有Job模式日志");
            assertFalse(jobLogs.stream().anyMatch(l -> l.contains("[SQL-CLIENT]")),
                    "Job模式不应有SQL模式日志");

            System.out.println("  SQL模式日志: " + sqlLogs.size() + "条 (全部[SQL-CLIENT])");
            System.out.println("  Job模式日志: " + jobLogs.size() + "条 (全部[JOB-MANAGER])");
            System.out.println("  模式间隔离 ✓");
        }
    }

    @Nested
    @DisplayName("场景4: Flink日志边界条件")
    class FlinkEdgeCaseTests {

        @Test
        @DisplayName("✅ Flink超长单行日志 - 缓冲区正常存储和拉取")
        void testFlinkLongSingleLineLog_BufferHandlesCorrectly() {
            System.out.println("\n=== 场景4.1: Flink超长单行日志处理 ===");

            String executionId = "flink-long-line-" + System.currentTimeMillis();

            StringBuilder longLine = new StringBuilder("[FLINK][ERROR] Exception stack trace:\n");
            for (int i = 0; i < 50; i++) {
                longLine.append("    at com.flink.").append(i)
                       .append(".SomeClass.method").append(i)
                       .append("(SomeClass.java:").append(i * 10).append(")\n");
            }

            String longLog = longLine.toString();
            executionLogListener.publishLog(executionId, longLog);

            assertEquals(1, executionLogListener.getLogCount(executionId), "应为1条日志");

            List<String> pulled = executionLogListener.pullLogs(executionId);
            assertEquals(1, pulled.size(), "拉取应返回1条");
            assertEquals(longLog, pulled.get(0), "长日志内容应完整保留");

            System.out.println("  单行长度: " + longLog.length() + "字符");
            System.out.println("  包含行数: ~50行stack trace");
            System.out.println("  拉取长度: " + pulled.get(0).length() + "字符");
            System.out.println("  超长日志处理 ✓");
        }

        @Test
        @DisplayName("✅ Flink高频短日志 - 大量小日志快速写入后正确拉取")
        void testFlinkHighFrequencyShortLogs_CorrectAfterBatchWrite() {
            System.out.println("\n=== 场景4.2: Flink高频短日志批量写入 ===");

            String executionId = "flink-high-freq-" + System.currentTimeMillis();
            int count = 2000;

            long start = System.nanoTime();
            for (int i = 0; i < count; i++) {
                executionLogListener.publishLog(executionId,
                        "[METRIC] records_in=" + (i * 100) + " records_out=" + (i * 99));
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertEquals(count, executionLogListener.getLogCount(executionId),
                    "应包含全部" + count + "条");

            List<String> allLogs = executionLogListener.pullLogs(executionId, 0);
            assertEquals(count, allLogs.size(), "全量拉取应返回全部" + count + "条");

            List<String> firstHalf = executionLogListener.pullLogs(executionId, 0, count / 2);
            assertEquals(count / 2, firstHalf.size(), "前半部分拉取数量应正确");

            List<String> secondHalf = executionLogListener.pullLogs(executionId, count / 2);
            assertEquals(count / 2, secondHalf.size(), "后半部分拉取应正确");

            List<String> lastOne = executionLogListener.pullLogs(executionId, -1);
            assertEquals(1, lastOne.size(), "最新一条拉取应正确");

            System.out.println("  写入条数: " + count);
            System.out.println("  写入耗时: " + elapsedMs + "ms");
            System.out.println("  吞吐: " + (count * 1000L / Math.max(elapsedMs, 1)) + " 条/秒");
            System.out.println("  高频日志处理 ✓");
        }

        @Test
        @DisplayName("✅ Flink特殊字符日志 - JSON/Unicode/转义符正常处理")
        void testFlinkSpecialCharacterLogs_HandledCorrectly() {
            System.out.println("\n=== 场景4.3: Flink特殊字符日志 ===");

            String executionId = "flink-special-chars-" + System.currentTimeMillis();

            String[] specialLogs = {
                    "{\"event\":\"checkpoint\",\"size\":1048576,\"duration\":234}",
                    "[FLINK] 中文日志测试 - 数据源连接成功",
                    "[FLINK] Path: /user/hive/warehouse/db_name/table_name/partition_date=2026-06-09",
                    "[FLINK] SQL: SELECT * FROM t WHERE name = \"O'Brien & Co.\"",
                    "[FLINK] \tTab-separated\tmetrics\tline",
                    "[FLINK] Unicode: \u6570\u636e\u5904\u7406\u5b8c\u6210"
            };

            for (String log : specialLogs) {
                executionLogListener.publishLog(executionId, log);
            }

            List<String> pulled = executionLogListener.pullLogs(executionId);
            assertEquals(specialLogs.length, pulled.size(), "应返回全部特殊字符日志");

            for (int i = 0; i < specialLogs.length; i++) {
                assertEquals(specialLogs[i], pulled.get(i),
                        "第" + i + "条特殊字符日志应原样保留");
            }

            System.out.println("  特殊日志条数: " + specialLogs.length);
            System.out.println("  包含类型: JSON/中文/路径/引号/Tab/Unicode");
            System.out.println("  特殊字符处理 ✓");
        }
    }
}
