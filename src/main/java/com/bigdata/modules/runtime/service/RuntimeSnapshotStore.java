package com.bigdata.modules.runtime.service;

import com.bigdata.modules.runtime.vo.RuntimeSnapshotVO;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内快照历史：用于「时间点对比」。
 *
 * <p>Flink / Spark REST 本身不保存指标历史，因此对比只能基于本服务观察到的快照；
 * 每个作业最多保留 10 份，重启后清空。这样不需要外部时序库，也不承诺跨实例一致性。</p>
 */
@Component
public class RuntimeSnapshotStore {

    private static final int MAX_PER_JOB = 10;

    private final Map<String, Deque<RuntimeSnapshotVO>> snapshots = new ConcurrentHashMap<>();

    public void record(String key, RuntimeSnapshotVO snapshot) {
        if (key == null || snapshot == null || snapshot.isDegraded()) {
            return;
        }
        Deque<RuntimeSnapshotVO> deque = snapshots.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(snapshot);
            while (deque.size() > MAX_PER_JOB) {
                deque.removeFirst();
            }
        }
    }

    /**
     * 找一份「至少 windowSeconds 之前」的快照；没有则退回最早的一份（仍需早于当前 1 秒）。
     */
    public RuntimeSnapshotVO findPrior(String key, long windowSeconds) {
        if (key == null) {
            return null;
        }
        Deque<RuntimeSnapshotVO> deque = snapshots.get(key);
        if (deque == null) {
            return null;
        }
        long threshold = System.currentTimeMillis() - Math.max(windowSeconds, 1) * 1000L;
        synchronized (deque) {
            RuntimeSnapshotVO fallback = null;
            RuntimeSnapshotVO candidate = null;
            List<RuntimeSnapshotVO> values = List.copyOf(deque);
            for (RuntimeSnapshotVO snapshot : values) {
                Long capturedAt = snapshot.getCapturedAt();
                if (capturedAt == null || capturedAt >= System.currentTimeMillis() - 1000L) {
                    continue;
                }
                if (fallback == null) {
                    fallback = snapshot;
                }
                if (capturedAt <= threshold) {
                    candidate = snapshot;
                }
            }
            return candidate != null ? candidate : fallback;
        }
    }
}
