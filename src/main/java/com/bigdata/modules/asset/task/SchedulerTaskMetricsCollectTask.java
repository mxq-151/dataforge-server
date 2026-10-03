package com.bigdata.modules.asset.task;

import com.bigdata.modules.asset.service.SchedulerTaskMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 调度任务指标采集任务（T+1）。
 *
 * <p>每天 03:30 采集「近 30 天」的 DolphinScheduler 运行记录，按日聚合写入
 * {@code scheduler_task_run_daily}，并清理超过保留期的历史。</p>
 *
 * <h3>为什么重拉 30 天而不是只拉昨天</h3>
 * <p>覆盖写窗口内的数据使采集天然幂等：漏跑一天、上游临时不可用、甚至手工重放，
 * 下一次执行都会把窗口内的数据重算成一致结果，不需要补偿脚本。</p>
 *
 * <h3>为什么选 03:30</h3>
 * <p>热度汇总 01:30、审计分区维护 02:00（每月 1 日 03:00）、资产目录快照 02:30。
 * 排在它们之后，既有足够时间窗口让调度系统的运行记录落定，也避开批量任务同时段争抢资源。</p>
 *
 * <h3>失败策略</h3>
 * <p>整个任务体包在 try/catch 里：采集是无人值守的批量任务，抛异常只会让调度线程记一笔栈。
 * 调度系统未配置时服务层会直接跳过（不报错），属于预期状态。</p>
 *
 * @author dataforge
 */
@Component
public class SchedulerTaskMetricsCollectTask {

    private static final Logger log = LoggerFactory.getLogger(SchedulerTaskMetricsCollectTask.class);

    @Autowired
    private SchedulerTaskMetricsService metricsService;

    /**
     * 跑一次调度指标采集：重拉近 30 天运行记录并按日覆盖写。
     *
     * <p>定时任务与手动触发共用同一入口，保证两条路径口径一致（幂等，可安全重放）。
     * 返回的 {@code skippedReason} 非空表示调度系统未配置，属预期跳过。</p>
     */
    public SchedulerTaskMetricsService.CollectResult runOnce(LocalDate anchorDate) {
        return metricsService.collect(anchorDate);
    }

    /** 每日 03:30 采集近 30 天调度运行记录并清理超期历史 */
    @Scheduled(cron = "0 30 3 * * ?")
    public void collectYesterday() {
        LocalDate anchorDate = LocalDate.now().minusDays(1);
        try {
            SchedulerTaskMetricsService.CollectResult result = runOnce(anchorDate);
            if (result.skippedReason() != null) {
                log.info("调度任务指标采集跳过：日期={}, 原因={}", anchorDate, result.skippedReason());
                return;
            }
            log.info("调度任务指标采集任务完成：日期={}, 项目={}, 任务实例={}, 聚合行={}",
                    anchorDate, result.projectCount(), result.taskInstanceCount(), result.rowCount());
        } catch (Exception e) {
            log.error("调度任务指标采集任务失败：日期={}（采集为幂等覆盖写，下次执行会重算补齐）", anchorDate, e);
        }
    }
}
