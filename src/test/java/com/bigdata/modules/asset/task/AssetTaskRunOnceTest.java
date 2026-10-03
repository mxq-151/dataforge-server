package com.bigdata.modules.asset.task;

import com.bigdata.modules.asset.service.AssetCatalogSnapshotService;
import com.bigdata.modules.asset.service.AssetUsageService;
import com.bigdata.modules.asset.service.SchedulerTaskMetricsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资产任务「跑一次」入口单测：验证手动触发与定时任务共用的 runOnce 组合逻辑
 * （快照含超期清理、热度汇总含冗余列回写、调度采集直接委托）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("资产任务 runOnce - 手动与定时共用入口")
class AssetTaskRunOnceTest {

    @Mock
    private AssetCatalogSnapshotService snapshotService;
    @InjectMocks
    private AssetCatalogSnapshotTask snapshotTask;

    @Mock
    private AssetUsageService assetUsageService;
    @InjectMocks
    private AssetUsageRollupTask rollupTask;

    @Mock
    private SchedulerTaskMetricsService metricsService;
    @InjectMocks
    private SchedulerTaskMetricsCollectTask metricsTask;

    @Test
    @DisplayName("快照任务：生成指定日快照并按保留期清理，返回组合摘要")
    void testSnapshotRunOnce() {
        LocalDate statDate = LocalDate.of(2026, 9, 30);
        when(snapshotService.snapshot(statDate))
                .thenReturn(new AssetCatalogSnapshotService.SnapshotResult(statDate, 128));
        when(snapshotService.purgeBefore(statDate.minusDays(AssetCatalogSnapshotService.RETENTION_DAYS)))
                .thenReturn(3);

        AssetCatalogSnapshotTask.RunResult result = snapshotTask.runOnce(statDate);

        assertEquals(statDate, result.statDate());
        assertEquals(128, result.tableCount());
        assertEquals(3, result.purged());
        // 独立断言清理截止日：180 天保留期对应 2026-09-30 → 2026-04-03
        verify(snapshotService).purgeBefore(LocalDate.of(2026, 4, 3));
    }

    @Test
    @DisplayName("热度汇总任务：聚合后回写热度冗余列，返回组合摘要")
    void testRollupRunOnce() {
        LocalDate statDate = LocalDate.of(2026, 9, 29);
        when(assetUsageService.rollupDaily(statDate))
                .thenReturn(new AssetUsageService.RollupResult(statDate, 45, 12));
        when(assetUsageService.refreshTableCounters()).thenReturn(30);

        AssetUsageRollupTask.RunResult result = rollupTask.runOnce(statDate);

        assertEquals(statDate, result.statDate());
        assertEquals(45, result.assetCount());
        assertEquals(12, result.hotUpdated());
        assertEquals(30, result.tablesUpdated());
        verify(assetUsageService).refreshTableCounters();
    }

    @Test
    @DisplayName("调度指标任务：委托采集并原样返回结果（含跳过原因）")
    void testMetricsRunOnce() {
        LocalDate anchorDate = LocalDate.of(2026, 9, 29);
        SchedulerTaskMetricsService.CollectResult collectResult =
                new SchedulerTaskMetricsService.CollectResult(anchorDate, 2, 120, 88, null);
        when(metricsService.collect(anchorDate)).thenReturn(collectResult);

        SchedulerTaskMetricsService.CollectResult result = metricsTask.runOnce(anchorDate);

        assertSame(collectResult, result);
        verify(metricsService).collect(anchorDate);
    }
}