package com.bigdata.modules.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.utils.SecurityUtils;
import com.bigdata.modules.asset.dto.AssetCategoryUpdateDTO;
import com.bigdata.modules.asset.dto.AssetDocSaveDTO;
import com.bigdata.modules.asset.dto.AssetOwnerUpdateDTO;
import com.bigdata.modules.asset.dto.AssetTagsUpdateDTO;
import com.bigdata.modules.asset.entity.AssetDoc;
import com.bigdata.modules.asset.entity.AssetDocHistory;
import com.bigdata.modules.asset.entity.AssetTag;
import com.bigdata.modules.asset.entity.AssetTagRel;
import com.bigdata.modules.asset.mapper.AssetDocHistoryMapper;
import com.bigdata.modules.asset.mapper.AssetDocMapper;
import com.bigdata.modules.asset.mapper.AssetTagMapper;
import com.bigdata.modules.asset.mapper.AssetTagRelMapper;
import com.bigdata.modules.asset.vo.AssetDocHistoryVO;
import com.bigdata.modules.asset.vo.AssetDocVO;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import com.bigdata.modules.metadata.service.MetadataSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 资产治理 —— 负责人、分类、标签、使用说明的维护。
 *
 * <h3>写入一律先校验可见性</h3>
 * <p>治理接口是写操作，比读更危险：不能先看再改的东西，也不应该能直接改。
 * 因此每个写方法都先做一次表级 SELECT 权限校验（fail-close）。
 * 更细的「谁能改元数据」属于平台角色权限，不在本模块自行发明。</p>
 *
 * <h3>空值语义：null 表示不改，空串表示清空</h3>
 * <p>前端治理面板是增量提交（只提交用户改过的字段），
 * 若把 null 当作「清空」，用户改负责人就会顺手把主题域清掉。
 * 需要清空时前端传空串，语义显式。</p>
 *
 * @author dataforge
 */
@Service
public class AssetGovernanceService {

    private static final Logger log = LoggerFactory.getLogger(AssetGovernanceService.class);

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 可见性白名单：与 table_metadata.visibility 的 enum 定义一致 */
    private static final Set<String> VISIBILITIES = Set.of("public", "internal", "restricted");

    /** 资产等级白名单 */
    private static final Set<String> ASSET_LEVELS = Set.of("L1", "L2", "L3", "L4", "L5");

    /** 使用说明正文长度上限（asset_doc.content 是 text，但仍然要防单次巨包写入） */
    private static final int MAX_DOC_LENGTH = 200_000;

    @Autowired
    private TableMetadataMapper tableMetadataMapper;

    @Autowired
    private AssetDocMapper assetDocMapper;

    @Autowired
    private AssetDocHistoryMapper assetDocHistoryMapper;

    @Autowired
    private AssetTagMapper assetTagMapper;

    @Autowired
    private AssetTagRelMapper assetTagRelMapper;

    @Autowired
    private AssetPermissionSupport permissionSupport;

    @Autowired
    private MetadataSyncService metadataSyncService;

    // ==================================================================
    // 负责人
    // ==================================================================

    @Transactional
    public void updateOwner(Long tableId, AssetOwnerUpdateDTO dto) {
        TableMetadata table = requireVisibleTable(tableId);
        if (dto == null) {
            throw new BusinessException("请求体不能为空");
        }
        TableMetadata update = new TableMetadata();
        update.setId(table.getId());
        boolean changed = false;
        if (dto.getBusinessOwner() != null) {
            update.setBusinessOwner(blankToNull(dto.getBusinessOwner()));
            changed = true;
        }
        if (dto.getTechOwner() != null) {
            update.setTechOwner(blankToNull(dto.getTechOwner()));
            changed = true;
        }
        if (!changed) {
            return;
        }
        tableMetadataMapper.updateById(update);
        log.info("更新资产负责人: table={}.{}, businessOwner={}, techOwner={}",
                table.getDatabaseName(), table.getTableName(),
                dto.getBusinessOwner(), dto.getTechOwner());
    }

    // ==================================================================
    // 分类与治理属性
    // ==================================================================

    @Transactional
    public void updateCategory(Long tableId, AssetCategoryUpdateDTO dto) {
        TableMetadata table = requireVisibleTable(tableId);
        if (dto == null) {
            throw new BusinessException("请求体不能为空");
        }
        TableMetadata update = new TableMetadata();
        update.setId(table.getId());
        boolean changed = false;

        if (dto.getCategoryCode() != null) {
            // 空串 = 清除人工覆盖，回落到表名规范解析结果
            update.setCategoryCode(blankToNull(dto.getCategoryCode()));
            changed = true;
        }
        if (dto.getTopic() != null) {
            update.setTopic(blankToNull(dto.getTopic()));
            changed = true;
        }
        if (dto.getVisibility() != null) {
            String visibility = blankToNull(dto.getVisibility());
            if (visibility != null) {
                String normalized = visibility.toLowerCase(Locale.ROOT);
                if (!VISIBILITIES.contains(normalized)) {
                    throw new BusinessException("可见性只能是 public / internal / restricted 之一");
                }
                visibility = normalized;
            }
            update.setVisibility(visibility);
            changed = true;
        }
        if (dto.getAssetLevel() != null) {
            String level = blankToNull(dto.getAssetLevel());
            if (level != null) {
                String normalized = level.toUpperCase(Locale.ROOT);
                if (!ASSET_LEVELS.contains(normalized)) {
                    throw new BusinessException("资产等级只能是 L1 ~ L5");
                }
                level = normalized;
            }
            update.setAssetLevel(level);
            changed = true;
        }
        if (!changed) {
            return;
        }
        tableMetadataMapper.updateById(update);
        log.info("更新资产分类: table={}.{}, categoryCode={}, topic={}, visibility={}, assetLevel={}",
                table.getDatabaseName(), table.getTableName(),
                dto.getCategoryCode(), dto.getTopic(), dto.getVisibility(), dto.getAssetLevel());
    }

    // ==================================================================
    // 标签
    // ==================================================================

    /** 整体替换某张表的标签集合（前端提交的是最终集合，不是增量） */
    @Transactional
    public void updateTags(Long tableId, AssetTagsUpdateDTO dto) {
        TableMetadata table = requireVisibleTable(tableId);
        List<String> requested = dto == null ? null : dto.getTagCodes();
        Set<String> tagCodes = new LinkedHashSet<>();
        if (requested != null) {
            for (String code : requested) {
                if (code != null && !code.isBlank()) {
                    tagCodes.add(code.trim());
                }
            }
        }

        List<Long> tagIds = new ArrayList<>();
        if (!tagCodes.isEmpty()) {
            LambdaQueryWrapper<AssetTag> tagWrapper = new LambdaQueryWrapper<>();
            tagWrapper.eq(AssetTag::getDeleted, 0)
                    .eq(AssetTag::getEnabled, 1)
                    .in(AssetTag::getTagCode, tagCodes);
            List<AssetTag> tags = assetTagMapper.selectList(tagWrapper);
            if (tags.size() != tagCodes.size()) {
                Set<String> found = new LinkedHashSet<>();
                for (AssetTag tag : tags) {
                    found.add(tag.getTagCode());
                }
                Set<String> missing = new LinkedHashSet<>(tagCodes);
                missing.removeAll(found);
                throw new BusinessException("以下标签不存在或已停用：" + missing);
            }
            for (AssetTag tag : tags) {
                tagIds.add(tag.getId());
            }
        }

        LambdaQueryWrapper<AssetTagRel> existingWrapper = new LambdaQueryWrapper<>();
        existingWrapper.eq(AssetTagRel::getDeleted, 0)
                .eq(AssetTagRel::getAssetType, "table")
                .eq(AssetTagRel::getAssetId, tableId);
        List<AssetTagRel> existing = assetTagRelMapper.selectList(existingWrapper);

        Set<Long> existingTagIds = new LinkedHashSet<>();
        for (AssetTagRel rel : existing) {
            existingTagIds.add(rel.getTagId());
        }
        Set<Long> desired = new LinkedHashSet<>(tagIds);

        // 差集：先删后增，避免「先删光再重建」把未变更的关联也一起抖动
        for (AssetTagRel rel : existing) {
            if (!desired.contains(rel.getTagId())) {
                assetTagRelMapper.deleteById(rel.getId());
            }
        }
        for (Long tagId : desired) {
            if (!existingTagIds.contains(tagId)) {
                AssetTagRel rel = new AssetTagRel();
                rel.setTagId(tagId);
                rel.setAssetType("table");
                rel.setAssetId(tableId);
                rel.setTableMetadataId(tableId);
                rel.setDeleted(0);
                assetTagRelMapper.insert(rel);
            }
        }
        log.info("更新资产标签: table={}.{}, tags={}", table.getDatabaseName(), table.getTableName(), tagCodes);
    }

    /** 某张表的标签列表 */
    public List<AssetTagRel> tagRelsOfTable(Long tableId) {
        LambdaQueryWrapper<AssetTagRel> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AssetTagRel::getDeleted, 0)
                .eq(AssetTagRel::getAssetType, "table")
                .eq(AssetTagRel::getAssetId, tableId);
        return assetTagRelMapper.selectList(wrapper);
    }

    // ==================================================================
    // 使用说明
    // ==================================================================

    /** 读取使用说明；从未写过时返回空正文而不是 404（详情页需要稳定的响应形状） */
    public AssetDocVO getDoc(Long tableId) {
        TableMetadata table = requireVisibleTable(tableId);
        AssetDoc doc = findDoc(table.getId());
        AssetDocVO vo = new AssetDocVO();
        if (doc == null) {
            vo.setContent("");
            vo.setUpdateBy("");
            vo.setUpdateTime("");
            return vo;
        }
        vo.setContent(doc.getContent() == null ? "" : doc.getContent());
        vo.setUpdateBy(doc.getUpdateBy() == null ? "" : doc.getUpdateBy());
        vo.setUpdateTime(doc.getUpdateTime() == null ? "" : doc.getUpdateTime().format(TIME_FMT));
        return vo;
    }

    /** 保存使用说明：写正文、把上一版归档进历史 */
    @Transactional
    public void saveDoc(Long tableId, AssetDocSaveDTO dto) {
        TableMetadata table = requireVisibleTable(tableId);
        String content = dto == null ? null : dto.getContent();
        if (content == null) {
            throw new BusinessException("content 不能为空");
        }
        if (content.length() > MAX_DOC_LENGTH) {
            throw new BusinessException("使用说明过长（上限 " + MAX_DOC_LENGTH + " 字符）");
        }
        String operator = SecurityUtils.getCurrentUsername();

        AssetDoc existing = findDoc(table.getId());
        if (existing == null) {
            AssetDoc doc = new AssetDoc();
            doc.setTableMetadataId(table.getId());
            doc.setContent(content);
            doc.setVersion(1);
            doc.setUpdateBy(operator);
            doc.setDeleted(0);
            assetDocMapper.insert(doc);
            archive(table.getId(), 1, content, operator);
            log.info("新建资产使用说明: table={}.{}", table.getDatabaseName(), table.getTableName());
            return;
        }

        int nextVersion = (existing.getVersion() == null ? 1 : existing.getVersion()) + 1;
        // 归档的是「被替换掉的那一版」，不是新内容 ——
        // 否则历史里永远看不到用户在改之前写的是什么。
        archive(table.getId(), existing.getVersion() == null ? 1 : existing.getVersion(),
                existing.getContent(), existing.getUpdateBy());

        AssetDoc update = new AssetDoc();
        update.setId(existing.getId());
        update.setContent(content);
        update.setVersion(nextVersion);
        update.setUpdateBy(operator);
        assetDocMapper.updateById(update);
        log.info("更新资产使用说明: table={}.{}, version={}",
                table.getDatabaseName(), table.getTableName(), nextVersion);
    }

    /** 版本历史（最新在前） */
    public List<AssetDocHistoryVO> listDocHistory(Long tableId) {
        TableMetadata table = requireVisibleTable(tableId);
        LambdaQueryWrapper<AssetDocHistory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AssetDocHistory::getDeleted, 0)
                .eq(AssetDocHistory::getTableMetadataId, table.getId())
                .orderByDesc(AssetDocHistory::getVersion);
        List<AssetDocHistoryVO> result = new ArrayList<>();
        for (AssetDocHistory row : assetDocHistoryMapper.selectList(wrapper)) {
            AssetDocHistoryVO vo = new AssetDocHistoryVO();
            vo.setVersion(row.getVersion());
            vo.setUpdateBy(row.getUpdateBy() == null ? "" : row.getUpdateBy());
            vo.setUpdateTime(row.getUpdateTime() == null ? "" : row.getUpdateTime().format(TIME_FMT));
            result.add(vo);
        }
        return result;
    }

    /** 使用说明正文（供资产详情聚合，不再重复校验权限） */
    public String docContentOf(TableMetadata table) {
        AssetDoc doc = findDoc(table.getId());
        return doc == null || doc.getContent() == null ? "" : doc.getContent();
    }

    private void archive(Long tableMetadataId, int version, String content, String updateBy) {
        AssetDocHistory history = new AssetDocHistory();
        history.setTableMetadataId(tableMetadataId);
        history.setVersion(version);
        history.setContent(content);
        history.setUpdateBy(updateBy);
        history.setDeleted(0);
        assetDocHistoryMapper.insert(history);
    }

    private AssetDoc findDoc(Long tableMetadataId) {
        LambdaQueryWrapper<AssetDoc> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AssetDoc::getDeleted, 0)
                .eq(AssetDoc::getTableMetadataId, tableMetadataId);
        return assetDocMapper.selectOne(wrapper);
    }

    // ==================================================================
    // 元数据刷新
    // ==================================================================

    /**
     * 刷新单表元数据。
     *
     * <p>刻意复用既有的 {@link MetadataSyncService} 而不是自己写一段取元数据的逻辑：
     * 元数据的采集口径（字段顺序、类型归一化、注释来源）只能有一份实现，
     * 否则「同步任务采到的」与「手动刷新采到的」会不一致。</p>
     */
    public void refreshTableMetadata(Long tableId) {
        TableMetadata table = requireVisibleTable(tableId);
        if (table.getDatasourceId() == null) {
            throw new BusinessException("该表未关联数据源，无法刷新元数据");
        }
        if (table.getStorageEngine() == null) {
            throw new BusinessException("该表缺少存储引擎信息，无法刷新元数据");
        }
        boolean success = metadataSyncService.syncSingleTableMetadata(
                table.getDatasourceId(), table.getStorageEngine(),
                table.getDatabaseName(), table.getTableName(), "REFRESH", null);
        if (!success) {
            throw new BusinessException("元数据刷新失败，请确认数据源连通性后重试");
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 取表并校验可见性（不存在 404 / 无权限 403） */
    private TableMetadata requireVisibleTable(Long tableId) {
        if (tableId == null) {
            throw new BusinessException("tableId 不能为空");
        }
        TableMetadata table = tableMetadataMapper.selectById(tableId);
        if (table == null || (table.getDeleted() != null && table.getDeleted() == 1)) {
            throw new BusinessException(404, "资产不存在");
        }
        permissionSupport.checkSelectable(table);
        return table;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 供外部判断时间格式一致（详情页也用同一格式） */
    public static String formatTime(LocalDateTime time) {
        return time == null ? "" : time.format(TIME_FMT);
    }
}
