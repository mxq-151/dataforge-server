package com.bigdata.modules.telemetry.service.logdata.model;

import com.bigdata.modules.telemetry.common.model.Target;
import com.bigdata.modules.telemetry.common.model.TimeRange;

import java.time.Duration;
import java.util.Map;

/**
 * 一次日志查询的统一输入。
 *
 * <p>{@code dataType} 决定证据来源：服务日志（local-disk / ES）与任务日志（YARN）不能混用，
 * 后端在能力判定阶段必须拒绝不属于自己的分类。</p>
 *
 * <p>{@code cursor} 必须与过滤条件、时间范围、stream 绑定；条件变化后继续使用旧游标应返回
 * CURSOR_MISMATCH，而不是静默换一套结果。</p>
 */
public record LogQuerySpec(Target target,
                           LogDataType dataType,
                           String stream,
                           TimeRange timeRange,
                           LogFilter filter,
                           String cursor,
                           int limit,
                           long maxBytes,
                           Map<String, String> refs,
                           Duration timeout) {

    public LogQuerySpec {
        filter = filter == null ? LogFilter.empty() : filter;
        refs = refs == null ? Map.of() : Map.copyOf(refs);
    }
}
