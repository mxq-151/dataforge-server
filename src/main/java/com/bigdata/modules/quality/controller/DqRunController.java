package com.bigdata.modules.quality.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.quality.dto.QualityRunReportDTO;
import com.bigdata.modules.quality.service.DqRunService;
import com.bigdata.modules.quality.vo.QualityRuleResultVO;
import com.bigdata.modules.quality.vo.QualityRunInstanceVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 运行记录 Controller。
 *
 * <p>对应前端 {@code api/quality.ts} 的 {@code /api/quality/run/*} 系列。</p>
 */
@RestController
@RequestMapping("/quality/run")
public class DqRunController {

    @Autowired
    private DqRunService dqRunService;

    @GetMapping
    public Result<Page<QualityRunInstanceVO>> page(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) String batchNo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return Result.success(dqRunService.page(current, size, taskId, batchNo, status, startDate, endDate));
    }

    @GetMapping("/{id}")
    public Result<QualityRunInstanceVO> get(@PathVariable Long id) {
        return Result.success(DqRunService.toVO(dqRunService.getById(id)));
    }

    /** 规则级结果：满足提示词「规则名/校验对象/阈值/实际值/是否通过」五要素 */
    @GetMapping("/{id}/results")
    public Result<List<QualityRuleResultVO>> listResults(@PathVariable Long id) {
        dqRunService.getById(id);
        return Result.success(dqRunService.listResults(id));
    }

    /** 结果上报入口：S3 侧校验任务回传结果用（程序调用，非界面） */
    @PostMapping("/report")
    public Result<Void> report(@RequestBody QualityRunReportDTO dto) {
        dqRunService.reportRun(dto);
        return Result.success();
    }
}
