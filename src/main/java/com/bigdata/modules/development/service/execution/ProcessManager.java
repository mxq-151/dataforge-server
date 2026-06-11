package com.bigdata.modules.development.service.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ProcessManager {

    private static final Logger log = LoggerFactory.getLogger(ProcessManager.class);

    private final ConcurrentHashMap<String, ManagedProcess> processMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ManagedCancellable> cancellableMap = new ConcurrentHashMap<>();

    public String registerProcess(Process process, Long userId, String engineType) {
        String executionId = generateExecutionId();
        ManagedProcess mp = new ManagedProcess(executionId, process, null, userId, engineType);
        processMap.put(executionId, mp);
        log.info("注册进程: executionId={}, userId={}, engine={}", executionId, userId, engineType);
        return executionId;
    }

    public String registerStatement(Statement statement, Long userId, String engineType) {
        String executionId = generateExecutionId();
        ManagedProcess mp = new ManagedProcess(executionId, null, statement, userId, engineType);
        processMap.put(executionId, mp);
        log.info("注册Statement: executionId={}, userId={}, engine={}", executionId, userId, engineType);
        return executionId;
    }

    /**
     * 注册可取消的任务（用于非Process/Statement类型的资源，如REST API调用）
     * @param cancelTask 取消时执行的回调
     * @param userId 用户ID
     * @param engineType 引擎类型
     * @return 注册ID
     */
    public String registerCancellable(Runnable cancelTask, Long userId, String engineType) {
        String executionId = generateExecutionId();
        ManagedCancellable mc = new ManagedCancellable(executionId, cancelTask, userId, engineType);
        cancellableMap.put(executionId, mc);
        log.info("注册可取消任务: executionId={}, userId={}, engine={}", executionId, userId, engineType);
        return executionId;
    }

    public boolean cancel(String executionId, Long userId) {
        // 先检查可取消任务
        ManagedCancellable mc = cancellableMap.get(executionId);
        if (mc != null) {
            if (userId != null && !userId.equals(mc.getUserId())) {
                log.warn("用户无权取消此任务: executionId={}, requestUserId={}, ownerUserId={}",
                        executionId, userId, mc.getUserId());
                return false;
            }
            try {
                mc.getCancelTask().run();
                log.info("已执行取消回调: executionId={}", executionId);
                cancellableMap.remove(executionId);
                return true;
            } catch (Exception e) {
                log.error("取消任务异常: executionId={}", executionId, e);
                return false;
            }
        }

        // 再检查进程/Statement
        ManagedProcess mp = processMap.get(executionId);
        if (mp == null) {
            log.warn("未找到执行任务: executionId={}", executionId);
            return false;
        }

        if (userId != null && !userId.equals(mp.getUserId())) {
            log.warn("用户无权取消此任务: executionId={}, requestUserId={}, ownerUserId={}",
                    executionId, userId, mp.getUserId());
            return false;
        }

        try {
            if (mp.getProcess() != null) {
                Process process = mp.getProcess();
                if (process.isAlive()) {
                    process.destroyForcibly();
                    log.info("已强制终止进程: executionId={}", executionId);
                }
            }

            if (mp.getStatement() != null) {
                try {
                    mp.getStatement().cancel();
                    log.info("已取消SQL执行: executionId={}", executionId);
                } catch (Exception e) {
                    log.warn("取消SQL执行失败: executionId={}, error={}", executionId, e.getMessage());
                }
            }

            processMap.remove(executionId);
            return true;
        } catch (Exception e) {
            log.error("取消任务异常: executionId={}", executionId, e);
            return false;
        }
    }

    public void unregister(String executionId) {
        ManagedProcess removed = processMap.remove(executionId);
        ManagedCancellable removedC = cancellableMap.remove(executionId);
        if (removed != null || removedC != null) {
            log.debug("注销任务: executionId={}", executionId);
        }
    }

    public boolean isRunning(String executionId) {
        ManagedProcess mp = processMap.get(executionId);
        if (mp == null) {
            return false;
        }
        if (mp.getProcess() != null) {
            return mp.getProcess().isAlive();
        }
        return false;
    }

    public List<Map<String, Object>> getRunningProcesses(Long userId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, ManagedProcess> entry : processMap.entrySet()) {
            ManagedProcess mp = entry.getValue();
            if (userId != null && !userId.equals(mp.getUserId())) {
                continue;
            }
            result.add(Map.of(
                    "executionId", mp.getExecutionId(),
                    "engineType", mp.getEngineType() != null ? mp.getEngineType() : "unknown",
                    "userId", mp.getUserId() != null ? mp.getUserId() : 0L
            ));
        }
        return result;
    }

    public void cleanup() {
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, ManagedProcess> entry : processMap.entrySet()) {
            ManagedProcess mp = entry.getValue();
            if (mp.getProcess() != null && !mp.getProcess().isAlive()) {
                toRemove.add(entry.getKey());
            }
        }
        for (String id : toRemove) {
            processMap.remove(id);
        }
        if (!toRemove.isEmpty()) {
            log.info("清理已完成进程: {}个", toRemove.size());
        }
    }

    private String generateExecutionId() {
        return "EXEC_" + System.currentTimeMillis() + "_" +
                Integer.toHexString((int) (Math.random() * 0xFFFF));
    }

    private static class ManagedProcess {
        private final String executionId;
        private final Process process;
        private final Statement statement;
        private final Long userId;
        private final String engineType;

        public ManagedProcess(String executionId, Process process, Statement statement, Long userId, String engineType) {
            this.executionId = executionId;
            this.process = process;
            this.statement = statement;
            this.userId = userId;
            this.engineType = engineType;
        }

        public String getExecutionId() { return executionId; }
        public Process getProcess() { return process; }
        public Statement getStatement() { return statement; }
        public Long getUserId() { return userId; }
        public String getEngineType() { return engineType; }
    }

    private static class ManagedCancellable {
        private final String executionId;
        private final Runnable cancelTask;
        private final Long userId;
        private final String engineType;

        public ManagedCancellable(String executionId, Runnable cancelTask, Long userId, String engineType) {
            this.executionId = executionId;
            this.cancelTask = cancelTask;
            this.userId = userId;
            this.engineType = engineType;
        }

        public String getExecutionId() { return executionId; }
        public Runnable getCancelTask() { return cancelTask; }
        public Long getUserId() { return userId; }
        public String getEngineType() { return engineType; }
    }
}
