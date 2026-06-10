package com.bigdata.modules.development.service.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class ExecutionLogListener {

    private static final Logger log = LoggerFactory.getLogger(ExecutionLogListener.class);

    private static final int MAX_LOG_BUFFER_SIZE = 10000;

    private final ConcurrentHashMap<String, List<Consumer<String>>> logConsumers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> logBuffers = new ConcurrentHashMap<>();

    public void registerLogConsumer(String executionId, Consumer<String> consumer) {
        logConsumers.computeIfAbsent(executionId, k -> new CopyOnWriteArrayList<>()).add(consumer);
        log.debug("注册日志消费者: executionId={}", executionId);
    }

    public void unregisterLogConsumer(String executionId) {
        logConsumers.remove(executionId);
        log.debug("注销日志消费者: executionId={}", executionId);
    }

    public void publishLog(String executionId, String logLine) {
        appendToBuffer(executionId, logLine);

        List<Consumer<String>> consumers = logConsumers.get(executionId);
        if (consumers != null && !consumers.isEmpty()) {
            for (Consumer<String> consumer : consumers) {
                try {
                    consumer.accept(logLine);
                } catch (Exception e) {
                    log.warn("推送日志到消费者失败: executionId={}", executionId, e);
                }
            }
        }
    }

    private void appendToBuffer(String executionId, String logLine) {
        List<String> buffer = logBuffers.computeIfAbsent(executionId, k ->
                Collections.synchronizedList(new ArrayList<>()));
        synchronized (buffer) {
            if (buffer.size() >= MAX_LOG_BUFFER_SIZE) {
                buffer.remove(0);
            }
            buffer.add(logLine);
        }
    }

    /**
     * 客户端主动拉取日志（全量）
     */
    public List<String> pullLogs(String executionId) {
        return pullLogs(executionId, 0);
    }

    /**
     * 客户端增量拉取日志（从指定偏移量开始）
     * @param offset 起始位置（0=从头开始，-1=只获取最新一条）
     */
    public List<String> pullLogs(String executionId, int offset) {
        return pullLogs(executionId, offset, -1);
    }

    /**
     * 客户端增量拉取日志（支持limit限制返回条数）
     * @param offset 起始位置（0=从头开始，-1=只获取最新一条）
     * @param limit 最大返回条数（-1=不限制）
     */
    public List<String> pullLogs(String executionId, int offset, int limit) {
        List<String> buffer = logBuffers.get(executionId);
        if (buffer == null || buffer.isEmpty()) {
            return Collections.emptyList();
        }
        synchronized (buffer) {
            if (offset < 0) {
                return Collections.singletonList(buffer.get(buffer.size() - 1));
            }
            if (offset >= buffer.size()) {
                return Collections.emptyList();
            }
            int endIndex = (limit > 0) ? Math.min(offset + limit, buffer.size()) : buffer.size();
            return new ArrayList<>(buffer.subList(offset, endIndex));
        }
    }

    /**
     * 获取指定executionId的日志总行数（用于计算下次拉取的offset）
     */
    public int getLogCount(String executionId) {
        List<String> buffer = logBuffers.get(executionId);
        if (buffer == null) {
            return 0;
        }
        synchronized (buffer) {
            return buffer.size();
        }
    }

    /**
     * 清理指定executionId的缓存
     */
    public void clearBuffer(String executionId) {
        List<String> removed = logBuffers.remove(executionId);
        if (removed != null) {
            log.info("清理日志缓冲区: executionId={}, 清除{}条日志", executionId, removed.size());
        }
    }

    /**
     * 清理所有已完成的日志缓冲区（配合ProcessManager.cleanup使用）
     */
    public void cleanupCompletedBuffers(List<String> activeExecutionIds) {
        List<String> toRemove = new ArrayList<>();
        for (String id : logBuffers.keySet()) {
            if (!activeExecutionIds.contains(id)) {
                toRemove.add(id);
            }
        }
        for (String id : toRemove) {
            clearBuffer(id);
        }
        if (!toRemove.isEmpty()) {
            log.debug("批量清理已完成任务的日志缓冲: {}个", toRemove.size());
        }
    }

    public boolean hasConsumers(String executionId) {
        List<Consumer<String>> consumers = logConsumers.get(executionId);
        return consumers != null && !consumers.isEmpty();
    }

    public boolean hasBuffer(String executionId) {
        List<String> buffer = logBuffers.get(executionId);
        return buffer != null && !buffer.isEmpty();
    }
}
