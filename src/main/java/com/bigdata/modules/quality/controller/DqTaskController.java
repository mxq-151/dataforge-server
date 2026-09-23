package com.bigdata.modules.quality.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.quality.dto.QualityTaskSaveDTO;
import com.bigdata.modules.quality.service.DqRuleService;
import com.bigdata.modules.quality.service.DqTaskService;
import com.bigdata.modules.quality.vo.QualityRuleVO;
import com.bigdata.modules.quality.vo.QualityTaskVO;
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
 * 质检任务 Controller。
 *
 * <p>对应前端 {@code api/quality.ts} 的 {@code /api/quality/task/*} 系列。</p>
 */
@RestController
@RequestMapping("/quality/task")
public class DqTaskController {

    @Autowired
    private DqTaskService dqTaskService;

    @Autowired
    private DqRuleService dqRuleService;

    @GetMapping
    public Result<Page<QualityTaskVO>> page(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String triggerType,
            @RequestParam(required = false) Boolean enabled) {
        return Result.success(dqTaskService.page(current, size, keyword, triggerType, enabled));
    }

    @PostMapping
    public Result<Void> create(@RequestBody QualityTaskSaveDTO dto) {
        dqTaskService.create(dto);
        return Result.success();
    }

    @PutMapping
    public Result<Void> update(@RequestBody QualityTaskSaveDTO dto) {
        dqTaskService.update(dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        dqTaskService.delete(id);
        return Result.success();
    }

    /** 任务挂载的规则 */
    @GetMapping("/{id}/rules")
    public Result<List<QualityRuleVO>> listTaskRules(@PathVariable Long id) {
        // 先校验任务存在，避免对不存在的任务返回空数组造成「任务没挂规则」的误判
        dqTaskService.getById(id);
        return Result.success(dqRuleService.listByTask(id));
    }

    /** 手动触发：返回运行实例 id，用于随后轮询运行结果 */
    @PostMapping("/{id}/run")
    public Result<Long> run(@PathVariable Long id) {
        return Result.success(dqTaskService.run(id));
    }

    @PostMapping("/{id}/enable")
    public Result<Void> setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        dqTaskService.setEnabled(id, enabled);
        return Result.success();
    }
}
