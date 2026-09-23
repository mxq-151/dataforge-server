package com.bigdata.modules.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bigdata.modules.asset.entity.AssetCategory;
import com.bigdata.modules.asset.entity.AssetTag;
import com.bigdata.modules.asset.mapper.AssetCategoryMapper;
import com.bigdata.modules.asset.mapper.AssetTagMapper;
import com.bigdata.modules.asset.vo.AssetCategoryVO;
import com.bigdata.modules.asset.vo.AssetTagVO;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 资产字典 —— 标签定义与分类（分层 / 业务域）。
 *
 * <h3>分类为什么是「配置 ∪ 发现」</h3>
 * <p>asset_category 存的是人工维护的分类：名称、排序、人工覆盖。
 * 但一个新平台刚上线时这张表是空的，如果分类接口只读这张表，
 * 前端筛选器就会是空下拉框 —— 而实际上库里已经有一批 {@code dwd_} 开头的表了。
 * 因此这里把「已配置的分类」与「从现存表名解析出来的分层/业务域」合并返回：
 * 配置优先（可改名、可排序），未被配置的编码按发现顺序补在后面。
 * 这样分类既不用等人工维护，人工一旦维护又立刻生效。</p>
 *
 * @author dataforge
 */
@Service
public class AssetMetaService {

    @Autowired
    private AssetTagMapper assetTagMapper;

    @Autowired
    private AssetCategoryMapper assetCategoryMapper;

    @Autowired
    private TableMetadataMapper tableMetadataMapper;

    /** 启用的标签定义 */
    public List<AssetTagVO> listTags() {
        LambdaQueryWrapper<AssetTag> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AssetTag::getDeleted, 0)
                .eq(AssetTag::getEnabled, 1)
                .orderByAsc(AssetTag::getTagCode);
        List<AssetTagVO> result = new ArrayList<>();
        for (AssetTag tag : assetTagMapper.selectList(wrapper)) {
            AssetTagVO vo = new AssetTagVO();
            vo.setId(tag.getId());
            vo.setTagCode(tag.getTagCode());
            vo.setTagName(tag.getTagName());
            vo.setColor(tag.getColor());
            result.add(vo);
        }
        return result;
    }

    /**
     * 分类列表 = 已配置分类 ∪ 现存表名解析结果。
     *
     * <p>只取表名一列，不把整表元数据拉回来 —— 这里只关心「出现了哪些分层/业务域」。</p>
     */
    public List<AssetCategoryVO> listCategories() {
        Map<String, AssetCategoryVO> merged = new LinkedHashMap<>();

        for (AssetCategory category : configuredCategories()) {
            AssetCategoryVO vo = new AssetCategoryVO();
            vo.setCode(category.getCategoryCode());
            vo.setName(category.getCategoryName());
            vo.setParentCode(category.getParentCode());
            vo.setCategoryKind(normalizeKind(category.getCategoryKind()));
            merged.put(key(vo.getCategoryKind(), vo.getCode()), vo);
        }

        for (String layer : TableNameConventionParser.DEFAULT_LAYERS) {
            merged.computeIfAbsent(key("layer", layer), k -> {
                AssetCategoryVO vo = new AssetCategoryVO();
                vo.setCode(layer);
                // 显示名统一走解析器，避免同一分层在目录树/盘点/筛选器里显示成不同的名字
                vo.setName(TableNameConventionParser.layerLabel(layer));
                vo.setParentCode("");
                vo.setCategoryKind("layer");
                return vo;
            });
        }

        for (String domain : discoverDomains()) {
            merged.computeIfAbsent(key("domain", domain), k -> {
                AssetCategoryVO vo = new AssetCategoryVO();
                vo.setCode(domain);
                vo.setName(domain);
                vo.setParentCode("");
                vo.setCategoryKind("domain");
                return vo;
            });
        }

        return new ArrayList<>(merged.values());
    }

    private List<AssetCategory> configuredCategories() {
        LambdaQueryWrapper<AssetCategory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AssetCategory::getDeleted, 0)
                .orderByAsc(AssetCategory::getSortOrder)
                .orderByAsc(AssetCategory::getCategoryCode);
        return assetCategoryMapper.selectList(wrapper);
    }

    /** 从现存表名里发现业务域编码 */
    private Set<String> discoverDomains() {
        LambdaQueryWrapper<TableMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(TableMetadata::getTableName).eq(TableMetadata::getDeleted, 0);
        Set<String> domains = new LinkedHashSet<>();
        for (TableMetadata table : tableMetadataMapper.selectList(wrapper)) {
            String domain = TableNameConventionParser.parse(table.getTableName()).domainCode();
            if (domain != null) {
                domains.add(domain);
            }
        }
        return domains;
    }

    private String normalizeKind(String kind) {
        return "domain".equalsIgnoreCase(kind) ? "domain" : "layer";
    }

    private String key(String kind, String code) {
        return kind + "\u0000" + code;
    }
}
