package com.bigdata.modules.logs.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按日志源的简单熔断器。
 *
 * <p>连续失败达到阈值后打开一段时间，期间直接返回降级结果，避免慢源/挂源把线程池占满；
 * 成功一次即复位。状态只在内存中，重启后自动清零。</p>
 */
@Component
public class LogCircuitBreaker {

    private final Map<String, State> states = new ConcurrentHashMap<>();

    public boolean isOpen(String sourceId) {
        State state = states.get(sourceId);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            if (state.openUntil <= System.currentTimeMillis()) {
                state.openUntil = 0L;
                return false;
            }
            return true;
        }
    }

    public long retryAfterMs(String sourceId) {
        State state = states.get(sourceId);
        if (state == null) {
            return 0L;
        }
        return Math.max(0L, state.openUntil - System.currentTimeMillis());
    }

    public void recordSuccess(String sourceId) {
        State state = states.get(sourceId);
        if (state != null) {
            synchronized (state) {
                state.failures = 0;
                state.openUntil = 0L;
            }
        }
    }

    public void recordFailure(String sourceId, int threshold, long openMs) {
        State state = states.computeIfAbsent(sourceId, key -> new State());
        synchronized (state) {
            state.failures++;
            if (state.failures >= Math.max(threshold, 1)) {
                state.openUntil = System.currentTimeMillis() + Math.max(openMs, 1000L);
                state.failures = 0;
            }
        }
    }

    private static final class State {
        private int failures;
        private long openUntil;
    }
}
