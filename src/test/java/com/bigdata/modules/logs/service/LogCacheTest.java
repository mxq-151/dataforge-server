package com.bigdata.modules.logs.service;

import com.bigdata.modules.logs.config.LogAccessProperties;
import com.bigdata.modules.logs.vo.LogQueryResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 日志查询缓存的准入规则：只缓存成功、未降级、TTL 未过期的结果。
 * 这些规则决定「同一个诊断动作会不会重复打 DS/YARN」，也是降级结果不被放大的保险。
 */
@DisplayName("Logs模块 - LogCache TTL 与准入规则")
class LogCacheTest {

    private LogAccessProperties properties;
    private LogCache cache;

    @BeforeEach
    void setUp() {
        properties = new LogAccessProperties();
        cache = new LogCache(properties);
    }

    @Test
    @DisplayName("写入后可命中，且返回同一对象")
    void returnsCachedValue() {
        LogQueryResultVO value = mock(LogQueryResultVO.class);
        when(value.isDegraded()).thenReturn(false);

        cache.put("ds|runtime|0", value);

        assertSame(value, cache.get("ds|runtime|0"));
    }

    @Test
    @DisplayName("降级结果不进入缓存，避免把失败放大到 TTL 窗口")
    void degradedResultIsNotCached() {
        LogQueryResultVO degraded = mock(LogQueryResultVO.class);
        when(degraded.isDegraded()).thenReturn(true);

        cache.put("key", degraded);

        assertNull(cache.get("key"));
    }

    @Test
    @DisplayName("总开关关闭时读写都失效")
    void disabledCacheIgnoresWrites() {
        properties.getCache().setEnabled(false);

        LogQueryResultVO value = mock(LogQueryResultVO.class);
        cache.put("key", value);

        assertNull(cache.get("key"));
    }

    @Test
    @DisplayName("TTL 过期后不再命中")
    void expiresAfterTtl() throws Exception {
        properties.getCache().setTtlMs(1L);
        LogQueryResultVO value = mock(LogQueryResultVO.class);
        cache.put("key", value);
        assertNotNull(cache.get("key"), "刚写入应命中");

        Thread.sleep(20L);

        assertNull(cache.get("key"), "超过 TTL 后必须失效");
    }

    @Test
    @DisplayName("ttl<=0 视为不缓存")
    void nonPositiveTtlDisablesCaching() {
        properties.getCache().setTtlMs(0L);
        cache.put("key", mock(LogQueryResultVO.class));

        assertNull(cache.get("key"));
    }

    @Test
    @DisplayName("key 为 null 时安全返回 null")
    void nullKeyIsIgnored() {
        cache.put(null, mock(LogQueryResultVO.class));

        assertNull(cache.get(null));
    }

    @Test
    @DisplayName("超过容量后淘汰最久未使用的条目")
    void evictsEldestBeyondCapacity() {
        // 容量在构造时读取一次（Spring 单例语义），必须在构造前设置
        properties.getCache().setMaxEntries(16);
        LogCache bounded = new LogCache(properties);
        LogQueryResultVO value = mock(LogQueryResultVO.class);
        for (int i = 0; i < 17; i++) {
            bounded.put("k" + i, value);
        }

        assertNull(bounded.get("k0"), "最早写入的条目应被淘汰");
        assertSame(value, bounded.get("k16"));
    }
}
