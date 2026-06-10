package com.bigdata.modules.development.service.execution;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("执行日志监听器 - ExecutionLogListener单元测试")
class ExecutionLogListenerTest {

    @Autowired
    private ExecutionLogListener executionLogListener;

    @BeforeEach
    void setUp() {
        executionLogListener.clearBuffer("test-exec-001");
        executionLogListener.clearBuffer("test-exec-002");
        executionLogListener.clearBuffer("test-exec-003");
        executionLogListener.unregisterLogConsumer("test-exec-001");
        executionLogListener.unregisterLogConsumer("test-exec-002");
    }

    @Nested
    @DisplayName("场景1: 日志发布与消费者推送（Push模式）")
    class PushModeTests {

        @Test
        @DisplayName("✅ 注册单个消费者 - 发布日志后能正确接收")
        void testSingleConsumer_ReceivesPublishedLog() {
            System.out.println("\n=== 场景1.1: 单消费者接收日志 ===");

            String executionId = "test-exec-001";
            AtomicReference<String> receivedLog = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            executionLogListener.registerLogConsumer(executionId, logLine -> {
                receivedLog.set(logLine);
                latch.countDown();
            });

            String testLog = "[INFO] Spark任务开始执行";
            executionLogListener.publishLog(executionId, testLog);

            assertDoesNotThrow(() -> latch.await(2, TimeUnit.SECONDS), "等待消费者接收超时");
            assertEquals(testLog, receivedLog.get(), "消费者应接收到发布的日志");
            assertTrue(executionLogListener.hasConsumers(executionId), "应有活跃的消费者");

            System.out.println("  发布日志: " + testLog);
            System.out.println("  接收日志: " + receivedLog.get());
            System.out.println("  单消费者推送 ✓");
        }

        @Test
        @DisplayName("✅ 注册多个消费者 - 所有消费者都能收到同一条日志")
        void testMultipleConsumers_AllReceiveSameLog() {
            System.out.println("\n=== 场景1.2: 多消费者同时接收 ===");

            String executionId = "test-exec-001";
            int consumerCount = 5;
            AtomicInteger receiveCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(consumerCount);

            for (int i = 0; i < consumerCount; i++) {
                final int index = i;
                executionLogListener.registerLogConsumer(executionId, logLine -> {
                    System.out.println("    消费者[" + index + "] 收到: " + logLine);
                    receiveCount.incrementAndGet();
                    latch.countDown();
                });
            }

            String testLog = "[INFO] Stage 0 completed in 3.2s";
            executionLogListener.publishLog(executionId, testLog);

            assertDoesNotThrow(() -> latch.await(2, TimeUnit.SECONDS), "等待所有消费者接收超时");
            assertEquals(consumerCount, receiveCount.get(), "所有消费者都应收到日志");

            System.out.println("  消费者数: " + consumerCount);
            System.out.println("  接收次数: " + receiveCount.get());
            System.out.println("  多消费者广播 ✓");
        }

        @Test
        @DisplayName("✅ 连续发布多条日志 - 消费者按顺序全部接收")
        void testSequentialPublish_AllLogsReceivedInOrder() {
            System.out.println("\n=== 场景1.3: 连续发布多条日志 ===");

            String executionId = "test-exec-001";
            List<String> receivedLogs = Collections.synchronizedList(new ArrayList<>());
            int logCount = 100;
            CountDownLatch latch = new CountDownLatch(logCount);

            executionLogListener.registerLogConsumer(executionId, logLine -> {
                receivedLogs.add(logLine);
                latch.countDown();
            });

            for (int i = 0; i < logCount; i++) {
                executionLogListener.publishLog(executionId,
                        "[INFO] Task-" + i + " progress=" + (i * 100 / logCount) + "%");
            }

            assertDoesNotThrow(() -> latch.await(3, TimeUnit.SECONDS), "等待所有日志接收超时");
            assertEquals(logCount, receivedLogs.size(), "应接收到全部" + logCount + "条日志");

            for (int i = 0; i < logCount; i++) {
                assertTrue(receivedLogs.get(i).contains("Task-" + i),
                        "第" + i + "条日志内容不匹配");
            }

            System.out.println("  发布条数: " + logCount);
            System.out.println("  接收条数: " + receivedLogs.size());
            System.out.println("  顺序一致性 ✓");
        }
    }

    @Nested
    @DisplayName("场景2: 客户端主动拉取（Pull模式）")
    class PullModeTests {

        @Test
        @DisplayName("✅ 全量拉取 - 获取缓冲区中所有日志")
        void testPullAllLogs_ReturnsAll() {
            System.out.println("\n=== 场景2.1: 全量拉取日志 ===");

            String executionId = "test-exec-001";
            String[] logs = {
                    "[INFO] 提交Spark作业",
                    "[INFO] Driver启动中...",
                    "[INFO] Executor连接成功",
                    "[INFO] 开始执行Stage 0"
            };

            for (String log : logs) {
                executionLogListener.publishLog(executionId, log);
            }

            List<String> pulledLogs = executionLogListener.pullLogs(executionId);

            assertEquals(logs.length, pulledLogs.size(), "全量拉取应返回所有日志");
            for (int i = 0; i < logs.length; i++) {
                assertEquals(logs[i], pulledLogs.get(i), "第" + i + "条日志应匹配");
            }

            System.out.println("  缓冲区大小: " + pulledLogs.size());
            System.out.println("  日志列表:");
            for (int i = 0; i < pulledLogs.size(); i++) {
                System.out.println("    [" + i + "] " + pulledLogs.get(i));
            }
            System.out.println("  全量拉取 ✓");
        }

        @Test
        @DisplayName("✅ 增量拉取 - 从指定offset获取新日志")
        void testPullWithOffset_ReturnsOnlyNewLogs() {
            System.out.println("\n=== 场景2.2: 增量拉取日志 ===");

            String executionId = "test-exec-002";

            executionLogListener.publishLog(executionId, "[INFO] 第一批日志-1");
            executionLogListener.publishLog(executionId, "[INFO] 第一批日志-2");
            executionLogListener.publishLog(executionId, "[INFO] 第一批日志-3");

            List<String> firstPull = executionLogListener.pullLogs(executionId, 0);
            assertEquals(3, firstPull.size(), "offset=0应返回前3条");
            System.out.println("  第一次拉取(offset=0): " + firstPull.size() + "条");

            int currentOffset = executionLogListener.getLogCount(executionId);
            assertEquals(3, currentOffset, "当前日志总数应为3");

            executionLogListener.publishLog(executionId, "[INFO] 第二批日志-4");
            executionLogListener.publishLog(executionId, "[INFO] 第二批日志-5");

            List<String> incrementalPull = executionLogListener.pullLogs(executionId, currentOffset);
            assertEquals(2, incrementalPull.size(), "offset=3应只返回新增的2条");
            assertTrue(incrementalPull.get(0).contains("第二批日志-4"), "增量第一条应为新日志");
            assertTrue(incrementalPull.get(1).contains("第二批日志-5"), "增量第二条应为新日志");

            System.out.println("  当前offset: " + currentOffset);
            System.out.println("  增量拉取(offset=3): " + incrementalPull.size() + "条");
            for (String log : incrementalPull) {
                System.out.println("    " + log);
            }
            System.out.println("  增量拉取 ✓");
        }

        @Test
        @DisplayName("✅ 越界offset - 返回空列表")
        void testPullWithOutOfRangeOffset_ReturnsEmpty() {
            System.out.println("\n=== 场景2.3: offset超出范围 ===");

            String executionId = "test-exec-003";

            executionLogListener.publishLog(executionId, "[INFO] 仅一条日志");

            List<String> result = executionLogListener.pullLogs(executionId, 999);
            assertNotNull(result, "结果不应为null");
            assertTrue(result.isEmpty(), "越界offset应返回空列表");

            System.out.println("  缓冲区大小: 1");
            System.out.println("  拉取offset: 999");
            System.out.println("  返回结果: 空 ✓");
        }

        @Test
        @DisplayName("✅ offset=-1 - 只获取最新一条日志")
        void testPullLatestOnly_ReturnsLastLog() {
            System.out.println("\n=== 场景2.4: 只拉取最新日志 ===");

            String executionId = "test-exec-001";

            for (int i = 0; i < 10; i++) {
                executionLogListener.publishLog(executionId, "[INFO] 日志行-" + i);
            }

            List<String> latest = executionLogListener.pullLogs(executionId, -1);
            assertEquals(1, latest.size(), "offset=-1应只返回1条");
            assertTrue(latest.get(0).contains("日志行-9"), "应为最后一条日志");

            System.out.println("  总日志数: 10");
            System.out.println("  最新一条: " + latest.get(0));
            System.out.println("  最新单条拉取 ✓");
        }

        @Test
        @DisplayName("✅ 带limit的拉取 - 限制返回条数")
        void testPullWithLimit_RespectsLimit() {
            System.out.println("\n=== 场景2.4b: limit参数限制返回量 ===");

            String executionId = "test-exec-001";

            for (int i = 0; i < 20; i++) {
                executionLogListener.publishLog(executionId, "[LOG] item-" + i);
            }

            List<String> limited3 = executionLogListener.pullLogs(executionId, 5, 3);
            assertEquals(3, limited3.size(), "limit=3应返回3条");
            assertTrue(limited3.get(0).contains("item-5"), "应从offset=5开始");

            List<String> limitedOverflow = executionLogListener.pullLogs(executionId, 18, 5);
            assertEquals(2, limitedOverflow.size(), "limit超出剩余量时只返回实际数量");

            List<String> noLimit = executionLogListener.pullLogs(executionId, 5, -1);
            assertEquals(15, noLimit.size(), "limit=-1不限制，返回全部剩余");

            System.out.println("  总日志: 20条");
            System.out.println("  pull(5, 3): " + limited3.size() + "条");
            System.out.println("  pull(18, 5): " + limitedOverflow.size() + "条 (仅剩2条)");
            System.out.println("  pull(5, -1): " + noLimit.size() + "条 (无限制)");
            System.out.println("  limit参数 ✓");
        }

        @Test
        @DisplayName("✅ 空缓冲区拉取 - 返回空列表")
        void testPullFromEmptyBuffer_ReturnsEmpty() {
            System.out.println("\n=== 场景2.5: 空缓冲区拉取 ===");

            String nonExistentId = "exec_non_existent_" + System.currentTimeMillis();

            List<String> result = executionLogListener.pullLogs(nonExistentId);
            assertNotNull(result, "结果不应为null");
            assertTrue(result.isEmpty(), "空缓冲区应返回空列表");
            assertEquals(0, executionLogListener.getLogCount(nonExistentId), "日志计数应为0");
            assertFalse(executionLogListener.hasBuffer(nonExistentId), "hasBuffer应返回false");

            System.out.println("  不存在的executionId: " + nonExistentId);
            System.out.println("  返回结果: 空 ✓");
        }

        @Test
        @DisplayName("✅ getLogCount - 准确反映日志数量")
        void testGetLogCount_AccurateCount() {
            System.out.println("\n=== 场景2.6: 日志计数准确性 ===");

            String executionId = "test-exec-002";

            assertEquals(0, executionLogListener.getLogCount(executionId), "初始计数为0");

            for (int i = 1; i <= 50; i++) {
                executionLogListener.publishLog(executionId, "[LOG] line-" + i);
                assertEquals(i, executionLogListener.getLogCount(executionId),
                        "发布" + i + "条后计数应为" + i);
            }

            System.out.println("  最终计数: " + executionLogListener.getLogCount(executionId));
            System.out.println("  计数准确 ✓");
        }

        @Test
        @DisplayName("✅ 典型的轮询场景 - 模拟客户端定时拉取")
        void testTypicalPollingScenario_SimulatesClientPulling() throws InterruptedException {
            System.out.println("\n=== 场景2.7: 模拟客户端定时轮询拉取 ===");

            String executionId = "test-exec-polling";
            List<String> clientReceivedLogs = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger clientOffset = new AtomicInteger(0);

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                executor.submit(() -> {
                    for (int pollRound = 0; pollRound < 5; pollRound++) {
                        try {
                            Thread.sleep(50);
                            List<String> newLogs = executionLogListener.pullLogs(
                                    executionId, clientOffset.get());
                            if (!newLogs.isEmpty()) {
                                clientReceivedLogs.addAll(newLogs);
                                clientOffset.set(executionLogListener.getLogCount(executionId));
                                System.out.println("    客户端第" + (pollRound + 1) + "次轮询: 拉取" +
                                        newLogs.size() + "条, 累计" + clientReceivedLogs.size() + "条");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                });

                Thread.sleep(30);
                for (int i = 0; i < 20; i++) {
                    executionLogListener.publishLog(executionId, "[SPARK] stage-progress " + (i * 5) + "%");
                    Thread.sleep(20);
                }

                executor.shutdown();
                assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));

            } finally {
                executor.shutdownNow();
            }

            assertTrue(clientReceivedLogs.size() > 0, "客户端应通过轮询收到日志");
            assertTrue(clientReceivedLogs.size() <= 20, "不应超过总发布量");

            System.out.println("  服务端发布: 20条");
            System.out.println("  客户端拉取: " + clientReceivedLogs.size() + "条");
            System.out.println("  轮询模拟 ✓");
        }
    }

    @Nested
    @DisplayName("场景3: 消费者注册/注销生命周期")
    class ConsumerLifecycleTests {

        @Test
        @DisplayName("✅ 注销后不再接收日志")
        void testUnregister_NoLongerReceivesLogs() {
            System.out.println("\n=== 场景3.1: 注销后停止接收 ===");

            String executionId = "test-exec-001";
            AtomicInteger receiveCount = new AtomicInteger(0);

            executionLogListener.registerLogConsumer(executionId, logLine ->
                    receiveCount.incrementAndGet());

            executionLogListener.publishLog(executionId, "[INFO] 注销前的日志");
            int countBeforeUnregister = receiveCount.get();

            executionLogListener.unregisterLogConsumer(executionId);
            assertFalse(executionLogListener.hasConsumers(executionId), "注销后不应有消费者");

            executionLogListener.publishLog(executionId, "[INFO] 注销后的日志");
            int countAfterUnregister = receiveCount.get();

            assertEquals(countBeforeUnregister, countAfterUnregister,
                    "注销后接收次数不应增加");

            System.out.println("  注销前接收: " + countBeforeUnregister + "次");
            System.out.println("  注销后接收: " + countAfterUnregister + "次");
            System.out.println("  收到新日志: false ✓");
        }

        @Test
        @DisplayName("✅ hasConsumers状态检查")
        void testHasConsumers_StateCheck() {
            System.out.println("\n=== 场景3.2: hasConsumers状态 ===");

            String executionId = "test-exec-002";

            assertFalse(executionLogListener.hasConsumers(executionId),
                    "初始无消费者");

            executionLogListener.registerLogConsumer(executionId, log -> {});
            assertTrue(executionLogListener.hasConsumers(executionId),
                    "注册后有消费者");

            executionLogListener.unregisterLogConsumer(executionId);
            assertFalse(executionLogListener.hasConsumers(executionId),
                    "注销后无消费者");

            System.out.println("  初始→注册→注销 状态变化正确 ✓");
        }

        @Test
        @DisplayName("✅ hasBuffer状态检查")
        void testHasBuffer_StateCheck() {
            System.out.println("\n=== 场景3.3: hasBuffer状态 ===");

            String executionId = "test-exec-003";

            assertFalse(executionLogListener.hasBuffer(executionId), "初始无缓冲区");

            executionLogListener.publishLog(executionId, "[INFO] 测试");
            assertTrue(executionLogListener.hasBuffer(executionId), "发布后有缓冲区");

            executionLogListener.clearBuffer(executionId);
            assertFalse(executionLogListener.hasBuffer(executionId), "清理后无缓冲区");

            System.out.println("  初始→发布→清理 状态变化正确 ✓");
        }
    }

    @Nested
    @DisplayName("场景4: 异常处理与边界条件")
    class ExceptionAndEdgeCaseTests {

        @Test
        @DisplayName("✅ 消费者抛异常 - 不影响其他消费者和后续发布")
        void testConsumerException_DoesNotAffectOthers() {
            System.out.println("\n=== 场景4.1: 消费者异常隔离 ===");

            String executionId = "test-exec-001";
            AtomicInteger normalConsumerCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(1);

            executionLogListener.registerLogConsumer(executionId, logLine -> {
                throw new RuntimeException("模拟异常消费者");
            });
            executionLogListener.registerLogConsumer(executionId, logLine -> {
                normalConsumerCount.incrementAndGet();
                latch.countDown();
            });

            assertDoesNotThrow(() ->
                    executionLogListener.publishLog(executionId, "[INFO] 测试异常隔离"));

            assertDoesNotThrow(() -> latch.await(2, TimeUnit.SECONDS),
                    "正常消费者应仍能接收");
            assertEquals(1, normalConsumerCount.get(), "正常消费者应收到1次");

            System.out.println("  异常消费者: 抛出RuntimeException");
            System.out.println("  正常消费者: 成功接收" + normalConsumerCount.get() + "次");
            System.out.println("  异常隔离 ✓");
        }

        @Test
        @DisplayName("✅ 空日志字符串 - 能正常处理")
        void testEmptyLogLine_HandledGracefully() {
            System.out.println("\n=== 场景4.2: 空日志处理 ===");

            String executionId = "test-exec-002";
            AtomicReference<String> received = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            executionLogListener.registerLogConsumer(executionId, logLine -> {
                received.set(logLine);
                latch.countDown();
            });

            assertDoesNotThrow(() -> executionLogListener.publishLog(executionId, ""));
            assertDoesNotThrow(() -> executionLogListener.publishLog(executionId, null));

            System.out.println("  空字符串日志: 无异常 ✓");
            System.out.println("  null日志: 无异常 ✓");
        }

        @Test
        @DisplayName("✅ 大量日志写入 - 缓冲区正常工作")
        void testLargeVolumeLogs_BufferWorksCorrectly() {
            System.out.println("\n=== 场景4.3: 大量日志压力测试 ===");

            String executionId = "test-exec-003";
            int totalLogs = 5000;

            long start = System.currentTimeMillis();
            for (long i = 0; i < totalLogs; i++) {
                executionLogListener.publishLog(executionId,
                        "[SPARK][TaskSetManager:" + i + "] Finished task 0.0 in stage 0.0 (TID 0)");
            }
            long elapsed = System.currentTimeMillis() - start;

            assertEquals(totalLogs, executionLogListener.getLogCount(executionId),
                    "缓冲区应包含全部" + totalLogs + "条日志");

            List<String> firstBatch = executionLogListener.pullLogs(executionId, 0);
            assertEquals(totalLogs, firstBatch.size(), "全量拉取应返回全部");

            List<String> lastBatch = executionLogListener.pullLogs(executionId, totalLogs - 10);
            assertEquals(10, lastBatch.size(), "最后10条拉取应返回10条");

            System.out.println("  写入条数: " + totalLogs);
            System.out.println("  写入耗时: " + elapsed + "ms");
            System.out.println("  吞吐: " + (totalLogs * 1000L / Math.max(elapsed, 1)) + " 条/秒");
            System.out.println("  大量日志处理 ✓");
        }

        @Test
        @DisplayName("✅ 不同executionId完全隔离")
        void testDifferentExecutionIds_Isolated() {
            System.out.println("\n=== 场景4.4: 执行ID隔离性 ===");

            String idA = "exec-A";
            String idB = "exec-B";

            List<String> receivedA = Collections.synchronizedList(new ArrayList<>());
            List<String> receivedB = Collections.synchronizedList(new ArrayList<>());

            executionLogListener.registerLogConsumer(idA, receivedA::add);
            executionLogListener.registerLogConsumer(idB, receivedB::add);

            executionLogListener.publishLog(idA, "[A-only] 这是A的任务日志");
            executionLogListener.publishLog(idB, "[B-only] 这是B的任务日志");
            executionLogListener.publishLog(idA, "[A-only] A的第二条日志");

            assertEquals(2, receivedA.size(), "A的消费者应只收到A的日志");
            assertEquals(1, receivedB.size(), "B的消费者应只收到B的日志");
            assertTrue(receivedA.stream().allMatch(s -> s.contains("[A-only]")), "A的日志不含B的内容");
            assertTrue(receivedB.stream().allMatch(s -> s.contains("[B-only]")), "B的日志不含A的内容");

            assertEquals(2, executionLogListener.getLogCount(idA), "A的缓冲区有2条");
            assertEquals(1, executionLogListener.getLogCount(idB), "B的缓冲区有1条");

            System.out.println("  ID-A日志: " + receivedA.size() + "条");
            System.out.println("  ID-B日志: " + receivedB.size() + "条");
            System.out.println("  隔离性 ✓");
        }
    }

    @Nested
    @DisplayName("场景5: 并发安全性测试")
    class ConcurrencyTests {

        @Test
        @DisplayName("✅ 多线程并发发布 - 数据不丢失不重复")
        void testConcurrentPublish_NoDataLossOrDuplication() throws Exception {
            System.out.println("\n=== 场景5.1: 并发发布安全性 ===");

            String executionId = "test-concurrent-publish";
            int threadCount = 10;
            int logsPerThread = 200;
            CyclicBarrier barrier = new CyclicBarrier(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            try {
                for (int t = 0; t < threadCount; t++) {
                    final int threadId = t;
                    executor.submit(() -> {
                        try {
                            barrier.await(5, TimeUnit.SECONDS);
                            for (int i = 0; i < logsPerThread; i++) {
                                executionLogListener.publishLog(executionId,
                                        "[Thread-" + threadId + "] Log-" + i);
                            }
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                }

                executor.shutdown();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

            } finally {
                executor.shutdownNow();
            }

            int expectedTotal = threadCount * logsPerThread;
            int actualTotal = executionLogListener.getLogCount(executionId);

            assertEquals(expectedTotal, actualTotal,
                    "并发发布" + expectedTotal + "条，实际" + actualTotal + "条，不允许丢失或重复");

            List<String> allLogs = executionLogListener.pullLogs(executionId);
            assertEquals(expectedTotal, allLogs.size(), "全量拉取验证");

            System.out.println("  线程数: " + threadCount);
            System.out.println("  每线程日志: " + logsPerThread);
            System.out.println("  预期总量: " + expectedTotal);
            System.out.println("  实际总量: " + actualTotal);
            System.out.println("  并发安全 ✓");
        }

        @Test
        @DisplayName("✅ 同时读写 - 推送和拉取并发进行")
        void testConcurrentPublishAndPull_ConsistentReads() throws Exception {
            System.out.println("\n=== 场景5.2: 并发读写一致性 ===");

            String executionId = "test-concurrent-rw";
            int publisherThreads = 3;
            int pullerThreads = 3;
            int iterations = 300;

            CyclicBarrier barrier = new CyclicBarrier(publisherThreads + pullerThreads);
            ExecutorService executor = Executors.newFixedThreadPool(publisherThreads + pullerThreads);

            AtomicInteger pullSuccessCount = new AtomicInteger(0);

            try {
                for (int t = 0; t < publisherThreads; t++) {
                    final int tid = t;
                    executor.submit(() -> {
                        try {
                            barrier.await(5, TimeUnit.SECONDS);
                            for (int i = 0; i < iterations; i++) {
                                executionLogListener.publishLog(executionId,
                                        "[PUB-T" + tid + "] iteration-" + i);
                            }
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                }

                for (int t = 0; t < pullerThreads; t++) {
                    final int tid = t;
                    executor.submit(() -> {
                        try {
                            barrier.await(5, TimeUnit.SECONDS);
                            for (int i = 0; i < iterations; i++) {
                                List<String> logs = executionLogListener.pullLogs(executionId, 0);
                                if (!logs.isEmpty()) {
                                    pullSuccessCount.incrementAndGet();
                                }
                                Thread.sleep(1);
                            }
                        } catch (Exception e) {
                            // ignore
                        }
                    });
                }

                executor.shutdown();
                assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));

            } finally {
                executor.shutdownNow();
            }

            int expectedLogs = publisherThreads * iterations;
            assertEquals(expectedLogs, executionLogListener.getLogCount(executionId),
                    "最终日志数量应等于总发布量");
            assertTrue(pullSuccessCount.get() > 0, "至少应有一次成功的拉取");

            System.out.println("  发布线程: " + publisherThreads);
            System.out.println("  拉取线程: " + pullerThreads);
            System.out.println("  最终日志数: " + executionLogListener.getLogCount(executionId));
            System.out.println("  成功拉取次数: " + pullSuccessCount.get());
            System.out.println("  并发读写一致 ✓");
        }
    }

    @Nested
    @DisplayName("场景6: 缓冲区清理功能")
    class BufferCleanupTests {

        @Test
        @DisplayName("✅ clearBuffer - 清理单个缓冲区")
        void testClearBuffer_RemovesAllLogs() {
            System.out.println("\n=== 场景6.1: 清理单个缓冲区 ===");

            String executionId = "test-cleanup-001";

            for (int i = 0; i < 10; i++) {
                executionLogListener.publishLog(executionId, "[LOG] item-" + i);
            }

            assertEquals(10, executionLogListener.getLogCount(executionId), "清理前应有10条");

            executionLogListener.clearBuffer(executionId);

            assertEquals(0, executionLogListener.getLogCount(executionId), "清理后应为0");
            assertFalse(executionLogListener.hasBuffer(executionId), "hasBuffer应返回false");
            assertTrue(executionLogListener.pullLogs(executionId).isEmpty(), "拉取应返回空");

            System.out.println("  清理前: 10条");
            System.out.println("  清理后: 0条 ✓");
        }

        @Test
        @DisplayName("✅ cleanupCompletedBuffers - 批量清理非活跃任务")
        void testCleanupCompletedBuffers_BulkCleanup() {
            System.out.println("\n=== 场景6.2: 批量清理已完成任务 ===");

            String activeId = "active-task-001";
            String finishedId1 = "finished-task-001";
            String finishedId2 = "finished-task-002";

            executionLogListener.publishLog(activeId, "[ACTIVE] 仍在运行");
            executionLogListener.publishLog(finishedId1, "[FINISHED] 已完成1");
            executionLogListener.publishLog(finishedId2, "[FINISHED] 已完成2");

            List<String> activeIds = List.of(activeId);
            executionLogListener.cleanupCompletedBuffers(activeIds);

            assertTrue(executionLogListener.hasBuffer(activeId), "活跃任务的缓冲区应保留");
            assertFalse(executionLogListener.hasBuffer(finishedId1), "已完成任务1应被清理");
            assertFalse(executionLogListener.hasBuffer(finishedId2), "已完成任务2应被清理");

            System.out.println("  活跃任务: 保留 ✓");
            System.out.println("  已完成任务: 已清理 ✓");
        }
    }
}
