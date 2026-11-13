package com.bigdata.modules.runtime.service;

import com.bigdata.modules.runtime.config.RuntimeProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 运行时快照的短 TTL 缓存；只缓存成功结果，降级结果不缓存 */
@Component
public class RuntimeCache {

    private final RuntimeProperties properties;
    private final Map<String, Entry> entries;

    public RuntimeCache(RuntimeProperties properties) {
        this.properties = properties;
        int maxEntries = Math.max(properties.getCache().getMaxEntries(), 16);
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > maxEntries;
            }
        };
    }

    public Object get(String key) {
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

    public void put(String key, Object value, long ttlMs) {
        if (!properties.getCache().isEnabled() || key == null || value == null) {
            return;
        }
        long effective = ttlMs > 0 ? ttlMs : properties.getCache().getTtlMs();
        if (effective <= 0) {
            return;
        }
        synchronized (entries) {
            entries.put(key, new Entry(value, System.currentTimeMillis() + effective));
        }
    }

    private static final class Entry {
        private final Object value;
        private final long expireAt;

        private Entry(Object value, long expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }
    }
}
