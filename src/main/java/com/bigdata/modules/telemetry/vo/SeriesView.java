package com.bigdata.modules.telemetry.vo;

import java.util.List;
import java.util.Map;

public record SeriesView(MetricMeta metric, Map<String, String> labels,
                         List<SampleView> samples, int sampleCount) {
}
