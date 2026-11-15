package com.bigdata.modules.telemetry.controller;

import com.bigdata.common.vo.Result;
import com.bigdata.modules.telemetry.dto.TelemetryQueryRequest;
import com.bigdata.modules.telemetry.model.DataTypeDefinition;
import com.bigdata.modules.telemetry.service.TelemetryCapabilityService;
import com.bigdata.modules.telemetry.service.TelemetryQueryService;
import com.bigdata.modules.telemetry.vo.CapabilityTreeView;
import com.bigdata.modules.telemetry.vo.TelemetryQueryResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;

/**
 * 统一取数入口：能力判定、数据类型目录、统一取数。
 *
 * <p>controller 不感知任何具体服务；新增服务或数据类型只注册 provider。</p>
 */
@RestController
@RequestMapping("/telemetry")
public class TelemetryController {

    private final TelemetryCapabilityService capabilityService;
    private final TelemetryQueryService queryService;

    public TelemetryController(TelemetryCapabilityService capabilityService,
                               TelemetryQueryService queryService) {
        this.capabilityService = capabilityService;
        this.queryService = queryService;
    }

    @GetMapping("/capabilities")
    public Result<CapabilityTreeView> capabilities(
            @RequestParam(required = false) String serviceType,
            @RequestParam(required = false) String componentId,
            @RequestParam(required = false) String hostId) {
        return Result.success(capabilityService.capabilities(serviceType, componentId, hostId));
    }

    @GetMapping("/data-types")
    public Result<Collection<DataTypeDefinition>> dataTypes() {
        return Result.success(capabilityService.dataTypes());
    }

    @PostMapping("/query")
    public Result<TelemetryQueryResponse> query(@RequestBody TelemetryQueryRequest request) {
        return Result.success(queryService.query(request));
    }
}
