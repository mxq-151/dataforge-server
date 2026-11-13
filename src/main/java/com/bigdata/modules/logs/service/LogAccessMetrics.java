package com.bigdata.modules.logs.service;

import com.bigdata.modules.logs.config.LogAccessProperties;
import com.bigdata.modules.logs.model.LogKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 日志访问指标：总量、失败、降级、字节数与慢查询。
 *
 * <p>慢查询通过 WARN 日志暴露（含用户、源、种类、耗时、字节数），
 * 不引入额外监控依赖；后续接入 Micrometer 时只需在此处埋点。</p>
 */
@Component
public class LogAccessMetrics {

    private static final Logger log = LoggerFactory.getLogger(LogAccessMetrics.class);

    private final LogAccessProperties properties;
    private final AtomicLong total = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong degraded = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();

    public LogAccessMetrics(LogAccessProperties properties) {
        this.properties = properties;
    }

    public void record(String sourceId, LogKind kind, long elapsedMs, int byteSize,
                       boolean isDegraded, boolean isFailure, String user) {
        total.incrementAndGet();
        bytes.addAndGet(Math.max(byteSize, 0));
        if (isDegraded) {
            degraded.incrementAndGet();
        }
        if (isFailure) {
            failures.incrementAndGet();
        }
        if (elapsedMs >= properties.getObservability().getSlowQueryMs()) {
            log.warn("日志慢查询: user={}, source={}, kind={}, elapsed={}ms, bytes={}, degraded={}",
                    user, sourceId, kind == null ? "-" : kind.wireName(), elapsedMs, byteSize, isDegraded);
        } else if (log.isDebugEnabled()) {
            log.debug("日志查询: user={}, source={}, kind={}, elapsed={}ms, bytes={}, degraded={}",
                    user, sourceId, kind == null ? "-" : kind.wireName(), elapsedMs, byteSize, isDegraded);
        }
    }

    public long getTotal() {
        return total.get();
    }

    public long getFailures() {
        return failures.get();
    }

    public long getDegraded() {
        return degraded.get();
    }

    public long getBytes() {
        return bytes.get();
    }
}
