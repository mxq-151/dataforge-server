package com.bigdata.modules.logs.service;

import com.bigdata.common.utils.SecurityUtils;
import com.bigdata.modules.logs.dto.LogQueryDTO;
import com.bigdata.modules.logs.vo.LogQueryResultVO;
import com.bigdata.modules.system.entity.OperationAuditLog;
import com.bigdata.modules.system.service.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 日志查询审计：谁、查了哪个服务/集群/种类、时间范围、游标、结果规模与耗时。
 *
 * <p>复用既有 {@link AuditLogService}，不新增表；写入走异步，失败只告警不影响查询。</p>
 */
@Service
public class LogAuditService {

    private static final Logger log = LoggerFactory.getLogger(LogAuditService.class);
    private static final int MAX_PARAM_LENGTH = 2000;

    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public LogAuditService(AuditLogService auditLogService, ObjectMapper objectMapper) {
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    public void record(LogQueryDTO dto, LogQueryResultVO result) {
        if (dto == null || result == null) {
            return;
        }
        try {
            OperationAuditLog auditLog = auditLogService.createAuditLog(
                    SecurityUtils.getCurrentUserId(),
                    SecurityUtils.getCurrentUsername(),
                    SecurityUtils.getCurrentTenantId(),
                    null,
                    "logs",
                    "QUERY_LOG",
                    "QUERY",
                    result.getServiceType(),
                    null,
                    result.getSourceId(),
                    currentRequest(),
                    buildParams(dto, result));
            auditLog.setResponseStatus(200);
            auditLog.setDurationMs((int) result.getElapsedMs());
            auditLog.setIsAiRequest(SecurityUtils.isAiRequest() ? 1 : 0);
            auditLogService.saveAuditLogAsync(auditLog);
        } catch (Exception e) {
            log.warn("写日志查询审计失败: {}", e.getMessage());
        }
    }

    private String buildParams(LogQueryDTO dto, LogQueryResultVO result) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("serviceType", result.getServiceType());
        params.put("cluster", result.getCluster());
        params.put("componentRole", result.getComponentRole());
        params.put("logKind", result.getLogKind());
        params.put("ref", dto.getRef());
        if (dto.getRef() != null) {
            for (String key : new String[]{"jobId", "job_id", "job"}) {
                String jobId = dto.getRef().get(key);
                if (jobId != null && !jobId.isBlank()) {
                    params.put("jobId", jobId);
                    break;
                }
            }
        }
        if (dto.getTimeRange() != null) {
            params.put("timeRange", Map.of(
                    "start", String.valueOf(dto.getTimeRange().getStart()),
                    "end", String.valueOf(dto.getTimeRange().getEnd())));
        }
        params.put("keyword", dto.getKeyword());
        params.put("cursor", dto.getCursor() == null ? "" : dto.getCursor());
        params.put("lineCount", result.getLineCount());
        params.put("byteSize", result.getByteSize());
        params.put("degraded", result.isDegraded());
        params.put("errorCode", result.getErrorCode());
        try {
            String json = objectMapper.writeValueAsString(params);
            return json.length() > MAX_PARAM_LENGTH ? json.substring(0, MAX_PARAM_LENGTH) + "...[截断]" : json;
        } catch (Exception e) {
            return null;
        }
    }

    private HttpServletRequest currentRequest() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes == null ? null : attributes.getRequest();
    }
}
