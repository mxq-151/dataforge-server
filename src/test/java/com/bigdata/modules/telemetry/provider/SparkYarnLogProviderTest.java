package com.bigdata.modules.telemetry.provider;

import com.bigdata.modules.logs.config.LogAccessProperties;
import com.bigdata.modules.logs.dto.LogQueryDTO;
import com.bigdata.modules.logs.model.ComponentRole;
import com.bigdata.modules.logs.model.LogKind;
import com.bigdata.modules.logs.model.LogSource;
import com.bigdata.modules.logs.model.ServiceType;
import com.bigdata.modules.logs.service.LogAccessControlService;
import com.bigdata.modules.logs.service.LogQueryService;
import com.bigdata.modules.logs.service.LogSourceCatalog;
import com.bigdata.modules.logs.vo.LogQueryResultVO;
import com.bigdata.modules.telemetry.model.Capability;
import com.bigdata.modules.telemetry.model.CapabilityState;
import com.bigdata.modules.telemetry.model.DataTypes;
import com.bigdata.modules.telemetry.model.TelemetryIdentity;
import com.bigdata.modules.telemetry.provider.LogTelemetryProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * spark.driver/executor 的日志在新模型里走 YARN 聚合日志（transport=yarn-rest/yarn-cli）。
 */
class SparkYarnLogProviderTest {

    @Test
    @DisplayName("能力判定：spark.driver 通过 YARN 聚合日志可用，transport 为 yarn-*")
    void sparkDriverFallsBackToYarnAggregate() {
        LogTelemetryProvider provider = providerWithYarnSource();

        Capability capability = provider.evaluate(new CapabilityContext(
                "spark", "spark.driver", "cluster", DataTypes.LOG_RUNTIME));

        assertEquals(CapabilityState.AVAILABLE, capability.state());
        assertTrue(capability.transports().contains("yarn-cli"));
        assertTrue(capability.transports().contains("yarn-rest"));
        assertFalse(capability.transports().contains("spark-shs"));
    }

    @Test
    @DisplayName("取数：映射为 serviceType=yarn + logKind=aggregate + role=node_manager")
    void fetchUsesYarnAggregate() {
        LogTelemetryProvider provider = providerWithYarnSource();

        provider.fetch(new TelemetryQueryContext(
                new TelemetryIdentity("spark", "spark.driver", "cluster", DataTypes.LOG_RUNTIME),
                Map.of("applicationId", "application_1_0001"), null, null, null, 100, 1024,
                Map.of()));

        ArgumentCaptor<LogQueryDTO> captor = ArgumentCaptor.forClass(LogQueryDTO.class);
        verify(queryService).query(captor.capture());
        assertEquals("yarn", captor.getValue().getServiceType());
        assertEquals("aggregate", captor.getValue().getLogKind());
        assertEquals("node_manager", captor.getValue().getComponentRole());
        assertEquals("application_1_0001", captor.getValue().getRef().get("applicationId"));
    }

    private final LogQueryService queryService = mock(LogQueryService.class);

    private LogTelemetryProvider providerWithYarnSource() {
        LogAccessProperties.Source config = mock(LogAccessProperties.Source.class);
        when(config.getId()).thenReturn("yarn-default");
        when(config.getName()).thenReturn("YARN");
        when(config.getCluster()).thenReturn("default");
        LogSource yarn = new LogSource(config, ServiceType.YARN,
                List.of(ComponentRole.NODE_MANAGER),
                List.of(LogKind.AGGREGATE, LogKind.RUNTIME), "http://rm:8088", null, true, null);

        LogSourceCatalog catalog = mock(LogSourceCatalog.class);
        when(catalog.listAll()).thenReturn(List.of(yarn));
        LogAccessControlService accessControl = mock(LogAccessControlService.class);
        when(accessControl.canRead(any(LogSource.class))).thenReturn(true);

        LogQueryResultVO result = new LogQueryResultVO();
        result.setLines(List.of("line-1"));
        result.setContent("line-1");
        result.setLineCount(1);
        result.setDegraded(false);
        when(queryService.query(any(LogQueryDTO.class))).thenReturn(result);

        return new LogTelemetryProvider(catalog, accessControl, queryService);
    }
}
