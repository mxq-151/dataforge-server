package com.bigdata.modules.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.modules.system.entity.OperationAuditLog;
import com.bigdata.modules.system.mapper.OperationAuditLogMapper;
import com.bigdata.modules.system.vo.AuditStatisticsVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class AuditLogService {

    @Autowired
    private OperationAuditLogMapper auditLogMapper;

    /** 最大每页记录数 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 允许排序的字段白名单 */
    private static final java.util.Set<String> SORTABLE_FIELDS = java.util.Set.of(
            "operationTime", "durationMs", "responseStatus", "username", "module", "operation"
    );

    public Page<OperationAuditLog> getAuditLogList(int current, int size, String module, String operation,
                                                   String username, Long tenantId,
                                                   Integer isSensitive, String operationType,
                                                   LocalDateTime startTime, LocalDateTime endTime,
                                                   Integer isAiRequest,
                                                   String sortField, String sortOrder) {
        // 分页大小限制
        if (size > MAX_PAGE_SIZE) {
            size = MAX_PAGE_SIZE;
        }
        if (size < 1) {
            size = 20;
        }
        if (current < 1) {
            current = 1;
        }

        Page<OperationAuditLog> page = new Page<>(current, size);
        LambdaQueryWrapper<OperationAuditLog> wrapper = new LambdaQueryWrapper<>();

        if (module != null && !module.isEmpty()) {
            wrapper.eq(OperationAuditLog::getModule, module);
        }
        if (operation != null && !operation.isEmpty()) {
            wrapper.like(OperationAuditLog::getOperation, operation);
        }
        if (username != null && !username.isEmpty()) {
            wrapper.like(OperationAuditLog::getUsername, username);
        }
        if (tenantId != null) {
            wrapper.eq(OperationAuditLog::getTenantId, tenantId);
        }
        if (isSensitive != null) {
            wrapper.eq(OperationAuditLog::getIsSensitive, isSensitive);
        }
        if (operationType != null && !operationType.isEmpty()) {
            wrapper.eq(OperationAuditLog::getOperationType, operationType);
        }
        if (isAiRequest != null) {
            wrapper.eq(OperationAuditLog::getIsAiRequest, isAiRequest);
        }
        if (startTime != null) {
            wrapper.ge(OperationAuditLog::getOperationTime, startTime);
        }
        if (endTime != null) {
            wrapper.le(OperationAuditLog::getOperationTime, endTime);
        }

        // 动态排序（白名单校验，防SQL注入）
        applySort(wrapper, sortField, sortOrder);

        return auditLogMapper.selectPage(page, wrapper);
    }

    /**
     * 应用动态排序，仅允许白名单内的字段
     */
    private void applySort(LambdaQueryWrapper<OperationAuditLog> wrapper, String sortField, String sortOrder) {
        if (sortField == null || sortField.isEmpty()) {
            // 默认按操作时间倒序
            wrapper.orderByDesc(OperationAuditLog::getOperationTime);
            return;
        }

        if (!SORTABLE_FIELDS.contains(sortField)) {
            // 不在白名单中，回退默认排序
            wrapper.orderByDesc(OperationAuditLog::getOperationTime);
            return;
        }

        boolean asc = "asc".equalsIgnoreCase(sortOrder);
        switch (sortField) {
            case "operationTime":
                if (asc) wrapper.orderByAsc(OperationAuditLog::getOperationTime);
                else wrapper.orderByDesc(OperationAuditLog::getOperationTime);
                break;
            case "durationMs":
                if (asc) wrapper.orderByAsc(OperationAuditLog::getDurationMs);
                else wrapper.orderByDesc(OperationAuditLog::getDurationMs);
                break;
            case "responseStatus":
                if (asc) wrapper.orderByAsc(OperationAuditLog::getResponseStatus);
                else wrapper.orderByDesc(OperationAuditLog::getResponseStatus);
                break;
            case "username":
                if (asc) wrapper.orderByAsc(OperationAuditLog::getUsername);
                else wrapper.orderByDesc(OperationAuditLog::getUsername);
                break;
            case "module":
                if (asc) wrapper.orderByAsc(OperationAuditLog::getModule);
                else wrapper.orderByDesc(OperationAuditLog::getModule);
                break;
            case "operation":
                if (asc) wrapper.orderByAsc(OperationAuditLog::getOperation);
                else wrapper.orderByDesc(OperationAuditLog::getOperation);
                break;
            default:
                wrapper.orderByDesc(OperationAuditLog::getOperationTime);
        }
    }

    public void saveAuditLog(OperationAuditLog auditLog) {
        if (auditLog.getOperationTime() == null) {
            auditLog.setOperationTime(LocalDateTime.now());
        }
        if (auditLog.getIsSensitive() == null) {
            auditLog.setIsSensitive(0);
        }
        auditLogMapper.insert(auditLog);
    }

    @Async
    public void saveAuditLogAsync(OperationAuditLog auditLog) {
        try {
            saveAuditLog(auditLog);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(AuditLogService.class)
                    .error("异步保存审计日志失败", e);
        }
    }

    public OperationAuditLog getAuditLogById(Long id) {
        return auditLogMapper.selectById(id);
    }

    public OperationAuditLog createAuditLog(Long userId, String username, Long tenantId, String tenantName,
                                            String module, String operation, String operationType,
                                            String targetType, Long targetId, String targetName,
                                            HttpServletRequest request, String requestParams) {
        OperationAuditLog log = new OperationAuditLog();
        log.setUserId(userId);
        log.setUsername(username);
        log.setTenantId(tenantId);
        log.setTenantName(tenantName);
        log.setModule(module);
        log.setOperation(operation);
        log.setOperationType(operationType);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setTargetName(targetName);

        if (request != null) {
            log.setRequestMethod(request.getMethod());
            log.setRequestUrl(request.getRequestURI());
            log.setIpAddress(getClientIp(request));
            log.setUserAgent(request.getHeader("User-Agent"));
        }

        log.setRequestParams(requestParams);

        return log;
    }

    public List<String> getModules() {
        LambdaQueryWrapper<OperationAuditLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(OperationAuditLog::getModule)
               .groupBy(OperationAuditLog::getModule);
        return auditLogMapper.selectList(wrapper)
                .stream()
                .map(OperationAuditLog::getModule)
                .toList();
    }

    public List<String> getOperations() {
        LambdaQueryWrapper<OperationAuditLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(OperationAuditLog::getOperation)
               .groupBy(OperationAuditLog::getOperation);
        return auditLogMapper.selectList(wrapper)
                .stream()
                .map(OperationAuditLog::getOperation)
                .toList();
    }

    public List<String> getOperationTypes() {
        LambdaQueryWrapper<OperationAuditLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(OperationAuditLog::getOperationType)
               .isNotNull(OperationAuditLog::getOperationType)
               .groupBy(OperationAuditLog::getOperationType);
        return auditLogMapper.selectList(wrapper)
                .stream()
                .map(OperationAuditLog::getOperationType)
                .toList();
    }

    public AuditStatisticsVO getStatistics(LocalDateTime startTime, LocalDateTime endTime) {
        LambdaQueryWrapper<OperationAuditLog> wrapper = new LambdaQueryWrapper<>();
        if (startTime != null) {
            wrapper.ge(OperationAuditLog::getOperationTime, startTime);
        }
        if (endTime != null) {
            wrapper.le(OperationAuditLog::getOperationTime, endTime);
        }

        long total = auditLogMapper.selectCount(wrapper);

        LambdaQueryWrapper<OperationAuditLog> sensitiveWrapper = new LambdaQueryWrapper<>();
        if (startTime != null) {
            sensitiveWrapper.ge(OperationAuditLog::getOperationTime, startTime);
        }
        if (endTime != null) {
            sensitiveWrapper.le(OperationAuditLog::getOperationTime, endTime);
        }
        sensitiveWrapper.eq(OperationAuditLog::getIsSensitive, 1);
        long sensitiveCount = auditLogMapper.selectCount(sensitiveWrapper);

        LambdaQueryWrapper<OperationAuditLog> errorWrapper = new LambdaQueryWrapper<>();
        if (startTime != null) {
            errorWrapper.ge(OperationAuditLog::getOperationTime, startTime);
        }
        if (endTime != null) {
            errorWrapper.le(OperationAuditLog::getOperationTime, endTime);
        }
        errorWrapper.ne(OperationAuditLog::getResponseStatus, 200);
        long errorCount = auditLogMapper.selectCount(errorWrapper);

        AuditStatisticsVO vo = new AuditStatisticsVO();
        vo.setTotalOperations(total);
        vo.setSensitiveOperations(sensitiveCount);
        vo.setErrorOperations(errorCount);
        return vo;
    }

    public int deleteLogsBefore(LocalDateTime beforeTime) {
        String dateStr = beforeTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        try {
            List<String> expiredPartitions = auditLogMapper.selectExpiredPartitions(dateStr);
            for (String partitionName : expiredPartitions) {
                auditLogMapper.dropPartition(partitionName);
            }
            return expiredPartitions.size();
        } catch (Exception e) {
            LambdaQueryWrapper<OperationAuditLog> wrapper = new LambdaQueryWrapper<>();
            wrapper.lt(OperationAuditLog::getOperationTime, beforeTime);
            return auditLogMapper.delete(wrapper);
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
            ip = "127.0.0.1";
        }
        return ip;
    }
}
