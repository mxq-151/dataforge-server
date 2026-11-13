package com.bigdata.modules.runtime.service;

import com.bigdata.common.utils.SecurityUtils;
import com.bigdata.modules.runtime.config.RuntimeProperties;
import com.bigdata.modules.runtime.dto.RuntimeQueryDTO;
import com.bigdata.modules.runtime.model.RuntimeSource;
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
 * 运行时查询审计：作业 id、指标族、组件范围、耗时与是否降级。
 * 复用 {@link AuditLogService}，不新增表；写入异步，失败只告警。
 */
@Service
public class RuntimeAuditService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeAuditService.class);
    private static final int MAX_PARAM_LENGTH = 2000;

    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public RuntimeAuditService(AuditLogService auditLogService, ObjectMapper objectMapper) {
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    public void record(RuntimeQueryDTO dto, RuntimeSource source, String action,
                       long elapsedMs, boolean degraded, String errorCode) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("action", action);
            params.put("sourceId", source == null ? dto.getSourceId() : source.getId());
            params.put("serviceType", source == null ? dto.getServiceType()
                    : source.getServiceType() == null ? dto.getServiceType()
                    : source.getServiceType().wireName());
            params.put("cluster", source == null ? dto.getCluster() : source.getCluster());
            params.put("mode", source == null ? dto.getMode() : source.getMode().wireName());
            params.put("jobId", dto.getJobId());
            params.put("families", dto.getFamilies());
            params.put("metrics", dto.getMetrics());
            params.put("componentScope", dto.getComponents());
            params.put("includeConfig", dto.getIncludeConfig());
            params.put("includeVertices", dto.getIncludeVertices());
            params.put("windowSeconds", dto.getWindowSeconds());
            params.put("elapsedMs", elapsedMs);
            params.put("degraded", degraded);
            params.put("errorCode", errorCode);

            OperationAuditLog auditLog = auditLogService.createAuditLog(
                    SecurityUtils.getCurrentUserId(),
                    SecurityUtils.getCurrentUsername(),
                    SecurityUtils.getCurrentTenantId(),
                    null,
                    "runtime",
                    action,
                    "QUERY",
                    "RUNTIME_JOB",
                    null,
                    dto.getJobId(),
                    currentRequest(),
                    toJson(params));
            auditLog.setResponseStatus(degraded ? 503 : 200);
            auditLog.setDurationMs((int) elapsedMs);
            auditLog.setIsAiRequest(SecurityUtils.isAiRequest() ? 1 : 0);
            auditLogService.saveAuditLogAsync(auditLog);
        } catch (Exception e) {
            log.warn("写运行时查询审计失败: {}", e.getMessage());
        }
    }

    private String toJson(Map<String, Object> params) {
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
