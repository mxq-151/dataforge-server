package com.bigdata.modules.asset.task;

import com.bigdata.modules.asset.service.AssetPartitionSizeCollectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 分区大小采集任务（T+1）。
 *
 * <p>每天 02:20 采集「昨天」的天分区表最新分区大小，并清理超过保留期的历史。</p>
 *
 * <h3>为什么选 02:20</h3>
 * <p>采集依赖元数据同步与存储大小刷新（02:15）已完成的 {@code partition_columns}；
 * 排在 02:15 之后、目录快照 02:30 之前，避免批量任务同时段争抢数据库连接。</p>
 *
 * <h3>失败策略</h3>
 * <p>整个任务体包在 try/catch 里：批量任务后台无人值守，抛出异常只会让调度线程记一笔栈。
 * 采集本身是幂等的（按 {@code stat_date} 覆盖写），下一轮执行会补算。</p>
 *
 * @author dataforge
 */
@Component
public class AssetPartitionSizeCollectTask {

    private static final Logger log = LoggerFactory.getLogger(AssetPartitionSizeCollectTask.class);

    @Autowired
    private AssetPartitionSizeCollectService collectService;

    /** 单次执行摘要：供定时日志与手动触发接口共用 */
    public record RunResult(LocalDate statDate, int partitionTableCount, int rowCount, int purged) {
    }

    /**
     * 跑一次分区大小采集：采集指定统计日数据并清理超期历史。
     *
     * <p>定时任务与手动触发共用同一入口，保证两条路径口径一致；采集为幂等覆盖写，可安全重放。</p>
     */
    public RunResult runOnce(LocalDate statDate) {
        AssetPartitionSizeCollectService.CollectResult result = collectService.collect(statDate);
        int purged = collectService.purgeBefore(
                statDate.minusDays(AssetPartitionSizeCollectService.RETENTION_DAYS));
        return new RunResult(statDate, result.partitionTableCount(), result.rowCount(), purged);
    }

    /** 每日 02:20 采集前一天的天分区表最新分区大小，并清理超期历史 */
    @Scheduled(cron = "0 20 2 * * ?")
    public void collectYesterday() {
        LocalDate statDate = LocalDate.now().minusDays(1);
        try {
            RunResult result = runOnce(statDate);
            log.info("分区大小采集任务完成：日期={}, 天分区表数={}, 落库行数={}, 清理={}",
                    statDate, result.partitionTableCount(), result.rowCount(), result.purged());
        } catch (Exception e) {
            log.error("分区大小采集任务失败：日期={}（采集为幂等操作，下次执行会重算补齐）", statDate, e);
        }
    }
}
