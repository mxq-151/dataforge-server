package com.bigdata.modules.telemetry.compat;

import com.bigdata.modules.logs.dto.LogQueryDTO;
import com.bigdata.modules.logs.vo.LogQueryResultVO;
import com.bigdata.modules.telemetry.dto.TelemetryQueryRequest;
import com.bigdata.modules.telemetry.model.TelemetryOutcome;
import com.bigdata.modules.telemetry.service.TelemetryQueryService;
import com.bigdata.modules.telemetry.vo.TelemetryQueryResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 旧日志接口的分层路由。
 *
 * <p>职责边界：</p>
 * <ul>
 *   <li>{@code ds}/{@code yarn}/{@code file} 只走旧实现，保持现状；</li>
 *   <li>{@code spark}/{@code flink} 且能映射到四元组时，内部走 telemetry core，
 *       再把结果还原成 {@link LogQueryResultVO}，响应字段逐字不变；</li>
 *   <li>显式指定 {@code sourceId}、或参数无法映射时回退旧实现，避免改变既有语义。</li>
 * </ul>
 *
 * <p>收口条件：等 agentkit v4 标识落地、所有调用方迁到 {@code /api/telemetry/query} 后，
 * 旧接口仅保留 ds/yarn/file 三类，{@code logKind}/{@code componentRole} 随之下线。</p>
 */
@Service
public class LegacyLogTelemetryRouter {

    private static final Set<String> ENGINE_SERVICES = Set.of("spark", "flink");

    private static final Map<String, String> COMPONENT_BY_ROLE = Map.of(
            "driver", "spark.driver",
            "executor", "spark.executor",
            "job_manager", "flink.jobmanager",
            "task_manager", "flink.taskmanager");

    private static final Map<String, String> DATA_TYPE_BY_KIND = Map.of(
            "runtime", "log.runtime",
            "stdout", "log.stdout",
            "audit", "log.audit",
            "exception", "log.exception_summary",
            "aggregate", "log.runtime");

    private final TelemetryQueryService telemetryQueryService;

    public LegacyLogTelemetryRouter(TelemetryQueryService telemetryQueryService) {
        this.telemetryQueryService = telemetryQueryService;
    }

    /**
     * @return 走 telemetry 时的旧响应；返回 {@code null} 表示交给旧实现处理
     */
    public LogQueryResultVO queryIfEngine(LogQueryDTO dto) {
        if (!routeable(dto)) {
            return null;
        }
        String serviceType = dto.getServiceType().trim().toLowerCase();
        String componentId = COMPONENT_BY_ROLE.get(dto.getComponentRole().trim().toLowerCase());
        String dataTypeId = DATA_TYPE_BY_KIND.get(dto.getLogKind().trim().toLowerCase());

        TelemetryQueryRequest request = new TelemetryQueryRequest();
        request.setServiceType(serviceType);
        request.setComponentId(componentId);
        request.setHostId("cluster");
        request.setDataTypeId(dataTypeId);
        request.setRef(dto.getRef() == null ? new java.util.LinkedHashMap<>()
                : new java.util.LinkedHashMap<>(dto.getRef()));
        request.setTimeRange(dto.getTimeRange());
        request.setKeyword(dto.getKeyword());
        request.setCursor(dto.getCursor());
        request.setLimit(dto.getLimit());
        request.setMaxBytes(dto.getMaxBytes());
        if (dto.getCluster() != null && !dto.getCluster().isBlank()) {
            request.getRef().put("cluster", dto.getCluster());
        }

        return toLegacy(dto, telemetryQueryService.query(request));
    }

    private boolean routeable(LogQueryDTO dto) {
        if (dto == null || (dto.getSourceId() != null && !dto.getSourceId().isBlank())) {
            return false;
        }
        if (dto.getServiceType() == null || dto.getComponentRole() == null || dto.getLogKind() == null) {
            return false;
        }
        String serviceType = dto.getServiceType().trim().toLowerCase();
        String role = dto.getComponentRole().trim().toLowerCase();
        if (!ENGINE_SERVICES.contains(serviceType) || !DATA_TYPE_BY_KIND.containsKey(
                dto.getLogKind().trim().toLowerCase())) {
            return false;
        }
        String componentId = COMPONENT_BY_ROLE.get(role);
        return componentId != null && componentId.startsWith(serviceType + ".");
    }

    private LogQueryResultVO toLegacy(LogQueryDTO dto, TelemetryQueryResponse response) {
        LogQueryResultVO vo = new LogQueryResultVO();
        vo.setServiceType(dto.getServiceType());
        vo.setCluster(dto.getCluster());
        vo.setComponentRole(dto.getComponentRole());
        vo.setLogKind(dto.getLogKind());
        vo.setElapsedMs(response.meta() == null ? 0 : response.meta().elapsedMs());

        if (response.outcome() == TelemetryOutcome.OK) {
            Map<String, Object> data = response.data() == null ? Map.of() : response.data();
            vo.setSourceId(str(data.get("sourceId")));
            vo.setContent(str(data.get("content")));
            vo.setLines(lines(data.get("lines")));
            vo.setLineCount(intValue(data.get("lineCount")));
            vo.setByteSize(intValue(data.get("byteSize")));
            vo.setNextCursor(str(data.get("nextCursor")));
            vo.setHasMore(Boolean.TRUE.equals(data.get("hasMore")));
            vo.setTruncated(Boolean.TRUE.equals(data.get("truncated")));
            vo.setResolvedHost(str(data.get("resolvedHost")));
            vo.setResolvedPath(str(data.get("resolvedPath")));
            vo.setDegraded(false);
            return vo;
        }

        vo.setDegraded(true);
        vo.setLines(List.of());
        vo.setContent("");
        vo.setErrorCode(response.error() == null ? null : response.error().code().name());
        vo.setDegradationReason(response.error() == null ? null : response.error().reason());
        vo.setMessage(response.error() == null ? null : response.error().reason());
        return vo;
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> lines(Object value) {
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
