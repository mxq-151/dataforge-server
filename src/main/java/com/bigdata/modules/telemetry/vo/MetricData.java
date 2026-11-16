package com.bigdata.modules.telemetry.vo;

import java.util.List;

public record MetricData(String mode, String resultState,
                         List<SeriesView> series, int seriesCount) {
}
