package com.bigdata.modules.asset.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bigdata.modules.asset.service.AssetCatalogSnapshotService;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 资产日度任务启动补偿 —— 让「任务跑在数据之前」这种情况能自愈。
 *
 * <h3>要解决的问题</h3>
 * <p>目录快照（02:30）是 T+1 定时任务，只跑一次、不重试。
 * 而它依赖的元数据可能<b>晚于任务时间</b>才出现：</p>
 * <ul>
 *   <li>元数据依赖人工/首次触发的全量同步（新环境或重建库时往往在白天才同步）；</li>
 *   <li>容器重启后，进程在本该执行任务的时刻根本没在运行。</li>
 * </ul>
 * <p>结果就是任务在「零资产」状态下安静地跑完：<b>既没有报错，也没有留下任何快照</b>，
 * 依赖快照的看板（数据大小增长率）于是长期空着，
 * 让人以为是功能对接有问题。</p>
 *
 * <h3>补偿口径：只补「从未产出」，不覆盖已有</h3>
 * <p>只有目标表完全是空的（从未成功产出过任何统计日）且上游数据已经就绪时才补跑，
 * 统计日取「昨天」——与定时任务完全同一入口、同一口径，因此仍然是幂等的。
 * 「某个历史日缺一天」属于漏跑，由下一轮定时任务按覆盖写自行补齐，不在启动期兜底，
 * 避免每次重启都做一次全量重算。</p>
 *
 * <h3>失败不影响启动</h3>
 * <p>补偿是后台批处理，抛异常只会让应用起不来却解决不了任何问题：因此整段包在
 * try/catch 中，失败只记日志，等定时任务或手动触发接口（{@code /asset/task/*&#47;run}）
 * 处理。手动触发接口始终是最终兜底手段。</p>
 *
 * @author dataforge
 */
@Component
public class AssetDailyTaskCatchUpRunner {

    private static final Logger log = LoggerFactory.getLogger(AssetDailyTaskCatchUpRunner.class);

    @Autowired
    private AssetCatalogSnapshotService snapshotService;

    @Autowired
    private AssetCatalogSnapshotTask catalogSnapshotTask;

    @Autowired
    private TableMetadataMapper tableMetadataMapper;

    /** 应用就绪后补跑缺失的日度统计（幂等；任何异常都只记日志，不影响启动） */
    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        try {
            LocalDate statDate = LocalDate.now().minusDays(1);
            if (!metadataReadyFor(statDate)) {
                return;
            }
            catchUpCatalogSnapshot(statDate);
        } catch (Exception e) {
            log.warn("资产日度任务启动补偿整体失败（不影响启动，可由定时任务或 /asset/task/*&#47;run 补齐）", e);
        }
    }

    /**
     * 元数据是否「已覆盖统计日」。
     *
     * <p>补跑出来的快照会被当成那一天的<b>历史基线</b>（大小增长率要拿它做对比），
     * 所以不能拿过期元数据去补：只有最新一次同步发生在统计日之后，
     * 才说明这份元数据至少反映了统计日那天的世界。</p>
     */
    private boolean metadataReadyFor(LocalDate statDate) {
        if (tableMetadataMapper.selectCount(null) <= 0) {
            log.info("资产日度任务启动补偿跳过：当前无纳管资产（定时任务或手动触发会补齐）");
            return false;
        }
        TableMetadata newest = tableMetadataMapper.selectOne(new LambdaQueryWrapper<TableMetadata>()
                .select(TableMetadata::getSyncTime)
                .orderByDesc(TableMetadata::getSyncTime)
                .last("LIMIT 1"));
        LocalDateTime lastSync = newest == null || newest.getSyncTime() == null
                ? null
                : LocalDateTime.ofInstant(newest.getSyncTime().toInstant(), ZoneId.systemDefault());
        if (lastSync == null || lastSync.isBefore(statDate.atStartOfDay())) {
            log.info("资产日度任务启动补偿跳过：元数据最近同步时间 {} 早于统计日 {}，"
                            + "补跑会把过期数据固化成历史基线（请先同步元数据，或用 /asset/task/*&#47;run 手动补跑）",
                    lastSync, statDate);
            return false;
        }
        return true;
    }

    private void catchUpCatalogSnapshot(LocalDate statDate) {
        try {
            if (snapshotService.latestStatDate() != null) {
                return;
            }
            AssetCatalogSnapshotTask.RunResult result = catalogSnapshotTask.runOnce(statDate);
            log.info("资产目录快照启动补偿完成：日期={}, 资产数={}（此前从未产出过快照）",
                    statDate, result.tableCount());
        } catch (Exception e) {
            log.warn("资产目录快照启动补偿失败（不影响启动，可由定时任务或 /asset/task/catalog-snapshot/run 补齐）", e);
        }
    }
}
