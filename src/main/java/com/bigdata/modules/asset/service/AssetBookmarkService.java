package com.bigdata.modules.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.utils.SecurityUtils;
import com.bigdata.modules.asset.dto.AssetBookmarkDTO;
import com.bigdata.modules.asset.entity.AssetBookmark;
import com.bigdata.modules.asset.mapper.AssetBookmarkMapper;
import com.bigdata.modules.asset.vo.AssetSearchHitVO;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.Indicator;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.ColumnMetadataMapper;
import com.bigdata.modules.metadata.mapper.IndicatorMapper;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 资产收藏 —— 「我关注的表/字段/指标」。
 *
 * <h3>列表为什么读的时候还要过一遍权限</h3>
 * <p>收藏是「用户主动记住」的快照，但权限会变。如果收藏后权限被回收，
 * 收藏列表仍然把表名、负责人、分层展示出来，就等于用收藏当了一次绕过权限的读数通道。
 * 因此这里在读列表时对被收藏对象再做一次批量权限过滤：<b>取不到权限就不返回</b>，
 * 收藏记录本身保留（权限恢复后自动重新可见），不做删除。</p>
 *
 * @author dataforge
 */
@Service
public class AssetBookmarkService {

    private static final Logger log = LoggerFactory.getLogger(AssetBookmarkService.class);

    private static final Set<String> ASSET_TYPES = Set.of("table", "column", "indicator");

    @Autowired
    private AssetBookmarkMapper assetBookmarkMapper;

    @Autowired
    private TableMetadataMapper tableMetadataMapper;

    @Autowired
    private ColumnMetadataMapper columnMetadataMapper;

    @Autowired
    private IndicatorMapper indicatorMapper;

    @Autowired
    private AssetPermissionSupport permissionSupport;

    @Autowired
    private AssetUsageService assetUsageService;

    /** 当前用户的收藏分页 */
    public Page<AssetSearchHitVO> page(int current, int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        Page<AssetBookmark> page = new Page<>(current, size);
        if (userId == null) {
            Page<AssetSearchHitVO> empty = new Page<>(current, size, 0);
            empty.setRecords(List.of());
            return empty;
        }

        LambdaQueryWrapper<AssetBookmark> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AssetBookmark::getDeleted, 0)
                .eq(AssetBookmark::getUserId, userId)
                .orderByDesc(AssetBookmark::getId);
        Page<AssetBookmark> bookmarkPage = assetBookmarkMapper.selectPage(page, wrapper);

        Page<AssetSearchHitVO> result = new Page<>(bookmarkPage.getCurrent(), bookmarkPage.getSize(),
                bookmarkPage.getTotal());
        result.setRecords(toHits(bookmarkPage.getRecords()));
        return result;
    }

    /** 当前用户是否已收藏某资产 */
    public boolean isBookmarked(Long userId, String assetType, Long assetId) {
        if (userId == null || assetId == null || assetType == null) {
            return false;
        }
        LambdaQueryWrapper<AssetBookmark> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AssetBookmark::getDeleted, 0)
                .eq(AssetBookmark::getUserId, userId)
                .eq(AssetBookmark::getAssetType, assetType)
                .eq(AssetBookmark::getAssetId, assetId);
        return assetBookmarkMapper.selectCount(wrapper) > 0;
    }

    /** 某张表被收藏的总数（详情页展示） */
    public int countOfTable(Long tableMetadataId) {
        if (tableMetadataId == null) {
            return 0;
        }
        LambdaQueryWrapper<AssetBookmark> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AssetBookmark::getDeleted, 0)
                .eq(AssetBookmark::getAssetType, "table")
                .eq(AssetBookmark::getAssetId, tableMetadataId);
        Long count = assetBookmarkMapper.selectCount(wrapper);
        return count == null ? 0 : count.intValue();
    }

    /** 新增收藏（幂等：已收藏时直接返回成功） */
    @Transactional
    public void add(AssetBookmarkDTO dto) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(401, "未登录，无法收藏");
        }
        String assetType = normalizeType(dto == null ? null : dto.getAssetType());
        Long assetId = dto == null ? null : dto.getAssetId();
        if (assetType == null || assetId == null) {
            throw new BusinessException("assetType 与 assetId 必须填写，且 assetType 只能是 table/column/indicator");
        }

        Long tableMetadataId = dto.getTableMetadataId();
        // 按类型补全归属表：column 的归属表从字段元数据推，table 就是自身。
        // indicator 不强制归属表（一个指标可能引用多张表）。
        if ("table".equals(assetType)) {
            tableMetadataId = assetId;
        } else if ("column".equals(assetType)) {
            ColumnMetadata column = columnMetadataMapper.selectById(assetId);
            if (column == null) {
                throw new BusinessException(404, "字段不存在");
            }
            tableMetadataId = column.getTableMetadataId();
        }

        // 收藏前先确认可见：否则「猜 id 收藏」会变成探测越权资产是否存在的手段
        if (tableMetadataId != null) {
            permissionSupport.checkSelectable(tableMetadataMapper.selectById(tableMetadataId));
        }

        if (isBookmarked(userId, assetType, assetId)) {
            return;
        }
        AssetBookmark bookmark = new AssetBookmark();
        bookmark.setUserId(userId);
        bookmark.setAssetType(assetType);
        bookmark.setAssetId(assetId);
        bookmark.setTableMetadataId(tableMetadataId);
        bookmark.setDeleted(0);
        assetBookmarkMapper.insert(bookmark);

        if (tableMetadataId != null) {
            assetUsageService.refreshBookmarkCount(tableMetadataId);
        }
        log.info("用户 {} 收藏 {}:{}", userId, assetType, assetId);
    }

    /** 取消收藏（不存在时视为成功，保证前端幂等调用不报错） */
    @Transactional
    public void remove(String assetType, Long assetId) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(401, "未登录，无法取消收藏");
        }
        String type = normalizeType(assetType);
        if (type == null || assetId == null) {
            throw new BusinessException("assetType 与 assetId 必须填写");
        }

        LambdaQueryWrapper<AssetBookmark> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AssetBookmark::getDeleted, 0)
                .eq(AssetBookmark::getUserId, userId)
                .eq(AssetBookmark::getAssetType, type)
                .eq(AssetBookmark::getAssetId, assetId);
        AssetBookmark existing = assetBookmarkMapper.selectOne(wrapper);
        if (existing == null) {
            return;
        }
        // 逻辑删除：唯一键 (user_id, asset_type, asset_id) 不区分删除状态，
        // 因此这里做物理删除，否则用户「取消后重新收藏」会撞唯一键。
        assetBookmarkMapper.deleteById(existing.getId());

        if (existing.getTableMetadataId() != null) {
            assetUsageService.refreshBookmarkCount(existing.getTableMetadataId());
        }
    }

    // ==================================================================
    // 命中项组装
    // ==================================================================

    private List<AssetSearchHitVO> toHits(List<AssetBookmark> bookmarks) {
        if (bookmarks == null || bookmarks.isEmpty()) {
            return List.of();
        }
        Set<Long> tableIds = new LinkedHashSet<>();
        Set<Long> columnIds = new LinkedHashSet<>();
        Set<Long> indicatorIds = new LinkedHashSet<>();
        for (AssetBookmark bookmark : bookmarks) {
            switch (bookmark.getAssetType() == null ? "" : bookmark.getAssetType()) {
                case "table" -> tableIds.add(bookmark.getAssetId());
                case "column" -> columnIds.add(bookmark.getAssetId());
                case "indicator" -> indicatorIds.add(bookmark.getAssetId());
                default -> {
                }
            }
            if (bookmark.getTableMetadataId() != null) {
                tableIds.add(bookmark.getTableMetadataId());
            }
        }

        Map<Long, TableMetadata> tables = loadVisibleTables(tableIds);
        Map<Long, ColumnMetadata> columns = columnIds.isEmpty() ? Map.of()
                : indexById(columnMetadataMapper.selectBatchIds(columnIds), ColumnMetadata::getId);
        Map<Long, Indicator> indicators = indicatorIds.isEmpty() ? Map.of()
                : indexById(indicatorMapper.selectBatchIds(indicatorIds), Indicator::getId);

        List<AssetSearchHitVO> hits = new ArrayList<>(bookmarks.size());
        for (AssetBookmark bookmark : bookmarks) {
            AssetSearchHitVO hit = null;
            switch (bookmark.getAssetType() == null ? "" : bookmark.getAssetType()) {
                case "table" -> hit = tableHit(tables.get(bookmark.getAssetId()));
                case "column" -> hit = columnHit(columns.get(bookmark.getAssetId()), tables);
                case "indicator" -> hit = indicatorHit(indicators.get(bookmark.getAssetId()));
                default -> {
                }
            }
            if (hit != null) {
                hits.add(hit);
            }
        }
        return hits;
    }

    /** 批量取表并做权限过滤（fail-close：不可见的表不进结果） */
    private Map<Long, TableMetadata> loadVisibleTables(Set<Long> tableIds) {
        if (tableIds.isEmpty()) {
            return Map.of();
        }
        List<TableMetadata> tables = tableMetadataMapper.selectBatchIds(tableIds);
        Map<Long, TableMetadata> result = new HashMap<>();
        for (TableMetadata table : permissionSupport.filterSelectable(tables)) {
            result.put(table.getId(), table);
        }
        return result;
    }

    private AssetSearchHitVO tableHit(TableMetadata table) {
        if (table == null) {
            return null;
        }
        AssetSearchHitVO hit = new AssetSearchHitVO();
        hit.setAssetType("table");
        hit.setId(table.getId());
        hit.setTableMetadataId(table.getId());
        hit.setTitle(table.getTableName());
        hit.setSubtitle(table.getDatabaseName() + "." + table.getTableName());
        hit.setMatchedText(table.getTableComment());
        hit.setOwnerName(table.getBusinessOwner());
        TableNameConventionParser.Parsed parsed = TableNameConventionParser.parse(table.getTableName());
        hit.setLayerCode(parsed.layerCode());
        hit.setDomainCode(parsed.domainCode());
        return hit;
    }

    private AssetSearchHitVO columnHit(ColumnMetadata column, Map<Long, TableMetadata> tables) {
        if (column == null) {
            return null;
        }
        TableMetadata table = tables.get(column.getTableMetadataId());
        // 字段所属表不可见时，字段本身也不返回
        if (table == null) {
            return null;
        }
        AssetSearchHitVO hit = new AssetSearchHitVO();
        hit.setAssetType("column");
        hit.setId(column.getId());
        hit.setTableMetadataId(table.getId());
        hit.setTitle(column.getColumnName());
        hit.setSubtitle(table.getTableName() + "." + column.getColumnName());
        hit.setMatchedText(column.getColumnComment());
        hit.setOwnerName(table.getBusinessOwner());
        TableNameConventionParser.Parsed parsed = TableNameConventionParser.parse(table.getTableName());
        hit.setLayerCode(parsed.layerCode());
        hit.setDomainCode(parsed.domainCode());
        return hit;
    }

    private AssetSearchHitVO indicatorHit(Indicator indicator) {
        if (indicator == null) {
            return null;
        }
        AssetSearchHitVO hit = new AssetSearchHitVO();
        hit.setAssetType("indicator");
        hit.setId(indicator.getId());
        hit.setTitle(indicator.getName());
        hit.setSubtitle(indicator.getCode());
        hit.setMatchedText(indicator.getBusinessDefinition());
        hit.setOwnerName(indicator.getBusinessOwner());
        return hit;
    }

    private static <T> Map<Long, T> indexById(List<T> rows, java.util.function.Function<T, Long> idGetter) {
        Map<Long, T> result = new HashMap<>();
        for (T row : rows) {
            Long id = idGetter.apply(row);
            if (id != null) {
                result.put(id, row);
            }
        }
        return result;
    }

    private static String normalizeType(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return ASSET_TYPES.contains(value) ? value : null;
    }
}
