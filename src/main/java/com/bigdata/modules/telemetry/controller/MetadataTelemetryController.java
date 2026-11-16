package com.bigdata.modules.telemetry.controller;

import com.bigdata.common.vo.Result;
import com.bigdata.modules.telemetry.dto.MetadataQueryRequest;
import com.bigdata.modules.telemetry.service.metadata.MetadataTelemetryQueryService;
import com.bigdata.modules.telemetry.vo.MetadataQueryResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.bigdata.modules.telemetry.service.metricdata.spi.MetricDiscoveryProvider;
import com.bigdata.modules.telemetry.service.metadata.spi.ServiceMetadataProvider;

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
public class MetadataTelemetryController {

    private final MetadataTelemetryQueryService service;

    public MetadataTelemetryController(MetadataTelemetryQueryService service) {
        this.service = service;
    }

    @PostMapping("/metadata/query")
    public Result<MetadataQueryResponse> query(@RequestBody MetadataQueryRequest request) {
        return Result.success(service.query(request));
    }
}
