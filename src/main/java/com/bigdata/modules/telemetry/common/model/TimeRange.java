package com.bigdata.modules.telemetry.common.model;

import java.time.Instant;

/** 半开/闭区间统一用显式起止时间，避免调用方自己拼字符串。 */
public record TimeRange(Instant start, Instant end) {

    public boolean contains(Instant value) {
        if (value == null) {
            return false;
        }
        if (start != null && value.isBefore(start)) {
            return false;
        }
        return end == null || !value.isAfter(end);
    }
}
