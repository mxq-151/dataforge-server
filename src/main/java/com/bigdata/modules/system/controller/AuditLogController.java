package com.bigdata.modules.system.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.utils.SecurityUtils;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.system.entity.OperationAuditLog;
import com.bigdata.modules.system.service.AuditLogService;
import com.bigdata.modules.system.service.SysTenantService;
import com.bigdata.modules.system.vo.AuditStatisticsVO;
import com.bigdata.modules.system.vo.OperationAuditLogVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@RequestMapping("/system/audit-log")
public class AuditLogController {

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private SysTenantService sysTenantService;

    @GetMapping("/list")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Page<OperationAuditLogVO>> getAuditLogList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Integer isSensitive,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {

        Long tenantId = null;
        if (!SecurityUtils.isAdmin()) {
            tenantId = SecurityUtils.getCurrentTenantId();
        }

        Page<OperationAuditLog> page = auditLogService.getAuditLogList(
                current, size, module, operation, username,
                tenantId, isSensitive, operationType, startTime, endTime);

        Page<OperationAuditLogVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVO).toList());
        return Result.success(voPage);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<OperationAuditLogVO> getAuditLogDetail(@PathVariable Long id) {
        OperationAuditLog log = auditLogService.getAuditLogById(id);
        if (log == null) {
            return Result.error(404, "审计日志不存在");
        }
        return Result.success(toVO(log));
    }

    @GetMapping("/modules")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<String>> getModules() {
        List<String> modules = auditLogService.getModules();
        return Result.success(modules);
    }

    @GetMapping("/operations")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<String>> getOperations() {
        List<String> operations = auditLogService.getOperations();
        return Result.success(operations);
    }

    @GetMapping("/operation-types")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<String>> getOperationTypes() {
        List<String> types = auditLogService.getOperationTypes();
        return Result.success(types);
    }

    @GetMapping("/statistics")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<AuditStatisticsVO> getStatistics(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {

        if (startTime == null) {
            startTime = LocalDateTime.now().minusDays(30);
        }
        if (endTime == null) {
            endTime = LocalDateTime.now();
        }
        AuditStatisticsVO statistics = auditLogService.getStatistics(startTime, endTime);
        return Result.success(statistics);
    }

    @GetMapping("/export")
    @PreAuthorize("hasRole('ADMIN')")
    public void exportAuditLogs(
            HttpServletResponse response,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Integer isSensitive,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) throws IOException {

        Long tenantId = SecurityUtils.isAdmin() ? null : SecurityUtils.getCurrentTenantId();

        Page<OperationAuditLog> page = auditLogService.getAuditLogList(
                1, 10000, module, operation, username,
                tenantId, isSensitive, operationType, startTime, endTime);

        response.setContentType("text/csv;charset=UTF-8");
        String fileName = URLEncoder.encode("audit_log_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".csv", StandardCharsets.UTF_8);
        response.setHeader("Content-Disposition", "attachment; filename=" + fileName);

        StringBuilder sb = new StringBuilder();
        sb.append("\uFEFF");
        sb.append("ID,用户名,租户名称,模块,操作,操作类型,目标对象,目标名称,请求方法,请求URL,IP地址,响应状态,耗时(ms),操作时间,错误信息\n");

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        for (OperationAuditLog log : page.getRecords()) {
            sb.append(log.getId()).append(",");
            sb.append(csvField(log.getUsername())).append(",");
            sb.append(csvField(log.getTenantName())).append(",");
            sb.append(csvField(log.getModule())).append(",");
            sb.append(csvField(log.getOperation())).append(",");
            sb.append(csvField(log.getOperationType())).append(",");
            sb.append(csvField(log.getTargetType())).append(",");
            sb.append(csvField(log.getTargetName())).append(",");
            sb.append(csvField(log.getRequestMethod())).append(",");
            sb.append(csvField(log.getRequestUrl())).append(",");
            sb.append(csvField(log.getIpAddress())).append(",");
            sb.append(log.getResponseStatus()).append(",");
            sb.append(log.getDurationMs()).append(",");
            if (log.getOperationTime() != null) {
                sb.append(log.getOperationTime().format(fmt));
            }
            sb.append(",");
            sb.append(csvField(log.getErrorMessage())).append("\n");
        }

        response.getWriter().write(sb.toString());
    }

    @DeleteMapping("/cleanup")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Integer> cleanupOldLogs(@RequestParam(defaultValue = "90") int days) {
        LocalDateTime beforeTime = LocalDateTime.now().minusDays(days);
        int deletedCount = auditLogService.deleteLogsBefore(beforeTime);
        return Result.success(deletedCount);
    }

    private OperationAuditLogVO toVO(OperationAuditLog entity) {
        OperationAuditLogVO vo = new OperationAuditLogVO();
        vo.setId(entity.getId());
        vo.setUserId(entity.getUserId());
        vo.setUsername(entity.getUsername());
        vo.setTenantId(entity.getTenantId());
        vo.setTenantName(entity.getTenantName());
        vo.setModule(entity.getModule());
        vo.setOperation(entity.getOperation());
        vo.setOperationType(entity.getOperationType());
        vo.setTargetType(entity.getTargetType());
        vo.setTargetId(entity.getTargetId());
        vo.setTargetName(entity.getTargetName());
        vo.setRequestMethod(entity.getRequestMethod());
        vo.setRequestUrl(entity.getRequestUrl());
        vo.setRequestParams(entity.getRequestParams());
        vo.setResponseStatus(entity.getResponseStatus());
        vo.setErrorMessage(entity.getErrorMessage());
        vo.setIpAddress(entity.getIpAddress());
        vo.setUserAgent(entity.getUserAgent());
        vo.setOperationTime(entity.getOperationTime());
        vo.setDurationMs(entity.getDurationMs());
        vo.setIsSensitive(entity.getIsSensitive());
        return vo;
    }

    private String csvField(String value) {
        if (value == null) return "";
        value = value.replace("\"", "\"\"");
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value + "\"";
        }
        return value;
    }
}
