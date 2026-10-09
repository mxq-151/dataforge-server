package com.bigdata.modules.quality.controller;

import com.bigdata.common.vo.Result;
import com.bigdata.modules.quality.service.DqOverviewService;
import com.bigdata.modules.quality.vo.QualityOverviewVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 质量大盘 Controller。
 *
 * <p>对应前端 {@code api/quality.ts} 的 {@code /api/quality/overview}。</p>
 *
 * <p>告警订阅相关的三个端点（{@code GET/POST /quality/alert}、{@code GET /quality/alert/log}）
 * 已随前端「告警订阅」页签一并删除。</p>
 *
 * <p>{@code projectCode} 参数已移除：项目归属原先只能通过「被哪个项目的质检任务引用」得到，
 * 而质检任务链路已删除，该参数再也无法生效 —— 留着会变成一个静默无效的筛选条件。</p>
 *
 * @author dataforge
 */
@RestController
@RequestMapping("/quality")
public class DqOverviewController {

    @Autowired
    private DqOverviewService dqOverviewService;

    @GetMapping("/overview")
    public Result<QualityOverviewVO> overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return Result.success(dqOverviewService.getOverview(startDate, endDate));
    }
}
