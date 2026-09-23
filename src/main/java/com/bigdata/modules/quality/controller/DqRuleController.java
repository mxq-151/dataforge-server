package com.bigdata.modules.quality.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.quality.dto.QualityBatchByTemplateDTO;
import com.bigdata.modules.quality.dto.QualityRuleSaveDTO;
import com.bigdata.modules.quality.dto.QualityTestRunDTO;
import com.bigdata.modules.quality.service.DqRuleService;
import com.bigdata.modules.quality.vo.QualityRuleResultVO;
import com.bigdata.modules.quality.vo.QualityRuleVO;
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

import java.util.Map;

/**
 * 监控规则 Controller。
 *
 * <p>对应前端 {@code api/quality.ts} 的 {@code /api/quality/rule/*} 系列。</p>
 */
@RestController
@RequestMapping("/quality/rule")
public class DqRuleController {

    @Autowired
    private DqRuleService dqRuleService;

    @GetMapping
    public Result<Page<QualityRuleVO>> page(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(required = false) String databaseName,
            @RequestParam(required = false) String tableName,
            @RequestParam(required = false) String columnName,
            @RequestParam(required = false) String ruleType,
            @RequestParam(required = false) Boolean enabled) {
        return Result.success(dqRuleService.page(current, size, datasourceId, databaseName,
                tableName, columnName, ruleType, enabled));
    }

    @PostMapping
    public Result<Void> create(@RequestBody QualityRuleSaveDTO dto) {
        dqRuleService.create(dto);
        return Result.success();
    }

    @PutMapping
    public Result<Void> update(@RequestBody QualityRuleSaveDTO dto) {
        dqRuleService.update(dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        dqRuleService.delete(id);
        return Result.success();
    }

    /** 规则试跑：不落运行实例，只回结果，用于保存前验证阈值是否合理 */
    @PostMapping("/test-run")
    public Result<QualityRuleResultVO> testRun(@RequestBody QualityTestRunDTO dto) {
        return Result.success(dqRuleService.testRun(dto));
    }

    /** 按模板批量建规则（对齐 DataWorks「按模板批量配置」） */
    @PostMapping("/batch-by-template")
    public Result<Map<String, Integer>> batchCreateByTemplate(@RequestBody QualityBatchByTemplateDTO dto) {
        return Result.success(dqRuleService.batchCreateByTemplate(dto));
    }
}
