package com.bigdata.modules.telemetry.v1.spi;

import com.bigdata.modules.telemetry.v1.model.Target;

import java.time.Duration;

/**
 * 一次引擎结构化异常摘要查询的输入。
 *
 * <p>异常是结构化证据，不进入 {@code stdout/stderr} 日志流：塞进 {@code LogEntry.message}
 * 会把 taskName / 并发异常 / 截断标记拍平成一行文本。</p>
 *
 * <p>只对应列表接口 {@code GET /jobs/{jobId}/exceptions}。Flink 2.2 没有
 * {@code /jobs/{jobId}/exceptions/{exception}} 子资源（实测 404），因此本输入不包含单条详情选择，
 * 也不保留必然 404 的调用路径。</p>
 *
 * <p>条数覆盖值为 {@code null} 时由后端按配置上限取默认值，最终仍被配置上限夹住。</p>
 */
public record ExceptionQuerySpec(Target target,
                                 String jobId,
                                 Integer maxExceptions,
                                 Integer maxConcurrentExceptions,
                                 Boolean includeStackTraces,
                                 Duration timeout) {

    public ExceptionQuerySpec {
        jobId = jobId == null ? null : jobId.trim();
    }
}
