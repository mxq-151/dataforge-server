package com.bigdata.modules.telemetry.controller;

import com.bigdata.common.vo.Result;
import com.bigdata.modules.telemetry.vo.TelemetryQueryResponse;
import com.bigdata.modules.telemetry.service.logdata.ExceptionTelemetryQueryService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.bigdata.modules.telemetry.dto.ExceptionQueryRequest;

/**
 * 引擎结构化异常摘要：{@code POST /telemetry/v1/exceptions/query}。
 *
 * <p>异常是结构化证据，不拍平成日志行；当前只有 Flink 提供该能力。</p>
 */
@RestController
@RequestMapping("/telemetry/v1/exceptions")
public class ExceptionTelemetryController {

    private final ExceptionTelemetryQueryService exceptionService;

    public ExceptionTelemetryController(ExceptionTelemetryQueryService exceptionService) {
        this.exceptionService = exceptionService;
    }

    @PostMapping("/query")
    public Result<TelemetryQueryResponse> query(@RequestBody ExceptionQueryRequest request) {
        return Result.success(exceptionService.query(request));
    }
}
