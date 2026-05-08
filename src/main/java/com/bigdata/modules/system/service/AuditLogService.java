package com.bigdata.modules.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.modules.system.entity.OperationAuditLog;
import com.bigdata.modules.system.mapper.OperationAuditLogMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class AuditLogService {

    @Autowired
    private OperationAuditLogMapper auditLogMapper;

    public Page<OperationAuditLog> getAuditLogList(int current, int size, String module, String operation,
                                                   String username, Integer isSensitive,
                                                   LocalDateTime startTime, LocalDateTime endTime) {
        Page<OperationAuditLog> page = new Page<>(current, size);
        LambdaQueryWrapper<OperationAuditLog> wrapper = new LambdaQueryWrapper<>();

        if (module != null && !module.isEmpty()) {
            wrapper.eq(OperationAuditLog::getModule, module);
        }
        if (operation != null && !operation.isEmpty()) {
            wrapper.eq(OperationAuditLog::getOperation, operation);
        }
        if (username != null && !username.isEmpty()) {
            wrapper.like(OperationAuditLog::getUsername, username);
        }
        if (isSensitive != null) {
            wrapper.eq(OperationAuditLog::getIsSensitive, isSensitive);
        }
        if (startTime != null) {
            wrapper.ge(OperationAuditLog::getOperationTime, startTime);
        }
        if (endTime != null) {
            wrapper.le(OperationAuditLog::getOperationTime, endTime);
        }

        wrapper.orderByDesc(OperationAuditLog::getOperationTime);
        return auditLogMapper.selectPage(page, wrapper);
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

    public OperationAuditLog getAuditLogById(Long id) {
        return auditLogMapper.selectById(id);
    }

    public OperationAuditLog createAuditLog(Long userId, String username, String module,
                                            String operation, String targetType, Long targetId,
                                            String targetName, HttpServletRequest request) {
        OperationAuditLog log = new OperationAuditLog();
        log.setUserId(userId);
        log.setUsername(username);
        log.setModule(module);
        log.setOperation(operation);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setTargetName(targetName);

        if (request != null) {
            log.setRequestMethod(request.getMethod());
            log.setRequestUrl(request.getRequestURI());
            log.setIpAddress(getClientIp(request));
            log.setUserAgent(request.getHeader("User-Agent"));
        }

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

    public Map<String, Object> getStatistics(LocalDateTime startTime, LocalDateTime endTime) {
        LambdaQueryWrapper<OperationAuditLog> wrapper = new LambdaQueryWrapper<>();
        if (startTime != null) {
            wrapper.ge(OperationAuditLog::getOperationTime, startTime);
        }
        if (endTime != null) {
            wrapper.le(OperationAuditLog::getOperationTime, endTime);
        }

        long total = auditLogMapper.selectCount(wrapper);

        LambdaQueryWrapper<OperationAuditLog> sensitiveWrapper = wrapper.clone();
        sensitiveWrapper.eq(OperationAuditLog::getIsSensitive, 1);
        long sensitiveCount = auditLogMapper.selectCount(sensitiveWrapper);

        return Map.of(
                "total", total,
                "sensitiveCount", sensitiveCount,
                "normalCount", total - sensitiveCount
        );
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
        return ip;
    }
}
