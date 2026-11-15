package com.bigdata.common.aspect;

import com.bigdata.common.utils.SecurityUtils;
import com.bigdata.modules.system.entity.OperationAuditLog;
import com.bigdata.modules.system.service.AuditLogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Aspect
@Component
public class AuditLogAspect {

    private static final Logger logger = LoggerFactory.getLogger(AuditLogAspect.class);
    private static final int MAX_PARAM_LENGTH = 2000;
    private static final int MAX_RESPONSE_LENGTH = 1000;
    private static final String MASK = "******";

    /** 字段名命中这些片段（大小写不敏感）即视为敏感信息 */
    private static final String[] SENSITIVE_FIELD_PARTS = {
            "password", "passwd", "pwd", "token", "secret", "credential",
            "accesskey", "secretkey", "privatekey", "apikey",
            "idcard", "idno", "identitycard", "certno"
    };

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private ObjectMapper objectMapper;

    @Pointcut("@annotation(com.bigdata.common.annotation.AuditLog)")
    public void auditLogPointcut() {
    }

    @Around("auditLogPointcut() && @annotation(auditLog)")
    public Object around(ProceedingJoinPoint joinPoint, com.bigdata.common.annotation.AuditLog auditLog) throws Throwable {
        long startTime = System.currentTimeMillis();
        HttpServletRequest request = getRequest();

        Long userId = SecurityUtils.getCurrentUserId();
        String username = SecurityUtils.getCurrentUsername();
        Long tenantId = SecurityUtils.getCurrentTenantId();

        OperationAuditLog log = auditLogService.createAuditLog(
                userId,
                username,
                tenantId,
                null,
                auditLog.module(),
                auditLog.operation(),
                resolveOperationType(request),
                auditLog.targetType(),
                null,
                null,
                request,
                captureRequestParams(joinPoint, request)
        );

        // 记录是否为AI发起的请求
        log.setIsAiRequest(SecurityUtils.isAiRequest() ? 1 : 0);

        try {
            Object result = joinPoint.proceed();

            long durationMs = System.currentTimeMillis() - startTime;
            log.setDurationMs((int) durationMs);
            log.setResponseStatus(200);

            extractTargetInfo(result, log);

            asyncSaveAuditLog(log);
            return result;
        } catch (Throwable e) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.setDurationMs((int) durationMs);
            log.setResponseStatus(500);
            log.setErrorMessage(e.getMessage());
            if (!(e instanceof com.bigdata.common.exception.BusinessException)) {
                log.setExceptionClass(e.getClass().getName());
            }
            asyncSaveAuditLog(log);
            throw e;
        }
    }

    private String resolveOperationType(HttpServletRequest request) {
        if (request == null) return "UNKNOWN";
        String method = request.getMethod();
        switch (method) {
            case "POST": return "CREATE";
            case "PUT": return "UPDATE";
            case "DELETE": return "DELETE";
            case "GET": return "QUERY";
            default: return method.toUpperCase();
        }
    }

    private String captureRequestParams(ProceedingJoinPoint joinPoint, HttpServletRequest request) {
        try {
            Object[] args = joinPoint.getArgs();
            if (args == null || args.length == 0) {
                return null;
            }

            StringBuilder sb = new StringBuilder("{");
            String[] paramNames = getParameterNames(joinPoint);
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(",");
                String name = i < paramNames.length ? paramNames[i] : "arg" + i;
                Object value = maskSensitiveValue(args[i]);
                sb.append("\"").append(name).append("\":").append(objectMapper.writeValueAsString(value));
            }
            sb.append("}");

            String result = sb.toString();
            return result.length() > MAX_PARAM_LENGTH ? result.substring(0, MAX_PARAM_LENGTH) + "...[截断]" : result;
        } catch (Exception e) {
            logger.debug("捕获请求参数失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 入参脱敏。
     *
     * <p>只拦 String 是不够的：接口大多收 DTO，密码/密钥/证件号都在对象字段里，
     * 整个对象被序列化就会明文落进 {@code request_params}。这里把参数转成 JSON 树后
     * 递归按字段名脱敏，嵌套对象、集合、数组都会被覆盖；序列化失败时直接丢弃参数值，
     * 宁可不记录也不能落原文。</p>
     */
    private Object maskSensitiveValue(Object arg) {
        if (arg == null) {
            return null;
        }
        if (arg instanceof CharSequence) {
            String text = arg.toString();
            if (text.length() > 6 && (text.contains("password") || text.contains("secret") || text.contains("token"))) {
                return MASK;
            }
            return text;
        }
        try {
            JsonNode node = objectMapper.valueToTree(arg);
            maskSensitiveFields(node);
            return node;
        } catch (Exception e) {
            logger.debug("参数脱敏失败，已丢弃参数值: {}", e.getMessage());
            return MASK;
        }
    }

    private void maskSensitiveFields(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            List<String> sensitiveFields = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (isSensitiveField(entry.getKey())) {
                    sensitiveFields.add(entry.getKey());
                } else {
                    maskSensitiveFields(entry.getValue());
                }
            }
            sensitiveFields.forEach(name -> objectNode.put(name, MASK));
            return;
        }
        if (node.isArray()) {
            node.forEach(this::maskSensitiveFields);
        }
    }

    private boolean isSensitiveField(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        String lower = fieldName.toLowerCase(Locale.ROOT);
        for (String sensitivePart : SENSITIVE_FIELD_PARTS) {
            if (lower.contains(sensitivePart)) {
                return true;
            }
        }
        return false;
    }

    private void extractTargetInfo(Object result, OperationAuditLog log) {
        if (result == null) return;
        try {
            if (result instanceof com.bigdata.common.vo.Result) {
                Object data = ((com.bigdata.common.vo.Result<?>) result).getData();
                if (data != null) {
                    String json = objectMapper.writeValueAsString(data);
                    if (json.length() > MAX_RESPONSE_LENGTH) {
                        json = json.substring(0, MAX_RESPONSE_LENGTH) + "...[截断]";
                    }
                    log.setResponseBody(json);
                }
            }
        } catch (Exception e) {
            logger.debug("提取响应信息失败: {}", e.getMessage());
        }
    }

    private String[] getParameterNames(ProceedingJoinPoint joinPoint) {
        try {
            org.aspectj.lang.reflect.MethodSignature methodSig = (org.aspectj.lang.reflect.MethodSignature) joinPoint.getSignature();
            java.lang.reflect.Parameter[] params = methodSig.getMethod().getParameters();
            String[] names = new String[params.length];
            for (int i = 0; i < params.length; i++) {
                names[i] = params[i].getName();
            }
            return names;
        } catch (Exception e) {
            return new String[0];
        }
    }

    @Async
    protected void asyncSaveAuditLog(OperationAuditLog auditLog) {
        try {
            auditLogService.saveAuditLog(auditLog);
        } catch (Exception e) {
            logger.error("异步保存审计日志失败", e);
        }
    }

    private HttpServletRequest getRequest() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes != null ? attributes.getRequest() : null;
    }
}
