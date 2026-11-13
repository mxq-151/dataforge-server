package com.bigdata.modules.logs.service;

import com.bigdata.modules.logs.config.LogAccessProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按日志源隔离的线程池：一个源变慢或卡住，不会拖垮其他源的查询。
 */
@Component
public class LogExecutorRegistry implements DisposableBean {

    private final LogAccessProperties properties;
    private final Map<String, ThreadPoolExecutor> pools = new ConcurrentHashMap<>();

    public LogExecutorRegistry(LogAccessProperties properties) {
        this.properties = properties;
    }

    public ThreadPoolExecutor get(String sourceId) {
        String key = sourceId == null || sourceId.isBlank() ? "default" : sourceId;
        return pools.computeIfAbsent(key, id -> createPool(id));
    }

    private ThreadPoolExecutor createPool(String sourceId) {
        LogAccessProperties.Executor config = properties.getExecutor();
        AtomicInteger counter = new AtomicInteger();
        return new ThreadPoolExecutor(
                Math.max(config.getCorePoolSize(), 1),
                Math.max(config.getMaxPoolSize(), Math.max(config.getCorePoolSize(), 1)),
                Math.max(config.getKeepAliveSeconds(), 1L),
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(Math.max(config.getQueueCapacity(), 1)),
                runnable -> {
                    Thread thread = new Thread(runnable, "log-" + sourceId + "-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void destroy() {
        pools.values().forEach(ThreadPoolExecutor::shutdownNow);
        pools.clear();
    }
}
