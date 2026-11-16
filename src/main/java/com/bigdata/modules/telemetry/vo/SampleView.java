package com.bigdata.modules.telemetry.vo;

import java.time.Instant;

public record SampleView(Instant timestamp, double value) {
}
