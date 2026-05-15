package com.bigdata.modules.system.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.system.entity.OperationAuditLog;
import com.bigdata.modules.system.service.AuditLogService;
import com.bigdata.modules.system.vo.AuditStatisticsVO;
import com.bigdata.modules.system.vo.OperationAuditLogVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/system/audit")
public class AuditLogController {

    @Autowired
    private AuditLogService auditLogService;

    @GetMapping("/list")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Page<OperationAuditLogVO>> getList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Integer isSensitive,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        Page<OperationAuditLog> page = auditLogService.getAuditLogList(
                current, size, module, operation, username, isSensitive, startTime, endTime
        );
        Page<OperationAuditLogVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVO).collect(Collectors.toList()));
        return Result.success(voPage);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<OperationAuditLogVO> getById(@PathVariable Long id) {
        OperationAuditLog log = auditLogService.getAuditLogById(id);
        return Result.success(toVO(log));
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
    @PreAuthorize("hasRole('ADMIN')")
    public Result<AuditStatisticsVO> getStatistics(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        AuditStatisticsVO statistics = auditLogService.getStatistics(startTime, endTime);
        return Result.success(statistics);
    }

    private OperationAuditLogVO toVO(OperationAuditLog entity) {
        OperationAuditLogVO vo = new OperationAuditLogVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
