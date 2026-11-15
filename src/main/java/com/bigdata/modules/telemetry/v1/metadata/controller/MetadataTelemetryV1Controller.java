package com.bigdata.modules.telemetry.v1.metadata.controller;

import com.bigdata.common.vo.Result;
import com.bigdata.modules.telemetry.v1.metadata.dto.MetadataQueryV1Request;
import com.bigdata.modules.telemetry.v1.metadata.service.MetadataTelemetryQueryService;
import com.bigdata.modules.telemetry.v1.metadata.vo.TelemetryMetadataV1Response;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务版本与配置查询（第五类取数能力）。
 *
 * <p>{@code POST /telemetry/v1/metadata/query}：按 serviceType / componentId / hostId 查版本与配置，
 * 取数方式由可插拔的 {@code ServiceMetadataProvider} 决定；响应沿用四态外壳。
 * {@code scope=metrics} 改走 {@code MetricDiscoveryProvider} 列举实例真实暴露的指标名，
 * 并在可用时从 Prometheus / VictoriaMetrics 元数据补类型与单位。</p>
 */
@RestController
@RequestMapping("/telemetry/v1")
public class MetadataTelemetryV1Controller {

    private final MetadataTelemetryQueryService service;

    public MetadataTelemetryV1Controller(MetadataTelemetryQueryService service) {
        this.service = service;
    }

    @PostMapping("/metadata/query")
    public Result<TelemetryMetadataV1Response> query(@RequestBody MetadataQueryV1Request request) {
        return Result.success(service.query(request));
    }
}
