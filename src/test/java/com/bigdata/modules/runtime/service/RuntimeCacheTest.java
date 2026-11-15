package com.bigdata.modules.runtime.service;

import com.bigdata.modules.runtime.config.RuntimeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 运行时指标缓存：快照类数据必须按短 TTL 失效，否则看板会展示过期指标。
 */
@DisplayName("Runtime模块 - RuntimeCache TTL 与容量")
class RuntimeCacheTest {

    private RuntimeProperties properties;
    private RuntimeCache cache;

    @BeforeEach
    void setUp() {
        properties = new RuntimeProperties();
        cache = new RuntimeCache(properties);
    }

    @Test
    @DisplayName("写入后可命中")
    void returnsCachedValue() {
        cache.put("snapshot|flink|1", "payload", 5000L);

        assertEquals("payload", cache.get("snapshot|flink|1"));
    }

    @Test
    @DisplayName("按条目 TTL 过期")
    void expiresPerEntryTtl() throws Exception {
        cache.put("key", "v", 1L);
        Thread.sleep(20L);

        assertNull(cache.get("key"));
    }

    @Test
    @DisplayName("条目 TTL<=0 时回落到全局 TTL")
    void fallsBackToGlobalTtl() {
        properties.getCache().setTtlMs(60000L);

        cache.put("key", "v", 0L);

        assertSame("v", cache.get("key"));
    }

    @Test
    @DisplayName("全局开关关闭时不缓存")
    void disabledCacheStoresNothing() {
        properties.getCache().setEnabled(false);

        cache.put("key", "v", 1000L);

        assertNull(cache.get("key"));
    }

    @Test
    @DisplayName("容量上限生效，超限淘汰最早条目")
    void evictsBeyondCapacity() {
        // 容量在构造时读取一次，必须在构造前设置
        properties.getCache().setMaxEntries(16);
        RuntimeCache bounded = new RuntimeCache(properties);
        for (int i = 0; i < 17; i++) {
            bounded.put("k" + i, "v" + i, 60000L);
        }

        assertNull(bounded.get("k0"));
        assertEquals("v16", bounded.get("k16"));
    }
}
