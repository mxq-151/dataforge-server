package com.bigdata.modules.logs.service;

import com.bigdata.modules.logs.config.LogAccessProperties;
import com.bigdata.modules.logs.vo.LogQueryResultVO;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 短 TTL 查询缓存：只缓存成功且未降级的结果，避免同一诊断动作重复打源服务。
 */
@Component
public class LogCache {

    private final LogAccessProperties properties;
    private final Map<String, Entry> entries;

    public LogCache(LogAccessProperties properties) {
        this.properties = properties;
        int maxEntries = Math.max(properties.getCache().getMaxEntries(), 16);
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > maxEntries;
            }
        };
    }

    public LogQueryResultVO get(String key) {
        if (!properties.getCache().isEnabled() || key == null) {
            return null;
        }
        synchronized (entries) {
            Entry entry = entries.get(key);
            if (entry == null) {
                return null;
            }
            if (entry.expireAt < System.currentTimeMillis()) {
                entries.remove(key);
                return null;
            }
            return entry.value;
        }
    }

    public void put(String key, LogQueryResultVO value) {
        if (!properties.getCache().isEnabled() || key == null || value == null || value.isDegraded()) {
            return;
        }
        long ttl = Math.max(properties.getCache().getTtlMs(), 0L);
        if (ttl <= 0) {
            return;
        }
        synchronized (entries) {
            entries.put(key, new Entry(value, System.currentTimeMillis() + ttl));
        }
    }

    private static final class Entry {
        private final LogQueryResultVO value;
        private final long expireAt;

        private Entry(LogQueryResultVO value, long expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }
    }
}
