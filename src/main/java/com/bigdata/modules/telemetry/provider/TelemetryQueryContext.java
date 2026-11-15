package com.bigdata.modules.telemetry.provider;

import com.bigdata.modules.logs.dto.LogQueryDTO;
import com.bigdata.modules.telemetry.model.TelemetryIdentity;

import java.util.Map;

/** 取数上下文：身份 + 业务引用 + 分页/窗口参数。 */
public record TelemetryQueryContext(TelemetryIdentity identity,
                                    Map<String, String> ref,
                                    LogQueryDTO.TimeRange timeRange,
                                    String keyword,
                                    String cursor,
                                    Integer limit,
                                    Integer maxBytes,
                                    Map<String, Object> params) {
}
