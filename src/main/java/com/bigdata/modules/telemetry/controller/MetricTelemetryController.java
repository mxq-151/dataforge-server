package com.bigdata.modules.telemetry.controller;

import com.bigdata.common.vo.Result;
import com.bigdata.modules.telemetry.vo.TelemetryQueryResponse;
import com.bigdata.modules.telemetry.service.metricdata.MetricTelemetryQueryService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.bigdata.modules.telemetry.dto.MetricQueryRequest;

/**
 * 指标取数：{@code POST /telemetry/v1/metrics/query}。
 *
 * <p>{@code mode=catalog} 返回目录（组件 × 指标语义 × 运行时发现），其余模式走
 * Prometheus 指标模型。请求不指定后端，实际后端由服务端选择并在响应里回显。</p>
 */
@RestController
@RequestMapping("/telemetry/v1/metrics")
public class MetricTelemetryController {

    private final MetricTelemetryQueryService metricService;

    public MetricTelemetryController(MetricTelemetryQueryService metricService) {
        this.metricService = metricService;
    }

    @PostMapping("/query")
    public Result<TelemetryQueryResponse> query(@RequestBody MetricQueryRequest request) {
        return Result.success(metricService.query(request));
    }
}
