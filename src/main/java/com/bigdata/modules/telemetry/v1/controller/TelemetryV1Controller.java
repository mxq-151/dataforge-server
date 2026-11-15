package com.bigdata.modules.telemetry.v1.controller;

import com.bigdata.common.vo.Result;
import com.bigdata.modules.telemetry.v1.dto.LogQueryV1Request;
import com.bigdata.modules.telemetry.v1.dto.MetricQueryV1Request;
import com.bigdata.modules.telemetry.v1.service.LogTelemetryQueryService;
import com.bigdata.modules.telemetry.v1.service.MetricTelemetryQueryService;
import com.bigdata.modules.telemetry.v1.vo.TelemetryQueryV1Response;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务诊断取数 v1：只保留两个接口。
 *
 * <ul>
 *   <li>{@code POST /telemetry/v1/metrics/query}：状态数据（Prometheus 指标模型）；</li>
 *   <li>{@code POST /telemetry/v1/logs/query}：日志数据（stdout / stderr / slowquery）。</li>
 * </ul>
 *
 * <p>请求不指定后端；实际后端由服务端选择并在响应里回显。目录通过 {@code mode=catalog} 获取。</p>
 */
@RestController
@RequestMapping("/telemetry/v1")
public class TelemetryV1Controller {

    private final MetricTelemetryQueryService metricService;
    private final LogTelemetryQueryService logService;

    public TelemetryV1Controller(MetricTelemetryQueryService metricService,
                                 LogTelemetryQueryService logService) {
        this.metricService = metricService;
        this.logService = logService;
    }

    @PostMapping("/metrics/query")
    public Result<TelemetryQueryV1Response> metrics(@RequestBody MetricQueryV1Request request) {
        return Result.success(metricService.query(request));
    }

    @PostMapping("/logs/query")
    public Result<TelemetryQueryV1Response> logs(@RequestBody LogQueryV1Request request) {
        return Result.success(logService.query(request));
    }
}
