package com.bigdata.modules.asset.task;

import com.bigdata.modules.asset.service.AssetCatalogSnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 资产目录日快照任务（T+1）。
 *
 * <p>每天 02:30 生成「昨天」的资产目录快照，并清理超过保留期的历史快照。</p>
 *
 * <h3>为什么选 02:30</h3>
 * <p>快照里的使用热度来自 {@code table_metadata} 的冗余列，而该列由
 * {@link AssetUsageRollupTask}（01:30）回写；审计日志分区维护任务在 02:00。
 * 排在两者之后，既保证读到已回写的热度，也避开批量任务同时段争抢数据库连接。</p>
 *
 * <h3>失败策略</h3>
 * <p>整个任务体包在 try/catch 里：批量任务后台无人值守，抛出异常只会让调度线程
 * 记一笔栈。生成本身是幂等的（按 {@code stat_date} 覆盖写），下一轮执行会补算。</p>
 *
 * @author dataforge
 */
@Component
public class AssetCatalogSnapshotTask {

    private static final Logger log = LoggerFactory.getLogger(AssetCatalogSnapshotTask.class);

    @Autowired
    private AssetCatalogSnapshotService snapshotService;

    /** 单次执行摘要：供定时日志与手动触发接口共用 */
    public record RunResult(LocalDate statDate, int tableCount, int purged) {
    }

    /**
     * 跑一次资产目录快照：生成指定统计日快照并清理超期历史。
     *
     * <p>定时任务与手动触发共用同一入口，保证两条路径口径一致；生成为幂等覆盖写，
     * 可安全重放。异常向上抛出，由调用方决定记录或返回错误。</p>
     */
    public RunResult runOnce(LocalDate statDate) {
        AssetCatalogSnapshotService.SnapshotResult result = snapshotService.snapshot(statDate);
        int purged = snapshotService.purgeBefore(
                statDate.minusDays(AssetCatalogSnapshotService.RETENTION_DAYS));
        return new RunResult(statDate, result.tableCount(), purged);
    }

    /** 每日 02:30 生成前一天的资产目录快照，并清理超过保留期的历史 */
    @Scheduled(cron = "0 30 2 * * ?")
    public void snapshotYesterday() {
        LocalDate statDate = LocalDate.now().minusDays(1);
        try {
            RunResult result = runOnce(statDate);
            log.info("资产目录快照任务完成：日期={}, 资产数={}, 清理={}",
                    statDate, result.tableCount(), result.purged());
        } catch (Exception e) {
            log.error("资产目录快照任务失败：日期={}（生成为幂等操作，下次执行会重算补齐）", statDate, e);
        }
    }
}
