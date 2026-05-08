package com.bigdata.common.aspect;

import com.bigdata.modules.system.entity.OperationAuditLog;
import com.bigdata.modules.system.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

@Aspect
@Component
public class AuditLogAspect {

    private static final Logger logger = LoggerFactory.getLogger(AuditLogAspect.class);

    @Autowired
    private AuditLogService auditLogService;

    @Pointcut("@annotation(com.bigdata.common.annotation.AuditLog)")
    public void auditLogPointcut() {
    }

    @Around("auditLogPointcut() && @annotation(auditLog)")
    public Object around(ProceedingJoinPoint joinPoint, com.bigdata.common.annotation.AuditLog auditLog) throws Throwable {
        long startTime = System.currentTimeMillis();
        
        HttpServletRequest request = getRequest();
        String username = getCurrentUsername();

        OperationAuditLog log = auditLogService.createAuditLog(
                null,
                username,
                auditLog.module(),
                auditLog.operation(),
                auditLog.targetType(),
                null,
                null,
                request
        );

        try {
            Object result = joinPoint.proceed();
            
            long durationMs = System.currentTimeMillis() - startTime;
            log.setDurationMs((int) durationMs);
            log.setResponseStatus(200);
            
            if (result != null && result instanceof Map) {
                Map<?, ?> resultMap = (Map<?, ?>) result;
                Object id = resultMap.get("id");
                if (id != null) {
                    try {
                        log.setTargetId(Long.valueOf(id.toString()));
                    } catch (NumberFormatException e) {
                        logger.debug("无法解析目标ID: {}", id);
                    }
                }
                Object name = resultMap.get("name");
                if (name != null) {
                    log.setTargetName(name.toString());
                }
            }
            
            log.setRemark("操作成功");
            auditLogService.saveAuditLog(log);
            
            return result;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.setDurationMs((int) durationMs);
            log.setResponseStatus(500);
            log.setRemark("操作失败: " + e.getMessage());
            auditLogService.saveAuditLog(log);
            throw e;
        }
    }

    private HttpServletRequest getRequest() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            return attributes.getRequest();
        }
        return null;
    }

    private String getCurrentUsername() {
        try {
            HttpServletRequest request = getRequest();
            if (request != null) {
                Object principal = request.getUserPrincipal();
                if (principal != null) {
                    if (principal instanceof java.security.Principal) {
                        return ((java.security.Principal) principal).getName();
                    }
                    return principal.toString();
                }
            }
        } catch (Exception e) {
            logger.debug("获取当前用户名失败", e);
        }
        return "anonymous";
    }
}
