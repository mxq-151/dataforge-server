package com.bigdata.modules.system.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.system.entity.OperationAuditLog;
import com.bigdata.modules.system.service.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/system/audit")
public class AuditLogController {

    @Autowired
    private AuditLogService auditLogService;

    @GetMapping("/list")
    public Result<Page<OperationAuditLog>> getList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Integer isSensitive,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        Page<OperationAuditLog> result = auditLogService.getAuditLogList(
                current, size, module, operation, username, isSensitive, startTime, endTime
        );
        return Result.success(result);
    }

    @GetMapping("/{id}")
    public Result<OperationAuditLog> getById(@PathVariable Long id) {
        OperationAuditLog log = auditLogService.getAuditLogById(id);
        return Result.success(log);
    }

    @GetMapping("/modules")
    public Result<List<String>> getModules() {
        List<String> modules = auditLogService.getModules();
        return Result.success(modules);
    }

    @GetMapping("/operations")
    public Result<List<String>> getOperations() {
        List<String> operations = auditLogService.getOperations();
        return Result.success(operations);
    }

    @GetMapping("/statistics")
    public Result<Map<String, Object>> getStatistics(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        Map<String, Object> statistics = auditLogService.getStatistics(startTime, endTime);
        return Result.success(statistics);
    }
}
