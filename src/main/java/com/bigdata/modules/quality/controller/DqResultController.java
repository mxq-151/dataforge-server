package com.bigdata.modules.quality.controller;

import com.bigdata.common.vo.Result;
import com.bigdata.modules.quality.dto.QualityResultReportDTO;
import com.bigdata.modules.quality.service.DqResultService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 规则级校验结果回传 Controller（程序调用，非界面）。
 *
 * <p><b>路径刻意沿用历史的 {@code /quality/run/report}</b>：它写在智能体提示词的
 * 「质检结果回传规范」里，并由运行环境以 {@code DATAFORGE_QUALITY_REPORT_URL}
 * 注入到 S3 侧程序。改名会让所有已部署环境的回传地址失效，而这里的语义
 * （「把一次校验结果报回平台」）并没有因为运行实例链路被移除而改变。</p>
 *
 * <p>平台侧的查询入口（运行记录 / 告警订阅 / 质检任务）已随对应页签一并删除，
 * 因此本控制器只有一个写入端点。</p>
 *
 * @author dataforge
 */
@RestController
@RequestMapping("/quality/run")
public class DqResultController {

    @Autowired
    private DqResultService dqResultService;

    /** 结果上报入口：S3 侧校验任务回传结果用（程序调用，非界面） */
    @PostMapping("/report")
    public Result<Void> report(@RequestBody QualityResultReportDTO dto) {
        dqResultService.reportResult(dto);
        return Result.success();
    }
}
