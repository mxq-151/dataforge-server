package com.bigdata.modules.development.service.execution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务取消/注册表的权限与生命周期。
 *
 * <p>取消是「谁提交谁能停」的权限边界：非属主必须被拒绝，未知 id 必须返回 false；
 * 注册表不能在任务结束后继续持有 Process，否则长跑服务会内存泄漏。</p>
 */
@DisplayName("Development模块 - ProcessManager 取消与注册表")
class ProcessManagerTest {

    private ProcessManager processManager;

    @BeforeEach
    void setUp() {
        processManager = new ProcessManager();
    }

    @Test
    @DisplayName("属主取消运行中的进程：强杀成功并移出注册表")
    void ownerCancelsRunningProcess() {
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(true);

        processManager.registerProcess("EXEC-1", process, 100L, "spark");
        boolean cancelled = processManager.cancel("EXEC-1", 100L);

        assertTrue(cancelled);
        verify(process).destroyForcibly();
        assertFalse(processManager.isRunning("EXEC-1"));
    }

    @Test
    @DisplayName("非属主取消：拒绝，且不触碰进程")
    void nonOwnerCannotCancel() {
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(true);

        processManager.registerProcess("EXEC-2", process, 100L, "spark");

        assertFalse(processManager.cancel("EXEC-2", 999L));
        verify(process, never()).destroyForcibly();
        assertTrue(processManager.isRunning("EXEC-2"), "被拒绝后任务应仍在运行");
    }

    @Test
    @DisplayName("取消不存在的 executionId：返回 false 而不是抛异常")
    void cancelUnknownExecutionReturnsFalse() {
        assertFalse(processManager.cancel("NOT-EXIST", 1L));
    }

    @Test
    @DisplayName("可取消任务（REST 调用等）：执行注册的回调")
    void cancelsRegisteredCallback() {
        AtomicBoolean invoked = new AtomicBoolean(false);
        processManager.registerCancellable("EXEC-3", () -> invoked.set(true), 100L, "kyuubi");

        assertTrue(processManager.cancel("EXEC-3", 100L));
        assertTrue(invoked.get());
        assertFalse(processManager.cancel("EXEC-3", 100L), "已取消的任务不应再次取消成功");
    }

    @Test
    @DisplayName("JDBC Statement：用注册返回的 id 可以取消")
    void cancelsRegisteredStatement() throws Exception {
        Statement statement = mock(Statement.class);
        String id = processManager.registerStatement(statement, 100L, "mysql");

        assertTrue(processManager.cancel(id, 100L));
        verify(statement).cancel();
    }

    @Test
    @DisplayName("Statement 注册使用独立 id，与执行上下文 id 不一致（取消入口按 executionId 查找会 miss）")
    void statementRegistrationIdIsNotCallerExecutionId() {
        Statement statement = mock(Statement.class);
        String id = processManager.registerStatement(statement, 100L, "mysql");

        assertNotEquals("EXEC-CONTEXT-ID", id,
                "registerStatement 自行生成 id，调用方无法用 context.executionId 取消");
        assertFalse(processManager.cancel("EXEC-CONTEXT-ID", 100L));
    }

    @Test
    @DisplayName("cleanup 只清理已结束的进程")
    void cleanupRemovesOnlyDeadProcesses() {
        Process alive = mock(Process.class);
        when(alive.isAlive()).thenReturn(true);
        Process dead = mock(Process.class);
        when(dead.isAlive()).thenReturn(false);
        processManager.registerProcess("EXEC-4", alive, 1L, "flink");
        processManager.registerProcess("EXEC-5", dead, 1L, "flink");

        processManager.cleanup();

        assertTrue(processManager.isRunning("EXEC-4"));
        assertFalse(processManager.isRunning("EXEC-5"));
    }

    @Test
    @DisplayName("运行中列表按用户隔离")
    void runningProcessesAreFilteredByUser() {
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(true);
        processManager.registerProcess("EXEC-6", process, 1L, "spark");

        List<Map<String, Object>> mine = processManager.getRunningProcesses(1L);
        List<Map<String, Object>> others = processManager.getRunningProcesses(2L);

        assertEquals(1, mine.size());
        assertEquals("EXEC-6", mine.get(0).get("executionId"));
        assertTrue(others.isEmpty());
    }
}
