package com.bigdata.modules.telemetry.v1.runtime.controller;

import com.bigdata.common.vo.Result;
import com.bigdata.modules.telemetry.v1.runtime.dto.CompareV1Request;
import com.bigdata.modules.telemetry.v1.runtime.dto.ConfigV1Request;
import com.bigdata.modules.telemetry.v1.runtime.dto.DiscoveryQueryV1Request;
import com.bigdata.modules.telemetry.v1.runtime.dto.SnapshotV1Request;
import com.bigdata.modules.telemetry.v1.runtime.service.RuntimeTelemetryQueryService;
import com.bigdata.modules.telemetry.v1.vo.TelemetryQueryV1Response;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行时取数 v1：discovery / snapshot / config / compare。
 *
 * <p>四个接口与 metrics/logs 共用四态结果模型；compare 只用进程内环形缓冲，
 * 进程重启历史即丢、多实例部署各自独立不可比。</p>
 */
@RestController
@RequestMapping("/telemetry/v1")
public class RuntimeTelemetryV1Controller {

    private final RuntimeTelemetryQueryService service;

    public RuntimeTelemetryV1Controller(RuntimeTelemetryQueryService service) {
        this.service = service;
    }

    @PostMapping("/discovery/query")
    public Result<TelemetryQueryV1Response> discovery(
            @RequestBody DiscoveryQueryV1Request request) {
        return Result.success(service.discovery(request));
    }

    @PostMapping("/runtime/snapshot")
    public Result<TelemetryQueryV1Response> snapshot(@RequestBody SnapshotV1Request request) {
        return Result.success(service.snapshot(request));
    }

    @PostMapping("/runtime/config")
    public Result<TelemetryQueryV1Response> config(@RequestBody ConfigV1Request request) {
        return Result.success(service.config(request));
    }

    @PostMapping("/runtime/compare")
    public Result<TelemetryQueryV1Response> compare(@RequestBody CompareV1Request request) {
        return Result.success(service.compare(request));
    }
}
