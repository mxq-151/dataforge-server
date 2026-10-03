package com.bigdata.modules.asset.task;

import com.bigdata.modules.asset.service.AssetUsageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 资产使用热度日汇总任务（T+1）。
 *
 * <p>每天 01:30 汇总「昨天」的访问明细：</p>
 * <ol>
 *   <li>把 asset_access_log 按资产聚合进 asset_usage_daily（同一天重复执行是幂等的）；</li>
 *   <li>按当天命中次数回写 column_metadata.hot_score；</li>
 *   <li>回写 table_metadata 上的热度冗余列（近 30 日浏览/读取、收藏数、最近访问时间）。</li>
 * </ol>
 *
 * <h3>为什么选 01:30</h3>
 * <p>审计日志分区维护任务在 02:00 跑。热度汇总放在它之前，避免两个批量任务
 * 在同一时段争抢数据库连接；同时也确保「昨天的数据」在上班前已经算好。</p>
 *
 * <h3>失败策略</h3>
 * <p>整个任务体包在 try/catch 里：批量任务是后台无人值守的，
 * 抛出异常只会让调度线程记一笔栈，不如自己记录清楚并让下一轮补算 ——
 * 汇总本身是幂等的（按日覆盖写），漏掉一天下次执行同样能补上。</p>
 *
 * @author dataforge
 */
@Component
public class AssetUsageRollupTask {

    private static final Logger log = LoggerFactory.getLogger(AssetUsageRollupTask.class);

    @Autowired
    private AssetUsageService assetUsageService;

    /** 单次执行摘要：供定时日志与手动触发接口共用 */
    public record RunResult(LocalDate statDate, int assetCount, int hotUpdated, int tablesUpdated) {
    }

    /**
     * 跑一次资产使用热度汇总：按日聚合访问明细并回写热度冗余列。
     *
     * <p>定时任务与手动触发共用同一入口，保证两条路径口径一致；汇总为幂等覆盖写，
     * 可安全重放。异常向上抛出，由调用方决定记录或返回错误。</p>
     */
    public RunResult runOnce(LocalDate statDate) {
        AssetUsageService.RollupResult result = assetUsageService.rollupDaily(statDate);
        int tables = assetUsageService.refreshTableCounters();
        return new RunResult(statDate, result.assetCount(), result.hotUpdated(), tables);
    }

    /** 每日 01:30 汇总前一天的访问明细并回写热度 */
    @Scheduled(cron = "0 30 1 * * ?")
    public void rollupYesterday() {
        LocalDate statDate = LocalDate.now().minusDays(1);
        try {
            RunResult result = runOnce(statDate);
            log.info("资产热度日汇总任务完成：日期={}, 资产数={}, 字段热度={}, 冗余列回写={}",
                    statDate, result.assetCount(), result.hotUpdated(), result.tablesUpdated());
        } catch (Exception e) {
            log.error("资产热度日汇总任务失败：日期={}（汇总为幂等操作，下次执行会重算补齐）", statDate, e);
        }
    }
}
