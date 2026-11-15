package com.bigdata.modules.telemetry.v1.runtime.support;

import com.bigdata.modules.telemetry.v1.runtime.config.RuntimeV1Properties;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * compare 用的进程内环形缓冲。
 *
 * <p><b>两条限制</b>：不持久化，进程重启历史即丢；多实例部署时各自独立，跨实例不可比。
 * 容量由 {@code telemetry.v1.runtime.history-capacity} 配置，默认 10。</p>
 */
@Component
public class SnapshotHistory {

    /** 一份快照的扁平指标点，key = component|family|name。 */
    public record Point(String component, String componentType, String family, String name,
                        String unit, double value) {
    }

    public record Entry(String jobId, long capturedAt, Map<String, Point> points) {
    }

    private final Map<String, Deque<Entry>> entries = new ConcurrentHashMap<>();
    private final int capacity;

    public SnapshotHistory(RuntimeV1Properties properties) {
        this.capacity = Math.max(1, properties.getHistoryCapacity());
    }

    public int capacity() {
        return capacity;
    }

    public void record(String key, Entry entry) {
        Deque<Entry> deque = entries.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(entry);
            while (deque.size() > capacity) {
                deque.removeFirst();
            }
        }
    }

    public Optional<Entry> latest(String key) {
        Deque<Entry> deque = entries.get(key);
        if (deque == null) {
            return Optional.empty();
        }
        synchronized (deque) {
            return Optional.ofNullable(deque.peekLast());
        }
    }

    /** 同一 target（不含 jobId）下的全部历史，用于区分「作业不同」与「样本不足」。 */
    public List<Entry> entriesWithPrefix(String prefix) {
        List<Entry> result = new ArrayList<>();
        entries.forEach((key, deque) -> {
            if (!key.startsWith(prefix)) {
                return;
            }
            synchronized (deque) {
                result.addAll(deque);
            }
        });
        return result;
    }

    public void clear() {
        entries.clear();
    }
}
