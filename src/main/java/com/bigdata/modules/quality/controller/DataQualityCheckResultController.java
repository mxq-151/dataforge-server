package com.bigdata.modules.quality.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.quality.dto.DataQualityCheckResultDTO;
import com.bigdata.modules.quality.entity.DataQualityCheckResult;
import com.bigdata.modules.quality.service.DataQualityCheckResultService;
import com.bigdata.modules.quality.vo.DataQualityCheckResultVO;
import com.bigdata.modules.quality.vo.DataQualityFailStatisticVO;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 数据质量校验结果 Controller
 */
@RestController
@RequestMapping("/quality/check-result")
public class DataQualityCheckResultController {

    @Autowired
    private DataQualityCheckResultService dataQualityCheckResultService;

    /**
     * 分页查询校验结果
     *
     * <p>兼容接口：保留既有 checkItem 模糊匹配（历史自由文本数据仍在用），
     * 同时新增 ruleType 精确过滤，让收敛后的 7 类规则也能按类型筛选。</p>
     */
    @GetMapping("/list")
    public Result<Page<DataQualityCheckResultVO>> getList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(required = false) String databaseName,
            @RequestParam(required = false) String tableName,
            @RequestParam(required = false) String checkItem,
            @RequestParam(required = false) String ruleType,
            @RequestParam(required = false) Boolean failOnly) {
        Page<DataQualityCheckResult> page = dataQualityCheckResultService.getResultPage(
                current, size, datasourceId, databaseName, tableName, checkItem, ruleType, failOnly);
        Page<DataQualityCheckResultVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVO).collect(Collectors.toList()));
        return Result.success(voPage);
    }

    @GetMapping("/{id}")
    public Result<DataQualityCheckResultVO> getById(@PathVariable Long id) {
        DataQualityCheckResult result = dataQualityCheckResultService.getResultById(id);
        return Result.success(toVO(result));
    }

    @PostMapping
    public Result<Void> save(@Valid @RequestBody DataQualityCheckResultDTO dto) {
        DataQualityCheckResult result = new DataQualityCheckResult();
        BeanUtils.copyProperties(dto, result);
        dataQualityCheckResultService.saveResult(result);
        return Result.success();
    }

    @PutMapping
    public Result<Void> update(@Valid @RequestBody DataQualityCheckResultDTO dto) {
        DataQualityCheckResult result = new DataQualityCheckResult();
        BeanUtils.copyProperties(dto, result);
        dataQualityCheckResultService.updateResult(result);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        dataQualityCheckResultService.deleteResult(id);
        return Result.success();
    }

    /**
     * 检查失败记录统计：按 表 + 日期 + 检查项 聚合未通过记录数
     */
    @GetMapping("/statistics/fail")
    public Result<List<DataQualityFailStatisticVO>> getFailStatistic(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(required = false) String databaseName,
            @RequestParam(required = false) String tableName) {
        return Result.success(dataQualityCheckResultService.getFailStatistic(
                startDate, endDate, datasourceId, databaseName, tableName));
    }

    private DataQualityCheckResultVO toVO(DataQualityCheckResult entity) {
        DataQualityCheckResultVO vo = new DataQualityCheckResultVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
