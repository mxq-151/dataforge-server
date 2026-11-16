package com.bigdata.modules.telemetry.controller;

import com.bigdata.common.vo.Result;
import com.bigdata.modules.telemetry.vo.TelemetryQueryResponse;
import com.bigdata.modules.telemetry.service.logdata.LogTelemetryQueryService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.bigdata.modules.telemetry.dto.LogQueryRequest;

/**
 * 日志取数：{@code POST /telemetry/v1/logs/query}。
 *
 * <p>服务日志（stdout / stderr / slowquery）与任务日志共用同一接口，靠 {@code dataType} 区分；
 * 后端由服务端按目录与路由规则选择。</p>
 */
@RestController
@RequestMapping("/telemetry/v1/logs")
public class LogTelemetryController {

    private final LogTelemetryQueryService logService;

    public LogTelemetryController(LogTelemetryQueryService logService) {
        this.logService = logService;
    }

    @PostMapping("/query")
    public Result<TelemetryQueryResponse> query(@RequestBody LogQueryRequest request) {
        return Result.success(logService.query(request));
    }
}
