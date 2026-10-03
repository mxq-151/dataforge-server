package com.bigdata.modules.metadata.task;

import com.bigdata.modules.metadata.service.TableStorageSizeRefreshService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 表存储大小每日刷新任务。
 *
 * <p>每天 02:15 遍历全部数据源，从各引擎系统表采集「表 → 存储大小」并覆盖写
 * {@code table_metadata.size_bytes}。</p>
 *
 * <h3>为什么选 02:15</h3>
 * <p>排在资产目录快照（02:30）之前，保证当日快照与「数据大小增长率」看板读到最新值；
 * 又避开审计日志分区维护（02:00）与整点批次，减少同库连接争抢。未纳管的表与系统库
 * 不参与刷新（与全量同步同口径）。</p>
 *
 * <h3>失败策略</h3>
 * <p>整任务包在 try/catch 里，逐数据源隔离失败：批量任务后台无人值守，抛异常只会让
 * 调度线程记一笔栈。刷新是覆盖写且幂等，下一轮执行会补齐。</p>
 *
 * @author dataforge
 */
@Component
public class TableStorageSizeRefreshTask {

    private static final Logger log = LoggerFactory.getLogger(TableStorageSizeRefreshTask.class);

    @Autowired
    private TableStorageSizeRefreshService refreshService;

    /** 每日 02:15 刷新全部纳管表的存储大小 */
    @Scheduled(cron = "0 15 2 * * ?")
    public void refreshDaily() {
        try {
            TableStorageSizeRefreshService.RefreshResult result = refreshService.refreshAll();
            log.info("表存储大小刷新任务完成：数据源={}, 更新表数={}, 失败数据源={}",
                    result.datasourceCount(), result.updatedTables(), result.failedDatasources());
        } catch (Exception e) {
            log.error("表存储大小刷新任务失败（覆盖写幂等，下次执行会补齐）", e);
        }
    }
}