package com.bigdata.modules.quality.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.quality.service.DqAlertService;
import com.bigdata.modules.quality.service.DqOverviewService;
import com.bigdata.modules.quality.vo.QualityAlertSubscriptionVO;
import com.bigdata.modules.quality.vo.QualityOverviewVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 质量大盘与告警订阅 Controller。
 *
 * <p>对应前端 {@code api/quality.ts} 的 {@code /api/quality/overview} 与
 * {@code /api/quality/alert}。大盘与告警都是「读多写少」的配置面，
 * 因此合并在一个控制器里，避免为两个端点各起一个类。</p>
 */
@RestController
@RequestMapping("/quality")
public class DqOverviewController {

    @Autowired
    private DqOverviewService dqOverviewService;

    @Autowired
    private DqAlertService dqAlertService;

    @GetMapping("/overview")
    public Result<QualityOverviewVO> overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String projectCode) {
        return Result.success(dqOverviewService.getOverview(startDate, endDate, projectCode));
    }

    @GetMapping("/alert")
    public Result<Page<QualityAlertSubscriptionVO>> listAlertSubscriptions(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long taskId) {
        return Result.success(dqAlertService.page(current, size, taskId));
    }

    @PostMapping("/alert")
    public Result<Void> saveAlertSubscription(@RequestBody QualityAlertSubscriptionVO vo) {
        dqAlertService.save(vo);
        return Result.success();
    }
}
