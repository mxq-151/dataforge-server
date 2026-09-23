package com.bigdata.modules.asset.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.asset.dto.AssetAccessReportDTO;
import com.bigdata.modules.asset.dto.AssetBookmarkDTO;
import com.bigdata.modules.asset.dto.AssetCategoryUpdateDTO;
import com.bigdata.modules.asset.dto.AssetDocSaveDTO;
import com.bigdata.modules.asset.dto.AssetOwnerUpdateDTO;
import com.bigdata.modules.asset.dto.AssetTagsUpdateDTO;
import com.bigdata.modules.asset.service.AssetBookmarkService;
import com.bigdata.modules.asset.service.AssetCatalogService;
import com.bigdata.modules.asset.service.AssetGovernanceService;
import com.bigdata.modules.asset.service.AssetInventoryService;
import com.bigdata.modules.asset.service.AssetMetaService;
import com.bigdata.modules.asset.service.AssetProducerService;
import com.bigdata.modules.asset.service.AssetUsageService;
import com.bigdata.modules.asset.vo.AssetCategoryVO;
import com.bigdata.modules.asset.vo.AssetDocHistoryVO;
import com.bigdata.modules.asset.vo.AssetDocVO;
import com.bigdata.modules.asset.vo.AssetHealthIssueVO;
import com.bigdata.modules.asset.vo.AssetIndicatorVO;
import com.bigdata.modules.asset.vo.AssetInventoryVO;
import com.bigdata.modules.asset.vo.AssetPreviewVO;
import com.bigdata.modules.asset.vo.AssetProducerVO;
import com.bigdata.modules.asset.vo.AssetQualitySummaryVO;
import com.bigdata.modules.asset.vo.AssetSearchHitVO;
import com.bigdata.modules.asset.vo.AssetSearchResultVO;
import com.bigdata.modules.asset.vo.AssetTableDetailVO;
import com.bigdata.modules.asset.vo.AssetTagVO;
import com.bigdata.modules.asset.vo.AssetUsageVO;
import com.bigdata.modules.asset.vo.CatalogTreeNodeVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    private AssetUsageService assetUsageService;

    @Autowired
    private AssetGovernanceService assetGovernanceService;

    @Autowired
    private AssetBookmarkService assetBookmarkService;

    @Autowired
    private AssetMetaService assetMetaService;

    @Autowired
    private AssetInventoryService assetInventoryService;

    @Autowired
    private AssetProducerService assetProducerService;

    // ==================================================================
    // 目录与检索
    // ==================================================================

    /** 目录树：view=engine 数据源→库→表；view=convention 分层→业务域→表 */
    @GetMapping("/catalog/tree")
    public Result<List<CatalogTreeNodeVO>> getCatalogTree(
            @RequestParam(defaultValue = "engine") String view,
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(required = false) String databaseName,
            @RequestParam(required = false) String keyword) {
        return Result.success(assetCatalogService.tree(view, datasourceId, databaseName, keyword));
    }

    /** 统一检索：表 / 字段 / 指标，服务端已按权限过滤 */
    @GetMapping("/search")
    public Result<AssetSearchResultVO> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String types,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String layerCode,
            @RequestParam(required = false) String domainCode) {
        return Result.success(assetCatalogService.search(
                keyword, types, current, size, layerCode, domainCode));
    }

    /** 检索输入建议 */
    @GetMapping("/suggest")
    public Result<List<String>> suggest(@RequestParam(required = false) String keyword) {
        return Result.success(assetCatalogService.suggest(keyword));
    }

    // ==================================================================
    // 资产详情
    // ==================================================================

    @GetMapping("/table/{id}")
    public Result<AssetTableDetailVO> getTableDetail(@PathVariable Long id) {
        return Result.success(assetCatalogService.detail(id));
    }

    @GetMapping("/table/{id}/usage")
    public Result<AssetUsageVO> getTableUsage(@PathVariable Long id,
                                             @RequestParam(defaultValue = "30") Integer days) {
        return Result.success(assetUsageService.usage(id, days));
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
    // 收藏与字典
    // ==================================================================

    @GetMapping("/bookmark")
    public Result<Page<AssetSearchHitVO>> listBookmarks(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(assetBookmarkService.page(current, size));
    }

    @PostMapping("/bookmark")
    public Result<Void> addBookmark(@RequestBody AssetBookmarkDTO dto) {
        assetBookmarkService.add(dto);
        return Result.success();
    }

    @DeleteMapping("/bookmark")
    public Result<Void> removeBookmark(@RequestParam String assetType, @RequestParam Long assetId) {
        assetBookmarkService.remove(assetType, assetId);
        return Result.success();
    }

    @GetMapping("/tag")
    public Result<List<AssetTagVO>> listTags() {
        return Result.success(assetMetaService.listTags());
    }

    @GetMapping("/category")
    public Result<List<AssetCategoryVO>> listCategories() {
        return Result.success(assetMetaService.listCategories());
    }

    // ==================================================================
    // 埋点与盘点
    // ==================================================================

    /**
     * 访问埋点上报。
     *
     * <p>非关键路径：非法埋点会被静默丢弃，不返回错误码，
     * 避免前端为「埋点失败」做无意义的错误处理。</p>
     */
    @PostMapping("/access")
    public Result<Void> reportAccess(@RequestBody AssetAccessReportDTO dto) {
        assetUsageService.recordAccess(dto);
        return Result.success();
    }

    @GetMapping("/inventory/overview")
    public Result<AssetInventoryVO> getInventoryOverview(
            @RequestParam(required = false) String projectCode) {
        return Result.success(assetInventoryService.overview(projectCode));
    }

    @GetMapping("/inventory/health")
    public Result<List<AssetHealthIssueVO>> getInventoryHealth(
            @RequestParam(required = false) String projectCode) {
        return Result.success(assetInventoryService.health(projectCode));
    }
}
