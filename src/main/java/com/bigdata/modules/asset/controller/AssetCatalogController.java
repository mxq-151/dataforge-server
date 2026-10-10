package com.bigdata.modules.asset.controller;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.asset.dto.AssetCategoryUpdateDTO;
import com.bigdata.modules.asset.dto.AssetDocSaveDTO;
import com.bigdata.modules.asset.dto.AssetLifecycleUpdateDTO;
import com.bigdata.modules.asset.dto.AssetOwnerUpdateDTO;
import com.bigdata.modules.asset.dto.AssetTagsUpdateDTO;
import com.bigdata.modules.asset.service.AssetCatalogService;
import com.bigdata.modules.asset.service.AssetGovernanceService;
import com.bigdata.modules.asset.service.AssetInventoryService;
import com.bigdata.modules.asset.service.AssetMetaService;
import com.bigdata.modules.asset.service.AssetProducerService;
import com.bigdata.modules.asset.service.SchedulerTaskMetricsService;
import com.bigdata.modules.asset.task.AssetCatalogSnapshotTask;
import com.bigdata.modules.asset.task.AssetPartitionSizeCollectTask;
import com.bigdata.modules.asset.task.SchedulerTaskMetricsCollectTask;
import com.bigdata.modules.asset.vo.AssetCategoryVO;
import com.bigdata.modules.asset.vo.AssetDocHistoryVO;
import com.bigdata.modules.asset.vo.AssetDocVO;
import com.bigdata.modules.asset.vo.AssetEnvProjectStatVO;
import com.bigdata.modules.asset.vo.AssetHealthIssueVO;
import com.bigdata.modules.asset.vo.AssetIndicatorVO;
import com.bigdata.modules.asset.vo.AssetInventoryVO;
import com.bigdata.modules.asset.vo.AssetPreviewVO;
import com.bigdata.modules.asset.vo.AssetProducerVO;
import com.bigdata.modules.asset.vo.AssetQualitySummaryVO;
import com.bigdata.modules.asset.vo.AssetSearchResultVO;
import com.bigdata.modules.asset.vo.AssetSizeGrowthVO;
import com.bigdata.modules.asset.vo.AssetTableDetailVO;
import com.bigdata.modules.asset.vo.AssetTagVO;
import com.bigdata.modules.asset.vo.AssetTaskDelayVO;
import com.bigdata.modules.asset.vo.AssetTopAssetVO;
import com.bigdata.modules.asset.vo.AssetTopComputeTaskVO;
import com.bigdata.modules.asset.vo.CatalogTreeNodeVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 数据资产目录接口。
 *
 * <p>路径与入参出参严格对齐前端 {@code src/api/assetCatalog.ts}（BASE = /api/asset，
 * context-path 由 server.servlet.context-path 提供，本类不重复书写 /api 前缀）。</p>
 *
 * <h3>与既有接口的边界</h3>
 * <ul>
 *   <li>血缘不在本模块重建：详情页血缘页签复用桌面端 Marquez 客户端；</li>
 *   <li>产出任务不落服务端副本：{@code /table/{id}/producers} 实时代理调度系统；</li>
 *   <li>元数据标注缺口复用既有 {@code /metadata/annotation-gaps}，此处不重复实现。</li>
 * </ul>
 *
 * @author dataforge
 */
@RestController
@RequestMapping("/asset")
public class AssetCatalogController {

    @Autowired
    private AssetCatalogService assetCatalogService;

    @Autowired
    private AssetGovernanceService assetGovernanceService;

    @Autowired
    private AssetMetaService assetMetaService;

    @Autowired
    private AssetInventoryService assetInventoryService;

    @Autowired
    private AssetProducerService assetProducerService;

    @Autowired
    private SchedulerTaskMetricsService schedulerTaskMetricsService;

    @Autowired
    private AssetCatalogSnapshotTask catalogSnapshotTask;

    @Autowired
    private AssetPartitionSizeCollectTask partitionSizeCollectTask;

    @Autowired
    private SchedulerTaskMetricsCollectTask schedulerMetricsTask;

    // ==================================================================
    // 目录与检索
    // ==================================================================

    /**
     * 目录树：view=engine 数据源→库→表；view=convention 分层→业务域→表。
     *
     * @param env 可选，限定环境（按库名后缀 _prod/_gray/_test 解析）；不传表示全环境
     */
    @GetMapping("/catalog/tree")
    public Result<List<CatalogTreeNodeVO>> getCatalogTree(
            @RequestParam(defaultValue = "engine") String view,
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(required = false) String databaseName,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String env) {
        return Result.success(assetCatalogService.tree(view, datasourceId, databaseName, keyword, env));
    }

    /** 统一检索：表 / 字段 / 指标，服务端已按权限过滤；env 只对表 / 字段生效（指标无环境维度） */
    @GetMapping("/search")
    public Result<AssetSearchResultVO> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String types,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String layerCode,
            @RequestParam(required = false) String domainCode,
            @RequestParam(required = false) String env) {
        return Result.success(assetCatalogService.search(
                keyword, types, current, size, layerCode, domainCode, env));
    }

    /** 检索输入建议 */
    @GetMapping("/suggest")
    public Result<List<String>> suggest(@RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String env) {
        return Result.success(assetCatalogService.suggest(keyword, env));
    }

    // ==================================================================
    // 资产详情
    // ==================================================================

    @GetMapping("/table/{id}")
    public Result<AssetTableDetailVO> getTableDetail(@PathVariable Long id) {
        return Result.success(assetCatalogService.detail(id));
    }

    /** 产出任务：实时查询调度系统；未接入调度系统时返回空列表（不返回占位数据） */
    @GetMapping("/table/{id}/producers")
    public Result<List<AssetProducerVO>> getTableProducers(@PathVariable Long id) {
        return Result.success(assetProducerService.producers(id));
    }

    @GetMapping("/table/{id}/indicators")
    public Result<List<AssetIndicatorVO>> getTableIndicators(@PathVariable Long id) {
        return Result.success(assetCatalogService.indicators(id));
    }

    @GetMapping("/table/{id}/quality")
    public Result<AssetQualitySummaryVO> getTableQuality(@PathVariable Long id) {
        return Result.success(assetCatalogService.quality(id));
    }

    /** 数据预览：真实下发查询，行/列权限在引擎侧生效，返回前按脱敏规则处理取值 */
    @GetMapping("/table/{id}/preview")
    public Result<AssetPreviewVO> previewTable(@PathVariable Long id,
                                              @RequestParam(defaultValue = "20") Integer limit,
                                              @RequestParam(required = false) String partition) {
        return Result.success(assetCatalogService.preview(id, limit, partition));
    }

    // ==================================================================
    // 资产治理
    // ==================================================================

    @PutMapping("/table/{id}/owner")
    public Result<Void> updateOwner(@PathVariable Long id, @RequestBody AssetOwnerUpdateDTO dto) {
        assetGovernanceService.updateOwner(id, dto);
        return Result.success();
    }

    @PutMapping("/table/{id}/category")
    public Result<Void> updateCategory(@PathVariable Long id, @RequestBody AssetCategoryUpdateDTO dto) {
        assetGovernanceService.updateCategory(id, dto);
        return Result.success();
    }

    /** 修改生命周期（热/冷/保留天数）；服务端校验 hotDays ≤ coldDays ≤ keepDays */
    @PutMapping("/table/{id}/lifecycle")
    public Result<Void> updateLifecycle(@PathVariable Long id, @RequestBody AssetLifecycleUpdateDTO dto) {
        assetGovernanceService.updateLifecycle(id, dto);
        return Result.success();
    }

    /** 整体替换标签集合（提交的是最终集合，不是增量） */
    @PutMapping("/table/{id}/tags")
    public Result<Void> updateTags(@PathVariable Long id, @RequestBody AssetTagsUpdateDTO dto) {
        assetGovernanceService.updateTags(id, dto);
        return Result.success();
    }

    @GetMapping("/table/{id}/doc")
    public Result<AssetDocVO> getTableDoc(@PathVariable Long id) {
        return Result.success(assetGovernanceService.getDoc(id));
    }

    @PutMapping("/table/{id}/doc")
    public Result<Void> saveTableDoc(@PathVariable Long id, @RequestBody AssetDocSaveDTO dto) {
        assetGovernanceService.saveDoc(id, dto);
        return Result.success();
    }

    @GetMapping("/table/{id}/doc/history")
    public Result<List<AssetDocHistoryVO>> listDocHistory(@PathVariable Long id) {
        return Result.success(assetGovernanceService.listDocHistory(id));
    }

    /** 刷新单表元数据（复用既有同步能力，此处仅做入口收口） */
    @PostMapping("/table/{id}/refresh")
    public Result<Void> refreshTableMetadata(@PathVariable Long id) {
        assetGovernanceService.refreshTableMetadata(id);
        return Result.success();
    }

    // ==================================================================
    // 字典
    // ==================================================================

    @GetMapping("/tag")
    public Result<List<AssetTagVO>> listTags() {
        return Result.success(assetMetaService.listTags());
    }

    @GetMapping("/category")
    public Result<List<AssetCategoryVO>> listCategories() {
        return Result.success(assetMetaService.listCategories());
    }

    // ==================================================================
    // 盘点
    // ==================================================================

    /** 资产盘点概览；env 按库名后缀过滤（表数量 / 占用空间 / 分层聚合都随之收敛） */
    @GetMapping("/inventory/overview")
    public Result<AssetInventoryVO> getInventoryOverview(
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String env) {
        return Result.success(assetInventoryService.overview(projectCode, env));
    }

    /** 治理项清单；env 按库名后缀过滤 */
    @GetMapping("/inventory/health")
    public Result<List<AssetHealthIssueVO>> getInventoryHealth(
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String env) {
        return Result.success(assetInventoryService.health(projectCode, env));
    }

    /** 资产概况①：按环境（生产/灰度/测试）× 项目聚合的表数量与占用空间 */
    @GetMapping("/inventory/env-project")
    public Result<List<AssetEnvProjectStatVO>> getInventoryEnvProject() {
        return Result.success(assetInventoryService.envProject());
    }

    /**
     * 资产概况②：占用空间 TOP 资产；排序与截断在服务端完成。
     *
     * @param env 可选，限定环境（按库名后缀过滤）；不传表示全环境
     */
    @GetMapping("/inventory/top-assets")
    public Result<List<AssetTopAssetVO>> getInventoryTopAssets(
            @RequestParam(defaultValue = "100") Integer limit,
            @RequestParam(required = false) String env) {
        return Result.success(assetInventoryService.topAssets(limit, env));
    }

    /**
     * 资产概况⑤：数据大小增长率突增表（默认 增长率 > 10% 且当前大小 > 10GB）。
     *
     * <p>天分区表按「最新分区 vs 近 N 个分区均值」、全量表按「最新快照 vs 近 N 天快照均值」计算。</p>
     *
     * @param windowDays 对比期数 N，默认 7、夹到 2~30
     */
    @GetMapping("/inventory/size-growth")
    public Result<List<AssetSizeGrowthVO>> getInventorySizeGrowth(
            @RequestParam(required = false) Integer growthPercent,
            @RequestParam(required = false) Long minSizeBytes,
            @RequestParam(required = false) Integer windowDays,
            @RequestParam(required = false) String env) {
        return Result.success(assetInventoryService.sizeGrowth(growthPercent, minSizeBytes, windowDays, env));
    }

    /**
     * 资产概况③：消耗计算资源 / 运行时长 TOP 任务。
     *
     * <p>本平台无 YARN，cpuHours / memoryGbHours 恒为 null（前端显示「—」），
     * 排序按统计窗口内累计运行时长降序。</p>
     *
     * @param env 可选，限定环境（按<b>工作流名后缀</b>过滤，与库名后缀同一套约定）
     */
    @GetMapping("/inventory/top-compute")
    public Result<List<AssetTopComputeTaskVO>> getInventoryTopCompute(
            @RequestParam(defaultValue = "resource") String sortBy,
            @RequestParam(defaultValue = "50") Integer limit,
            @RequestParam(required = false) String env) {
        return Result.success(schedulerTaskMetricsService.topCompute(sortBy, limit, env));
    }

    /** 资产概况④：任务延迟率（延迟 = 实际开始晚于计划开始）；env 按工作流名后缀过滤 */
    @GetMapping("/inventory/task-delay")
    public Result<AssetTaskDelayVO> getInventoryTaskDelay(
            @RequestParam(defaultValue = "30") Integer days,
            @RequestParam(required = false) String env) {
        return Result.success(schedulerTaskMetricsService.taskDelay(days, env));
    }

    // ==================================================================
    // 任务手动触发（与每日定时任务共用同一执行入口，幂等可重放）
    // ==================================================================

    /**
     * 手动跑一次资产目录快照生成（默认昨天）。
     *
     * <p>与 02:30 定时任务同口径：生成指定统计日的快照并顺带清理超期历史；
     * 重复执行按日覆盖写，结果一致。</p>
     *
     * @param statDate 统计日（yyyy-MM-dd），缺省为昨天；不允许未来日期
     */
    @PostMapping("/task/catalog-snapshot/run")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Result<AssetCatalogSnapshotTask.RunResult> runCatalogSnapshot(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate statDate) {
        return Result.success(catalogSnapshotTask.runOnce(resolveStatDate(statDate)));
    }

    /**
     * 手动跑一次调度任务指标采集（默认昨天）。
     *
     * <p>与 03:30 定时任务同口径：重拉近 30 天 DolphinScheduler 运行记录并覆盖写；
     * {@code skippedReason} 非空表示调度系统未配置，属预期跳过而非失败。</p>
     */
    @PostMapping("/task/scheduler-metrics/run")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Result<SchedulerTaskMetricsService.CollectResult> runSchedulerMetrics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate anchorDate) {
        return Result.success(schedulerMetricsTask.runOnce(resolveStatDate(anchorDate)));
    }

    /**
     * 手动跑一次分区大小采集（默认昨天）。
     *
     * <p>与 02:20 定时任务同口径：采集指定统计日的天分区表最新分区大小并顺带清理超期历史；
     * 重复执行按日覆盖写，结果一致。</p>
     *
     * @param statDate 统计日（yyyy-MM-dd），缺省为昨天；不允许未来日期
     */
    @PostMapping("/task/partition-size/run")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Result<AssetPartitionSizeCollectTask.RunResult> runPartitionSize(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate statDate) {
        return Result.success(partitionSizeCollectTask.runOnce(resolveStatDate(statDate)));
    }

    /** 统计日缺省为昨天（T+1 口径）；未来日期没有可统计的数据，直接拒绝 */
    private LocalDate resolveStatDate(LocalDate statDate) {
        LocalDate today = LocalDate.now();
        LocalDate date = statDate == null ? today.minusDays(1) : statDate;
        if (date.isAfter(today)) {
            throw new BusinessException("统计日不能晚于今天");
        }
        return date;
    }
}
