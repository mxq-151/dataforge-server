package com.bigdata.modules.integration.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.integration.dto.IntegrationParamsDTO;
import com.bigdata.modules.integration.dto.IntegrationTaskDTO;
import com.bigdata.modules.integration.service.IntegrationTaskService;
import com.bigdata.modules.integration.vo.IntegrationOptionsVO;
import com.bigdata.modules.integration.vo.IntegrationSubmitLogVO;
import com.bigdata.modules.integration.vo.IntegrationSubmitResultVO;
import com.bigdata.modules.integration.vo.IntegrationTaskStatusVO;
import com.bigdata.modules.integration.vo.IntegrationTaskVO;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据集成 REST 接口。
 *
 * <p>所有集群操作都经由后端适配层完成，前端不直连 Flink。
 * 响应统一使用 {@link Result} 包装：{ success, code, message, data }。</p>
 */
@RestController
@RequestMapping("/integration")
public class IntegrationTaskController {

    @Autowired
    private IntegrationTaskService integrationTaskService;

    /** 任务列表（支持关键字、状态、集群、源/目标类型筛选 + 分页） */
    @GetMapping("/tasks")
    public Result<Page<IntegrationTaskVO>> list(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String clusterType,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String targetType) {
        return Result.success(integrationTaskService.page(current, size, keyword, status,
                clusterType, sourceType, targetType));
    }

    @GetMapping("/tasks/{id}")
    public Result<IntegrationTaskVO> detail(@PathVariable Long id) {
        return Result.success(integrationTaskService.detail(id));
    }

    @PostMapping("/tasks")
    public Result<IntegrationTaskVO> create(@Valid @RequestBody IntegrationTaskDTO dto) {
        return Result.success(integrationTaskService.create(dto));
    }

    @PutMapping("/tasks")
    public Result<IntegrationTaskVO> update(@Valid @RequestBody IntegrationTaskDTO dto) {
        return Result.success(integrationTaskService.update(dto));
    }

    @DeleteMapping("/tasks/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        integrationTaskService.delete(id);
        return Result.success();
    }

    /** 动态更新参数：不重建任务，下次上线生效 */
    @PutMapping("/tasks/{id}/params")
    public Result<IntegrationTaskVO> updateParams(@PathVariable Long id,
                                                  @Valid @RequestBody IntegrationParamsDTO dto) {
        return Result.success(integrationTaskService.updateParams(id, dto));
    }

    /** 上线：提交到集群，落库 jobId 与提交时间 */
    @PostMapping("/tasks/{id}/submit")
    public Result<IntegrationSubmitResultVO> submit(@PathVariable Long id,
                                                    @RequestBody(required = false) IntegrationParamsDTO overrides) {
        return Result.success(integrationTaskService.submit(id, overrides));
    }

    /** 下线：调用集群取消/停止接口并更新状态 */
    @PostMapping("/tasks/{id}/offline")
    public Result<IntegrationSubmitResultVO> offline(@PathVariable Long id) {
        return Result.success(integrationTaskService.offline(id));
    }

    /** 查询集群实时运行状态（集群不可达时 clusterReachable=false 并带原因） */
    @GetMapping("/tasks/{id}/status")
    public Result<IntegrationTaskStatusVO> status(@PathVariable Long id) {
        return Result.success(integrationTaskService.refreshStatus(id));
    }

    /** 提交历史 */
    @GetMapping("/tasks/{id}/logs")
    public Result<List<IntegrationSubmitLogVO>> logs(@PathVariable Long id,
                                                     @RequestParam(defaultValue = "20") int limit) {
        return Result.success(integrationTaskService.logs(id, limit));
    }

    /** 单次提交的完整日志 */
    @GetMapping("/logs/{logId}")
    public Result<IntegrationSubmitLogVO> logDetail(@PathVariable Long logId) {
        return Result.success(integrationTaskService.logDetail(logId));
    }

    /** 数据源类型、集群类型、状态等下拉选项 + 动态表单字段描述 */
    @GetMapping("/options")
    public Result<IntegrationOptionsVO> options() {
        return Result.success(integrationTaskService.options());
    }
}
