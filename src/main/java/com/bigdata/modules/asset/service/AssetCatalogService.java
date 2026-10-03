package com.bigdata.modules.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.common.utils.SecurityUtils;
import com.bigdata.modules.asset.entity.AssetCatalogDaily;
import com.bigdata.modules.asset.entity.AssetTag;
import com.bigdata.modules.asset.entity.AssetTagRel;
import com.bigdata.modules.asset.mapper.AssetTagMapper;
import com.bigdata.modules.asset.mapper.AssetTagRelMapper;
import com.bigdata.modules.asset.vo.AssetColumnVO;
import com.bigdata.modules.asset.vo.AssetIndicatorVO;
import com.bigdata.modules.asset.vo.AssetPreviewVO;
import com.bigdata.modules.asset.vo.AssetQualitySummaryVO;
import com.bigdata.modules.asset.vo.AssetSearchHitVO;
import com.bigdata.modules.asset.vo.AssetSearchResultVO;
import com.bigdata.modules.asset.vo.AssetTableDetailVO;
import com.bigdata.modules.asset.vo.AssetTagVO;
import com.bigdata.modules.asset.vo.CatalogTreeNodeVO;
import com.bigdata.modules.development.dto.CodeExecutionRequest;
import com.bigdata.modules.development.service.execution.CodeExecutionResult;
import com.bigdata.modules.development.service.execution.CodeExecutionService;
import com.bigdata.modules.development.service.execution.CodeType;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.devops.mapper.DatasourceConfigMapper;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.Indicator;
import com.bigdata.modules.metadata.entity.IndicatorField;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.ColumnMetadataMapper;
import com.bigdata.modules.metadata.mapper.IndicatorFieldMapper;
import com.bigdata.modules.metadata.mapper.IndicatorMapper;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import com.bigdata.modules.quality.entity.DqRule;
import com.bigdata.modules.quality.entity.DqRuleResult;
import com.bigdata.modules.quality.entity.DqRunInstance;
import com.bigdata.modules.quality.enums.DqDialect;
import com.bigdata.modules.quality.enums.QualityCheckStatus;
import com.bigdata.modules.quality.mapper.DqRuleMapper;
import com.bigdata.modules.quality.mapper.DqRuleResultMapper;
import com.bigdata.modules.quality.mapper.DqRunInstanceMapper;
import com.bigdata.modules.quality.sql.DqSqlBuilder;
import com.bigdata.modules.system.entity.SysTablePermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 资产目录查询 —— 目录树、检索、详情、预览。
 *
 * <h3>定位</h3>
 * <p>这是「人」侧找数认数的入口。它不产生新的元数据类别，而是把既有的
 * 「库表描述 + 指标描述 + 血缘 + 任务依赖」聚合到一个消费面上。</p>
 *
 * <h3>两条不可让步的规则</h3>
 * <ol>
 *   <li><b>权限在服务端过滤，且 fail-close</b>：树、检索、详情、预览四个入口
 *       全部走 {@link AssetPermissionSupport}。取不到权限就是不可见。</li>
 *   <li><b>预览必须经执行服务下发</b>：不复用「自己开连接查一下」的捷径，
 *       因为那样会绕开行级过滤与列白名单改写。</li>
 * </ol>
 *
 * @author dataforge
 */
@Service
public class AssetCatalogService {

    private static final Logger log = LoggerFactory.getLogger(AssetCatalogService.class);

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 检索时单个资产类型最多扫描多少条候选（防止 LIKE 全表扫描 + 权限过滤把请求拖死） */
    private static final int SEARCH_SCAN_CAP = 2000;

    /** 下拉建议最多返回多少条 */
    private static final int SUGGEST_LIMIT = 10;

    /** 预览默认行数 / 上限 */
    private static final int PREVIEW_DEFAULT_ROWS = 20;
    private static final int PREVIEW_MAX_ROWS = 200;

    @Autowired
    private TableMetadataMapper tableMetadataMapper;

    @Autowired
    private ColumnMetadataMapper columnMetadataMapper;

    @Autowired
    private IndicatorMapper indicatorMapper;

    @Autowired
    private IndicatorFieldMapper indicatorFieldMapper;

    @Autowired
    private AssetTagMapper assetTagMapper;

    @Autowired
    private AssetTagRelMapper assetTagRelMapper;

    @Autowired
    private DqRuleMapper dqRuleMapper;

    @Autowired
    private DqRunInstanceMapper dqRunInstanceMapper;

    @Autowired
    private DqRuleResultMapper dqRuleResultMapper;

    @Autowired
    private DatasourceConfigMapper datasourceConfigMapper;

    @Autowired
    private CodeExecutionService codeExecutionService;

    @Autowired
    private DataAccessPermissionService permissionService;

    @Autowired
    private AssetPermissionSupport permissionSupport;

    @Autowired
    private AssetHealthService healthService;

    @Autowired
    private AssetGovernanceService governanceService;

    @Autowired
    private AssetBookmarkService bookmarkService;

    @Autowired
    private AssetCatalogSnapshotService snapshotService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================================================================
    // 目录树
    // ==================================================================

    /**
     * 目录树。
     *
     * <p>读 T+1 的资产目录快照（{@code asset_catalog_daily}）；分层/业务域直接用快照里
     * 已固化的解析结果，不在查询期重新解析表名。快照尚未生成过时（首次部署）回退到
     * 实时计算，避免目录在这段窗口里显示为空。</p>
     *
     * @param view          engine=数据源→库→表；convention=分层→业务域→表
     * @param datasourceId  可选，限定数据源
     * @param databaseName  可选，限定库
     * @param keyword       可选，按表名/表注释模糊过滤（过滤后再建树，保证父节点计数与可见子节点一致）
     */
    public List<CatalogTreeNodeVO> tree(String view, Long datasourceId, String databaseName, String keyword) {
        LocalDate statDate = snapshotService.latestStatDate();
        List<TreeRow> rows = statDate == null
                ? liveTreeRows(datasourceId, databaseName, keyword)
                : snapshotTreeRows(statDate, datasourceId, databaseName, keyword);
        boolean convention = "convention".equalsIgnoreCase(view == null ? "" : view.trim());
        return convention ? buildConventionTree(rows) : buildEngineTree(rows);
    }

    /**
     * 目录树的最小行视图。
     *
     * <p>把「读快照」与「实时回退」两条来源统一成同一份建树输入，
     * 使树构建逻辑只写一遍；同时把分层/业务域从「表名解析」解耦成显式字段。</p>
     */
    private record TreeRow(Long id, Long datasourceId, String databaseName, String tableName,
                           String storageEngine, String layerCode, String domainCode,
                           boolean conventionParsed) {
    }

    /** 实时回退：直接读 table_metadata 并即时解析表名（快照不可用时使用） */
    private List<TreeRow> liveTreeRows(Long datasourceId, String databaseName, String keyword) {
        LambdaQueryWrapper<TableMetadata> wrapper = new LambdaQueryWrapper<>();
        if (datasourceId != null) {
            wrapper.eq(TableMetadata::getDatasourceId, datasourceId);
        }
        if (databaseName != null && !databaseName.isBlank()) {
            wrapper.eq(TableMetadata::getDatabaseName, databaseName.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String value = keyword.trim();
            wrapper.and(w -> w.like(TableMetadata::getTableName, value)
                    .or().like(TableMetadata::getTableComment, value));
        }
        wrapper.orderByAsc(TableMetadata::getDatabaseName).orderByAsc(TableMetadata::getTableName);

        List<TableMetadata> tables = permissionSupport.filterSelectable(tableMetadataMapper.selectList(wrapper));
        List<TreeRow> rows = new ArrayList<>(tables.size());
        for (TableMetadata table : tables) {
            TableNameConventionParser.Parsed parsed = TableNameConventionParser.parse(table.getTableName());
            rows.add(new TreeRow(table.getId(), table.getDatasourceId(), table.getDatabaseName(),
                    table.getTableName(), engineCodeOf(table),
                    parsed.layerCode(), parsed.domainCode(), parsed.parsed()));
        }
        return rows;
    }

    /** 读快照，取分层/业务域为已固化结果 */
    private List<TreeRow> snapshotTreeRows(LocalDate statDate, Long datasourceId,
                                           String databaseName, String keyword) {
        List<AssetCatalogDaily> snapshots = permissionSupport.filterSelectableSnapshots(
                snapshotService.load(statDate, datasourceId, databaseName, keyword));
        List<TreeRow> rows = new ArrayList<>(snapshots.size());
        for (AssetCatalogDaily row : snapshots) {
            rows.add(new TreeRow(row.getTableMetadataId(), row.getDatasourceId(), row.getDatabaseName(),
                    row.getTableName(), row.getStorageEngine(),
                    row.getLayerCode(), row.getDomainCode(), isParsed(row.getConventionParsed())));
        }
        return rows;
    }

    /** 引擎视角：数据源 → 库 → 表 */
    private List<CatalogTreeNodeVO> buildEngineTree(List<TreeRow> tables) {
        Map<Long, List<TreeRow>> byDatasource = new LinkedHashMap<>();
        for (TreeRow table : tables) {
            byDatasource.computeIfAbsent(table.datasourceId(), k -> new ArrayList<>()).add(table);
        }

        List<CatalogTreeNodeVO> roots = new ArrayList<>();
        for (Map.Entry<Long, List<TreeRow>> entry : byDatasource.entrySet()) {
            Long datasourceId = entry.getKey();
            List<TreeRow> datasourceTables = entry.getValue();

            CatalogTreeNodeVO datasourceNode = new CatalogTreeNodeVO();
            datasourceNode.setKey("engine:" + (datasourceId == null ? "unknown" : datasourceId));
            datasourceNode.setLabel(datasourceId == null ? "未关联数据源"
                    : nullToDefault(permissionSupport.datasourceName(datasourceId), "数据源#" + datasourceId));
            datasourceNode.setNodeKind("datasource");
            datasourceNode.setDatasourceId(datasourceId);
            datasourceNode.setAssetCount(datasourceTables.size());

            Map<String, List<TreeRow>> byDatabase = new LinkedHashMap<>();
            for (TreeRow table : datasourceTables) {
                byDatabase.computeIfAbsent(nullToDefault(table.databaseName(), "(未指定库)"),
                        k -> new ArrayList<>()).add(table);
            }
            datasourceNode.setStorageEngine(engineCodeOfRows(datasourceTables));

            List<CatalogTreeNodeVO> databaseNodes = new ArrayList<>();
            for (Map.Entry<String, List<TreeRow>> dbEntry : byDatabase.entrySet()) {
                CatalogTreeNodeVO databaseNode = new CatalogTreeNodeVO();
                databaseNode.setKey("db:" + (datasourceId == null ? "unknown" : datasourceId) + ":" + dbEntry.getKey());
                databaseNode.setLabel(dbEntry.getKey());
                databaseNode.setNodeKind("database");
                databaseNode.setDatasourceId(datasourceId);
                databaseNode.setDatabaseName(dbEntry.getKey());
                databaseNode.setAssetCount(dbEntry.getValue().size());
                databaseNode.setChildren(toTableNodes(dbEntry.getValue()));
                databaseNodes.add(databaseNode);
            }
            databaseNodes.sort(byCountThenLabel());
            datasourceNode.setChildren(databaseNodes);
            roots.add(datasourceNode);
        }
        roots.sort(byCountThenLabel());
        return roots;
    }

    /** 规范视角：分层 → 业务域 → 表 */
    private List<CatalogTreeNodeVO> buildConventionTree(List<TreeRow> tables) {
        Map<String, Map<String, List<TreeRow>>> byLayer = new LinkedHashMap<>();
        for (TreeRow table : tables) {
            String layer = table.layerCode() == null
                    ? TableNameConventionParser.UNCLASSIFIED_LAYER : table.layerCode();
            String domain = table.domainCode() == null
                    ? TableNameConventionParser.UNCLASSIFIED_LAYER : table.domainCode();
            byLayer.computeIfAbsent(layer, k -> new LinkedHashMap<>())
                    .computeIfAbsent(domain, k -> new ArrayList<>())
                    .add(table);
        }

        List<CatalogTreeNodeVO> roots = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<TreeRow>>> layerEntry : byLayer.entrySet()) {
            String layerCode = layerEntry.getKey();
            int total = layerEntry.getValue().values().stream().mapToInt(List::size).sum();

            CatalogTreeNodeVO layerNode = new CatalogTreeNodeVO();
            layerNode.setKey("layer:" + layerCode);
            layerNode.setLabel(TableNameConventionParser.layerLabel(layerCode));
            layerNode.setNodeKind("layer");
            layerNode.setLayerCode(layerCode);
            layerNode.setAssetCount(total);
            layerNode.setUnclassified(TableNameConventionParser.UNCLASSIFIED_LAYER.equals(layerCode));

            List<CatalogTreeNodeVO> domainNodes = new ArrayList<>();
            for (Map.Entry<String, List<TreeRow>> domainEntry : layerEntry.getValue().entrySet()) {
                String domainCode = domainEntry.getKey();
                CatalogTreeNodeVO domainNode = new CatalogTreeNodeVO();
                domainNode.setKey("domain:" + layerCode + "." + domainCode);
                domainNode.setLabel(TableNameConventionParser.UNCLASSIFIED_LAYER.equals(domainCode)
                        ? "未识别业务域" : domainCode);
                domainNode.setNodeKind("domain");
                domainNode.setLayerCode(layerCode);
                domainNode.setDomainCode(TableNameConventionParser.UNCLASSIFIED_LAYER.equals(domainCode)
                        ? null : domainCode);
                domainNode.setAssetCount(domainEntry.getValue().size());
                domainNode.setUnclassified(TableNameConventionParser.UNCLASSIFIED_LAYER.equals(domainCode));
                domainNode.setChildren(toTableNodes(domainEntry.getValue()));
                domainNodes.add(domainNode);
            }
            domainNodes.sort(byCountThenLabel());
            layerNode.setChildren(domainNodes);
            roots.add(layerNode);
        }

        // 「未分类」永远排在最后：它是一个待治理的收容所，不是正常分层
        roots.sort((left, right) -> {
            boolean leftUnclassified = Boolean.TRUE.equals(left.getUnclassified());
            boolean rightUnclassified = Boolean.TRUE.equals(right.getUnclassified());
            if (leftUnclassified != rightUnclassified) {
                return leftUnclassified ? 1 : -1;
            }
            return byCountThenLabel().compare(left, right);
        });
        return roots;
    }

    private List<CatalogTreeNodeVO> toTableNodes(List<TreeRow> tables) {
        List<CatalogTreeNodeVO> nodes = new ArrayList<>(tables.size());
        for (TreeRow table : tables) {
            CatalogTreeNodeVO node = new CatalogTreeNodeVO();
            node.setKey("table:" + table.id());
            node.setLabel(table.tableName());
            node.setNodeKind("table");
            node.setTableMetadataId(table.id());
            node.setDatasourceId(table.datasourceId());
            node.setStorageEngine(nullToDefault(table.storageEngine(), ""));
            node.setDatabaseName(table.databaseName());
            node.setTableName(table.tableName());
            node.setLayerCode(table.layerCode());
            node.setDomainCode(table.domainCode());
            node.setAssetCount(1);
            node.setUnclassified(!table.conventionParsed());
            nodes.add(node);
        }
        nodes.sort(Comparator.comparing(CatalogTreeNodeVO::getLabel,
                Comparator.nullsLast(String::compareToIgnoreCase)));
        return nodes;
    }

    private Comparator<CatalogTreeNodeVO> byCountThenLabel() {
        return Comparator
                .comparingInt((CatalogTreeNodeVO node) -> node.getAssetCount() == null ? 0 : node.getAssetCount())
                .reversed()
                .thenComparing(CatalogTreeNodeVO::getLabel, Comparator.nullsLast(String::compareToIgnoreCase));
    }

    // ==================================================================
    // 检索
    // ==================================================================

    /**
     * 统一检索：表 / 字段 / 指标三类。
     *
     * <h3>分页口径</h3>
     * <p>三类资产结构差异太大（表有负责人和分层、字段归属表、指标有业务口径），
     * 合成一个混合列表再分页会让「第 2 页」的内容取决于三类的相对数量，很反直觉。
     * 因此 {@code current/size} 分别作用于三个分组，前端三个页签各自翻页，
     * {@code total} 是三类命中数之和。</p>
     *
     * <h3>扫描上限</h3>
     * <p>字段检索必须先按关键词 LIKE 取候选、再过权限，无法在 SQL 里一次算准带权限的总数。
     * 因此单类最多扫描 {@value #SEARCH_SCAN_CAP} 条候选；命中数达到上限时
     * 返回的 total 是「已扫描范围内的命中数」，前端可提示用户收窄关键词。
     * 刻意不返回一个假的精确总数。</p>
     */
    public AssetSearchResultVO search(String keyword, String typesCsv, int current, int size,
                                     String layerCode, String domainCode) {
        AssetSearchResultVO result = new AssetSearchResultVO();
        result.setTables(List.of());
        result.setColumns(List.of());
        result.setIndicators(List.of());
        result.setTotal(0);
        if (keyword == null || keyword.isBlank()) {
            return result;
        }
        String value = keyword.trim();
        Set<String> types = parseTypes(typesCsv);
        int pageNo = Math.max(1, current);
        int pageSize = Math.max(1, Math.min(200, size));

        List<AssetSearchHitVO> tables = types.contains("table")
                ? searchTables(value, layerCode, domainCode) : List.of();
        List<AssetSearchHitVO> columns = types.contains("column")
                ? searchColumns(value, layerCode, domainCode) : List.of();
        List<AssetSearchHitVO> indicators = types.contains("indicator")
                ? searchIndicators(value) : List.of();

        result.setTotal(tables.size() + columns.size() + indicators.size());
        result.setTables(pageOf(tables, pageNo, pageSize));
        result.setColumns(pageOf(columns, pageNo, pageSize));
        result.setIndicators(pageOf(indicators, pageNo, pageSize));
        return result;
    }

    /** 表检索：优先走 T+1 快照，快照未生成时回退实时计算 */
    private List<AssetSearchHitVO> searchTables(String keyword, String layerCode, String domainCode) {
        LocalDate statDate = snapshotService.latestStatDate();
        return statDate == null
                ? searchTablesLive(keyword, layerCode, domainCode)
                : searchTablesFromSnapshot(statDate, keyword, layerCode, domainCode);
    }

    private List<AssetSearchHitVO> searchTablesLive(String keyword, String layerCode, String domainCode) {
        LambdaQueryWrapper<TableMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.like(TableMetadata::getTableName, keyword)
                        .or().like(TableMetadata::getTableComment, keyword));
        List<TableMetadata> matched = tableMetadataMapper.selectList(wrapper);
        if (matched.size() > SEARCH_SCAN_CAP) {
            matched = new ArrayList<>(matched.subList(0, SEARCH_SCAN_CAP));
        }
        List<AssetSearchHitVO> hits = new ArrayList<>();
        for (TableMetadata table : permissionSupport.filterSelectable(matched)) {
            TableNameConventionParser.Parsed parsed = TableNameConventionParser.parse(table.getTableName());
            if (!matchesCategory(parsed.layerCode(), parsed.domainCode(), layerCode, domainCode)) {
                continue;
            }
            AssetSearchHitVO hit = new AssetSearchHitVO();
            hit.setAssetType("table");
            hit.setId(table.getId());
            hit.setTableMetadataId(table.getId());
            hit.setTitle(table.getTableName());
            hit.setSubtitle(table.getDatabaseName() + "." + table.getTableName());
            hit.setMatchedText(table.getTableComment());
            hit.setOwnerName(table.getBusinessOwner());
            hit.setLayerCode(parsed.layerCode());
            hit.setDomainCode(parsed.domainCode());
            hits.add(hit);
        }
        return hits;
    }

    private List<AssetSearchHitVO> searchTablesFromSnapshot(LocalDate statDate, String keyword,
                                                           String layerCode, String domainCode) {
        List<AssetCatalogDaily> candidates = snapshotService.load(statDate, null, null, keyword);
        if (candidates.size() > SEARCH_SCAN_CAP) {
            candidates = new ArrayList<>(candidates.subList(0, SEARCH_SCAN_CAP));
        }
        List<AssetSearchHitVO> hits = new ArrayList<>();
        for (AssetCatalogDaily row : permissionSupport.filterSelectableSnapshots(candidates)) {
            if (!matchesCategory(row.getLayerCode(), row.getDomainCode(), layerCode, domainCode)) {
                continue;
            }
            AssetSearchHitVO hit = new AssetSearchHitVO();
            hit.setAssetType("table");
            hit.setId(row.getTableMetadataId());
            hit.setTableMetadataId(row.getTableMetadataId());
            hit.setTitle(row.getTableName());
            hit.setSubtitle(row.getDatabaseName() + "." + row.getTableName());
            hit.setMatchedText(row.getTableComment());
            hit.setOwnerName(row.getBusinessOwner());
            hit.setLayerCode(row.getLayerCode());
            hit.setDomainCode(row.getDomainCode());
            hits.add(hit);
        }
        return hits;
    }

    /**
     * 字段检索。
     *
     * <p>先按关键词取候选，再按「所属表是否可见」过滤 ——
     * 顺序很重要：反过来先取全部可见表的字段会让 IN 列表大到不可控。</p>
     */
    private List<AssetSearchHitVO> searchColumns(String keyword, String layerCode, String domainCode) {
        LambdaQueryWrapper<ColumnMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.like(ColumnMetadata::getColumnName, keyword)
                        .or().like(ColumnMetadata::getColumnComment, keyword))
                .orderByAsc(ColumnMetadata::getId);
        List<ColumnMetadata> candidates = columnMetadataMapper.selectList(wrapper);
        if (candidates.size() > SEARCH_SCAN_CAP) {
            candidates = new ArrayList<>(candidates.subList(0, SEARCH_SCAN_CAP));
        }

        Set<Long> tableIds = new LinkedHashSet<>();
        for (ColumnMetadata column : candidates) {
            if (column.getTableMetadataId() != null) {
                tableIds.add(column.getTableMetadataId());
            }
        }
        Map<Long, TableMetadata> visibleTables = loadVisibleTables(tableIds);
        List<AssetSearchHitVO> hits = new ArrayList<>();
        for (ColumnMetadata column : candidates) {
            TableMetadata table = visibleTables.get(column.getTableMetadataId());
            if (table == null) {
                continue;
            }
            TableNameConventionParser.Parsed parsed = TableNameConventionParser.parse(table.getTableName());
            if (!matchesCategory(parsed.layerCode(), parsed.domainCode(), layerCode, domainCode)) {
                continue;
            }
            AssetSearchHitVO hit = new AssetSearchHitVO();
            hit.setAssetType("column");
            hit.setId(column.getId());
            hit.setTableMetadataId(table.getId());
            hit.setTitle(column.getColumnName());
            hit.setSubtitle(table.getTableName() + "." + column.getColumnName());
            hit.setMatchedText(column.getColumnComment());
            hit.setOwnerName(table.getBusinessOwner());
            hit.setLayerCode(parsed.layerCode());
            hit.setDomainCode(parsed.domainCode());
            hits.add(hit);
        }
        return hits;
    }

    private List<AssetSearchHitVO> searchIndicators(String keyword) {
        LambdaQueryWrapper<Indicator> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.like(Indicator::getName, keyword)
                        .or().like(Indicator::getCode, keyword)
                        .or().like(Indicator::getBusinessDefinition, keyword))
                .orderByAsc(Indicator::getId);
        List<Indicator> candidates = indicatorMapper.selectList(wrapper);
        if (candidates.size() > SEARCH_SCAN_CAP) {
            candidates = new ArrayList<>(candidates.subList(0, SEARCH_SCAN_CAP));
        }

        // 指标按项目授权，与既有 indicators 接口保持同一套可见性口径
        Set<String> allowedProjects = permissionService.getAccessibleProjectCodes();
        List<AssetSearchHitVO> hits = new ArrayList<>();
        for (Indicator indicator : candidates) {
            if (allowedProjects != null && !allowedProjects.contains(indicator.getProjectCode())) {
                continue;
            }
            AssetSearchHitVO hit = new AssetSearchHitVO();
            hit.setAssetType("indicator");
            hit.setId(indicator.getId());
            hit.setTitle(indicator.getName());
            hit.setSubtitle(indicator.getCode());
            hit.setMatchedText(indicator.getBusinessDefinition());
            hit.setOwnerName(indicator.getBusinessOwner());
            hits.add(hit);
        }
        return hits;
    }

    /** 输入建议：只返回表名，且已过权限 */
    public List<String> suggest(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        LambdaQueryWrapper<TableMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(TableMetadata::getTableName)
                .like(TableMetadata::getTableName, keyword.trim())
                .orderByAsc(TableMetadata::getTableName);
        List<TableMetadata> matched = tableMetadataMapper.selectList(wrapper);
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TableMetadata table : permissionSupport.filterSelectable(matched)) {
            String name = table.getTableName();
            if (name != null && seen.add(name)) {
                result.add(name);
                if (result.size() >= SUGGEST_LIMIT) {
                    break;
                }
            }
        }
        return result;
    }

    // ==================================================================
    // 详情
    // ==================================================================

    /** 资产详情聚合：基础信息 + 字段 + 标签 + 使用说明 + 热度 + 治理项计数 */
    public AssetTableDetailVO detail(Long tableId) {
        TableMetadata table = requireVisibleTable(tableId);

        AssetTableDetailVO vo = new AssetTableDetailVO();
        vo.setId(table.getId());
        vo.setDatasourceId(table.getDatasourceId());
        vo.setDatasourceName(nullToDefault(permissionSupport.datasourceName(table.getDatasourceId()), ""));
        vo.setStorageEngine(engineCodeOf(table));
        vo.setDatabaseName(nullToDefault(table.getDatabaseName(), ""));
        vo.setTableName(table.getTableName());
        vo.setTableType(nullToDefault(table.getTableType(), ""));
        vo.setTableComment(nullToDefault(table.getTableComment(), ""));
        vo.setDescription(governanceService.docContentOf(table));
        vo.setBusinessOwner(nullToDefault(table.getBusinessOwner(), ""));
        vo.setTechOwner(nullToDefault(table.getTechOwner(), ""));
        vo.setTopic(nullToDefault(table.getTopic(), ""));
        vo.setVisibility(nullToDefault(table.getVisibility(), "internal"));
        vo.setCategoryCode(nullToDefault(table.getCategoryCode(), ""));

        TableNameConventionParser.Parsed parsed = TableNameConventionParser.parse(table.getTableName());
        // 人工覆盖优先：categoryCode 存在时按它定分层（形如 dwd 或 layer:dwd）
        String effectiveLayer = resolveLayer(table, parsed);
        vo.setLayerCode(nullToDefault(effectiveLayer, ""));
        vo.setDomainCode(nullToDefault(parsed.domainCode(), ""));
        vo.setConventionParsed(parsed.parsed());
        vo.setRefreshCycle(nullToDefault(parsed.refreshCycle(), ""));

        vo.setAssetLevel(nullToDefault(table.getAssetLevel(), ""));
        vo.setRowCount(table.getRowCount());
        vo.setSizeBytes(table.getSizeBytes());
        vo.setHotDays(table.getHotDays() == null ? 90 : table.getHotDays());
        vo.setColdDays(table.getColdDays() == null ? 180 : table.getColdDays());
        vo.setKeepDays(table.getKeepDays() == null ? 720 : table.getKeepDays());
        vo.setStorageFormat(nullToDefault(table.getStorageFormat(), ""));
        vo.setCompression(nullToDefault(table.getCompression(), ""));
        vo.setLocation(nullToDefault(table.getLocation(), ""));
        vo.setPartitionColumns(parsePartitionColumns(table.getPartitionColumns()));
        vo.setLastModifyTime(formatTime(table.getLastModifyTime()));
        vo.setSyncTime(formatTime(table.getSyncTime()));

        vo.setBrowseCount30d(table.getBrowseCnt30d() == null ? 0 : table.getBrowseCnt30d());
        vo.setReadCount30d(table.getReadCnt30d() == null ? 0 : table.getReadCnt30d());
        vo.setBookmarkCount(table.getBookmarkCnt() == null ? 0 : table.getBookmarkCnt());
        vo.setLastAccessTime(formatTime(table.getLastAccessTime()));

        vo.setColumns(columnsOf(table));
        vo.setTags(tagsOf(table.getId()));
        vo.setHealthIssueCount(healthService.countIssues(table));

        Long userId = SecurityUtils.getCurrentUserId();
        vo.setBookmarked(bookmarkService.isBookmarked(userId, "table", table.getId()));
        return vo;
    }

    /**
     * 字段清单（含列级权限裁剪）。
     *
     * <p>列级权限语义与平台既有实现保持一致：
     * {@code getAllowedColumns} 返回 null 表示不限制，返回空集合表示没有任何可见列。
     * 后者必须返回空列表而不是「不过滤」—— 否则配置失误会变成放大权限。</p>
     */
    private List<AssetColumnVO> columnsOf(TableMetadata table) {
        LambdaQueryWrapper<ColumnMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ColumnMetadata::getTableMetadataId, table.getId())
                .orderByAsc(ColumnMetadata::getOrdinalPosition);
        List<ColumnMetadata> columns = columnMetadataMapper.selectList(wrapper);

        Set<String> allowed = permissionService.getAllowedColumns(
                permissionSupport.datasourceName(table.getDatasourceId()),
                table.getDatabaseName(), table.getTableName());
        Set<String> allowedLower = null;
        if (allowed != null) {
            allowedLower = new HashSet<>();
            for (String column : allowed) {
                if (column != null && !column.isBlank()) {
                    allowedLower.add(column.trim().toLowerCase(Locale.ROOT));
                }
            }
        }

        List<AssetColumnVO> result = new ArrayList<>();
        for (ColumnMetadata column : columns) {
            if (allowedLower != null
                    && (column.getColumnName() == null
                    || !allowedLower.contains(column.getColumnName().toLowerCase(Locale.ROOT)))) {
                continue;
            }
            AssetColumnVO vo = new AssetColumnVO();
            vo.setId(column.getId());
            vo.setColumnName(column.getColumnName());
            vo.setColumnType(nullToDefault(column.getColumnType(), ""));
            vo.setColumnComment(nullToDefault(column.getColumnComment(), ""));
            vo.setBusinessDesc(nullToDefault(column.getBusinessDesc(), ""));
            vo.setIsPartition(intToBoolean(column.getIsPartition()));
            vo.setIsPrimaryKey(intToBoolean(column.getIsPrimaryKey()));
            vo.setNullable(intToBoolean(column.getNullable()));
            vo.setOrdinalPosition(column.getOrdinalPosition() == null ? 0 : column.getOrdinalPosition());
            vo.setSecurityLevel(nullToDefault(column.getSecurityLevel(), ""));
            vo.setHotScore(column.getHotScore() == null ? 0 : column.getHotScore());
            result.add(vo);
        }
        return result;
    }

    private List<AssetTagVO> tagsOf(Long tableId) {
        LambdaQueryWrapper<AssetTagRel> relWrapper = new LambdaQueryWrapper<>();
        relWrapper.eq(AssetTagRel::getAssetType, "table")
                .eq(AssetTagRel::getAssetId, tableId);
        List<AssetTagRel> rels = assetTagRelMapper.selectList(relWrapper);
        if (rels.isEmpty()) {
            return List.of();
        }
        Set<Long> tagIds = new LinkedHashSet<>();
        for (AssetTagRel rel : rels) {
            if (rel.getTagId() != null) {
                tagIds.add(rel.getTagId());
            }
        }
        List<AssetTagVO> result = new ArrayList<>();
        for (AssetTag tag : assetTagMapper.selectBatchIds(tagIds)) {
            AssetTagVO vo = new AssetTagVO();
            vo.setId(tag.getId());
            vo.setTagCode(tag.getTagCode());
            vo.setTagName(tag.getTagName());
            vo.setColor(tag.getColor());
            result.add(vo);
        }
        return result;
    }

    // ==================================================================
    // 关联指标 / 质量摘要
    // ==================================================================

    /**
     * 该表关联的指标（基于 indicators_fields 反查）。
     *
     * <p>匹配口径是「库名 + 表名」，数据源名作补充条件 ——
     * 指标字段表里数据源名可能为空（历史数据），此时不做数据源限制，
     * 避免因为一个可空字段把已有指标漏掉。</p>
     */
    public List<AssetIndicatorVO> indicators(Long tableId) {
        TableMetadata table = requireVisibleTable(tableId);

        LambdaQueryWrapper<IndicatorField> fieldWrapper = new LambdaQueryWrapper<>();
        fieldWrapper.eq(IndicatorField::getDbName, table.getDatabaseName())
                .eq(IndicatorField::getTblName, table.getTableName());
        List<IndicatorField> fields = indicatorFieldMapper.selectList(fieldWrapper);
        if (fields.isEmpty()) {
            return List.of();
        }

        Set<Long> indicatorIds = new LinkedHashSet<>();
        for (IndicatorField field : fields) {
            if (field.getIndicatorsId() != null) {
                indicatorIds.add(field.getIndicatorsId());
            }
        }
        Set<String> allowedProjects = permissionService.getAccessibleProjectCodes();

        Map<Long, List<String>> fieldsByIndicator = new HashMap<>();
        for (IndicatorField field : fields) {
            if (field.getIndicatorsId() != null && field.getFieldName() != null) {
                fieldsByIndicator.computeIfAbsent(field.getIndicatorsId(), k -> new ArrayList<>())
                        .add(field.getFieldName());
            }
        }

        List<AssetIndicatorVO> result = new ArrayList<>();
        for (Indicator indicator : indicatorMapper.selectBatchIds(indicatorIds)) {
            if (allowedProjects != null && !allowedProjects.contains(indicator.getProjectCode())) {
                continue;
            }
            List<String> fieldNames = fieldsByIndicator.getOrDefault(indicator.getId(), List.of());
            // 一个指标引用多个字段时展开成多行：前端每条都要能点回字段
            if (fieldNames.isEmpty()) {
                result.add(toIndicatorVO(indicator, null));
            } else {
                for (String fieldName : fieldNames) {
                    result.add(toIndicatorVO(indicator, fieldName));
                }
            }
        }
        return result;
    }

    private AssetIndicatorVO toIndicatorVO(Indicator indicator, String fieldName) {
        AssetIndicatorVO vo = new AssetIndicatorVO();
        vo.setId(indicator.getId());
        vo.setCode(nullToDefault(indicator.getCode(), ""));
        vo.setName(nullToDefault(indicator.getName(), ""));
        vo.setTopic(nullToDefault(indicator.getTopic(), ""));
        vo.setBusinessOwner(nullToDefault(indicator.getBusinessOwner(), ""));
        vo.setFieldName(fieldName);
        return vo;
    }

    /** 该表的质量规则摘要（跨模块读 quality） */
    public AssetQualitySummaryVO quality(Long tableId) {
        TableMetadata table = requireVisibleTable(tableId);

        LambdaQueryWrapper<DqRule> ruleWrapper = new LambdaQueryWrapper<>();
        ruleWrapper.eq(DqRule::getDatabaseName, table.getDatabaseName())
                .eq(DqRule::getTableName, table.getTableName());
        List<DqRule> rules = dqRuleMapper.selectList(ruleWrapper);
        // 数据源已绑定的规则再按数据源筛一次，避免同名库表串味
        List<DqRule> scoped = new ArrayList<>();
        for (DqRule rule : rules) {
            if (rule.getDatasourceId() == null || rule.getDatasourceId().equals(table.getDatasourceId())) {
                scoped.add(rule);
            }
        }

        AssetQualitySummaryVO vo = new AssetQualitySummaryVO();
        vo.setRuleCount(scoped.size());
        int enabled = 0;
        Set<Long> ruleIds = new LinkedHashSet<>();
        for (DqRule rule : scoped) {
            if (rule.getEnabled() == null || rule.getEnabled() == 1) {
                enabled++;
            }
            ruleIds.add(rule.getId());
        }
        vo.setEnabledRuleCount(enabled);

        DqRunInstance lastRun = lastRunOf(table);
        if (lastRun == null) {
            vo.setLastRunStatus("");
            vo.setLastRunTime("");
            vo.setErrorCount(0);
            vo.setFailCount(0);
            vo.setProblemRuleCount(0);
            return vo;
        }
        vo.setLastRunStatus(nullToDefault(lastRun.getStatus(), ""));
        vo.setLastRunTime(formatTime(lastRun.getEndTime() != null ? lastRun.getEndTime() : lastRun.getStartTime()));

        LambdaQueryWrapper<DqRuleResult> resultWrapper = new LambdaQueryWrapper<>();
        resultWrapper.eq(DqRuleResult::getRunId, lastRun.getId());
        List<DqRuleResult> results = dqRuleResultMapper.selectList(resultWrapper);

        int errorCount = 0;
        int failCount = 0;
        Set<Long> problemRules = new LinkedHashSet<>();
        for (DqRuleResult row : results) {
            String status = row.getStatus() == null ? "" : row.getStatus();
            if (QualityCheckStatus.ERROR.name().equals(status)) {
                errorCount++;
                problemRules.add(row.getRuleId());
            } else if (QualityCheckStatus.FAILED.name().equals(status)) {
                failCount++;
                problemRules.add(row.getRuleId());
            }
        }
        vo.setErrorCount(errorCount);
        vo.setFailCount(failCount);
        vo.setProblemRuleCount(problemRules.size());
        log.debug("资产质量摘要: table={}.{}, 规则数={}, 最近运行={}, error={}, fail={}",
                table.getDatabaseName(), table.getTableName(), scoped.size(), lastRun.getId(), errorCount, failCount);
        return vo;
    }

    private DqRunInstance lastRunOf(TableMetadata table) {
        LambdaQueryWrapper<DqRunInstance> wrapper = new LambdaQueryWrapper<>();
        if (table.getDatasourceId() != null) {
            wrapper.eq(DqRunInstance::getTargetDatasourceId, table.getDatasourceId());
        }
        wrapper.eq(DqRunInstance::getTargetDatabaseName, table.getDatabaseName())
                .eq(DqRunInstance::getTargetTableName, table.getTableName())
                .orderByDesc(DqRunInstance::getId);
        // 用分页插件生成方言无关的 LIMIT
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<DqRunInstance> page =
                dqRunInstanceMapper.selectPage(
                        new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 1), wrapper);
        List<DqRunInstance> records = page.getRecords();
        return records.isEmpty() ? null : records.get(0);
    }

    // ==================================================================
    // 数据预览
    // ==================================================================

    /**
     * 数据预览 —— 会真实下发查询。
     *
     * <h3>为什么必须走执行服务</h3>
     * <p>预览是唯一一个会把<b>真实数据行</b>送到浏览器的入口。如果自己开连接查，
     * 行级过滤与列白名单就全绕过去了。走 {@link CodeExecutionService} 之后，
     * {@code SqlPermissionRewriter} 会在下发前把行过滤条件注入 WHERE、
     * 把投影裁剪到列白名单，权限约束落在引擎侧而不是事后过滤。</p>
     *
     * <h3>三层防护</h3>
     * <ol>
     *   <li>表级 SELECT 权限：无权限直接 403；</li>
     *   <li>行/列改写：由执行链路完成（引擎侧生效）；</li>
     *   <li>列交集 + 数值脱敏：本方法在返回前再做一次列名交集（防链路差异），
     *       并按 data_masking_rule 对取值做脱敏 —— 平台此前只标注了脱敏类型，
     *       没有对值做替换，这一环在这里补上。</li>
     * </ol>
     *
     * @param limit     行数上限，默认 20、硬上限 200（浏览器表格不需要更多）
     * @param partition 分区条件，写法与质检规则一致（{@code dt=${yyyyMMdd}}）；
     *                  校验逻辑复用 DqSqlBuilder.partitionCondition，不另写一套
     */
    public AssetPreviewVO preview(Long tableId, Integer limit, String partition) {
        TableMetadata table = requireVisibleTable(tableId);
        if (table.getDatasourceId() == null) {
            throw new BusinessException("该表未关联数据源，无法预览数据");
        }
        DatasourceConfig config = datasourceConfigMapper.selectById(table.getDatasourceId());
        if (config == null) {
            throw new BusinessException("数据源配置不存在，无法预览数据");
        }
        DatasourceType engine = config.getDatasourceType();
        if (engine == null || !engine.supportsLanguage(CodeType.SQL)) {
            throw new BusinessException("数据源 " + config.getDatasourceName()
                    + " 不支持 SQL 查询，无法预览数据");
        }
        DqDialect dialect = DqDialect.of(engine);

        int rows = limit == null ? PREVIEW_DEFAULT_ROWS : Math.max(1, Math.min(PREVIEW_MAX_ROWS, limit));
        String sql = buildPreviewSql(dialect, table, rows, partition);
        log.info("资产预览下发: table={}.{}, datasource={}, sql={}",
                table.getDatabaseName(), table.getTableName(), config.getDatasourceName(), sql);

        CodeExecutionRequest request = new CodeExecutionRequest();
        request.setCodeContent(sql);
        request.setCodeType(CodeType.SQL.getCode());
        request.setEngineType(engine.getCode());
        request.setDatasources(List.of(config.getDatasourceName()));
        CodeExecutionResult execution = codeExecutionService.executeCode(
                request, SecurityUtils.getCurrentUserId(), SecurityUtils.getCurrentUsername());

        if (!execution.isSuccess()) {
            // 执行失败的原因如实回传（可能是权限改写拒绝、也可能是目标表不可达），
            // 不要伪装成空结果 —— 空结果会被误解为「这张表就是空的」
            throw new BusinessException("预览失败：" + nullToDefault(execution.getErrorMessage(), "未知原因"));
        }

        AssetPreviewVO vo = new AssetPreviewVO();
        List<String> columns = execution.getColumns() == null ? List.of() : execution.getColumns();
        columns = applyColumnPermission(table, columns);
        vo.setColumns(columns);

        Map<String, String> maskingRules = maskingRulesOf(table);
        List<List<String>> maskedRows = new ArrayList<>();
        List<List<String>> rawRows = execution.getRows() == null ? List.of() : execution.getRows();
        for (List<String> row : rawRows) {
            if (row == null) {
                continue;
            }
            List<String> masked = new ArrayList<>(row.size());
            for (int i = 0; i < row.size(); i++) {
                String value = row.get(i);
                String columnName = i < columns.size() ? columns.get(i) : null;
                String maskingType = columnName == null ? null : maskingRules.get(columnName);
                masked.add(maskingType == null ? value : AssetDataMasker.mask(value, maskingType));
            }
            // 列被裁剪后行长度应与列数一致，多余单元格直接丢弃而不是错位拼接
            while (masked.size() > columns.size()) {
                masked.remove(masked.size() - 1);
            }
            maskedRows.add(masked);
        }
        vo.setRows(maskedRows);
        return vo;
    }

    private String buildPreviewSql(DqDialect dialect, TableMetadata table, int rows, String partition) {
        String qualified = DqSqlBuilder.qualifiedTable(
                dialect, table.getDatabaseName(), table.getTableName(), "表");
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(qualified);
        String condition = DqSqlBuilder.partitionCondition(partition, LocalDate.now());
        if (!condition.isBlank()) {
            sql.append(" WHERE ").append(condition);
        }
        if (dialect.isLimitSupported()) {
            sql.append(" LIMIT ").append(rows);
        }
        return sql.toString();
    }

    /** 返回前再取一次列交集（与执行链路里的列白名单双保险） */
    private List<String> applyColumnPermission(TableMetadata table, List<String> columns) {
        Set<String> allowed = permissionService.getAllowedColumns(
                permissionSupport.datasourceName(table.getDatasourceId()),
                table.getDatabaseName(), table.getTableName());
        if (allowed == null) {
            return columns;
        }
        Set<String> allowedLower = new HashSet<>();
        for (String column : allowed) {
            if (column != null && !column.isBlank()) {
                allowedLower.add(column.trim().toLowerCase(Locale.ROOT));
            }
        }
        List<String> filtered = new ArrayList<>();
        for (String column : columns) {
            if (column != null && allowedLower.contains(column.toLowerCase(Locale.ROOT))) {
                filtered.add(column);
            }
        }
        return filtered;
    }

    /** 该表生效的脱敏规则：列名 → 脱敏类型 */
    private Map<String, String> maskingRulesOf(TableMetadata table) {
        SysTablePermission permission = permissionService.getMergedTablePermission(
                permissionSupport.datasourceName(table.getDatasourceId()),
                table.getDatabaseName(), table.getTableName());
        if (permission == null || permission.getDataMaskingRule() == null
                || permission.getDataMaskingRule().isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(permission.getDataMaskingRule());
            Map<String, String> rules = new HashMap<>();
            root.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (value.isTextual()) {
                    rules.put(entry.getKey(), value.asText());
                } else if (value.isObject() && value.path("type").isTextual()) {
                    rules.put(entry.getKey(), value.path("type").asText());
                } else {
                    // 规则结构不认识时按最严处理，避免「解析不了就放行」
                    rules.put(entry.getKey(), "full");
                }
            });
            return rules;
        } catch (Exception e) {
            log.warn("解析脱敏规则失败，按最严处理（全部遮蔽）: table={}.{}, 原因={}",
                    table.getDatabaseName(), table.getTableName(), e.getMessage());
            return maskingRulesFailClosed(table);
        }
    }

    private Map<String, String> maskingRulesFailClosed(TableMetadata table) {
        LambdaQueryWrapper<ColumnMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(ColumnMetadata::getColumnName)
                .eq(ColumnMetadata::getTableMetadataId, table.getId());
        Map<String, String> rules = new HashMap<>();
        for (ColumnMetadata column : columnMetadataMapper.selectList(wrapper)) {
            if (column.getColumnName() != null) {
                rules.put(column.getColumnName(), "full");
            }
        }
        return rules;
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private TableMetadata requireVisibleTable(Long tableId) {
        if (tableId == null) {
            throw new BusinessException("tableId 不能为空");
        }
        TableMetadata table = tableMetadataMapper.selectById(tableId);
        if (table == null) {
            throw new BusinessException(404, "资产不存在");
        }
        permissionSupport.checkSelectable(table);
        return table;
    }

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

    /** 分层/业务域筛选：任一为空表示不筛该类 */
    private boolean matchesCategory(String rowLayer, String rowDomain,
                                    String layerCode, String domainCode) {
        if (layerCode != null && !layerCode.isBlank()
                && !layerCode.trim().equalsIgnoreCase(nullToDefault(rowLayer, ""))) {
            return false;
        }
        return domainCode == null || domainCode.isBlank()
                || domainCode.trim().equalsIgnoreCase(nullToDefault(rowDomain, ""));
    }

    /**
     * 有效分层：人工覆盖的分类编码优先。
     * 允许 {@code dwd} 与 {@code layer:dwd} 两种写法（分类列表返回的是纯编码，
     * 但人工覆盖时前端可能带上维度前缀）。
     */
    private String resolveLayer(TableMetadata table, TableNameConventionParser.Parsed parsed) {
        String categoryCode = table.getCategoryCode();
        if (categoryCode != null && !categoryCode.isBlank()) {
            String value = categoryCode.trim();
            int colon = value.indexOf(':');
            if (colon >= 0) {
                value = value.substring(colon + 1);
            }
            if (!value.isBlank()) {
                return value;
            }
        }
        return parsed.layerCode();
    }

    private List<String> parsePartitionColumns(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String value = raw.trim();
        List<String> result = new ArrayList<>();
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node.isArray()) {
                node.forEach(item -> {
                    if (item.isTextual() && !item.asText().isBlank()) {
                        result.add(item.asText().trim());
                    }
                });
                return result;
            }
        } catch (Exception e) {
            // 不是 JSON：按逗号分隔的普通文本处理（Hive DDL 提取路径可能存成 "dt, hour"）
            log.debug("分区字段非 JSON 格式，按逗号分隔解析: {}", value);
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim().replace("[", "").replace("]", "").replace("\"", "");
            if (!trimmed.isBlank()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private String engineCodeOfRows(List<TreeRow> tables) {
        for (TreeRow table : tables) {
            String code = table.storageEngine();
            if (code != null && !code.isBlank()) {
                return code;
            }
        }
        return "";
    }

    private static boolean isParsed(Integer conventionParsed) {
        return conventionParsed != null && conventionParsed == 1;
    }

    private String engineCodeOf(TableMetadata table) {
        DatasourceType engine = table.getStorageEngine();
        return engine == null ? "" : engine.getCode();
    }

    private static Set<String> parseTypes(String typesCsv) {
        Set<String> result = new HashSet<>();
        if (typesCsv == null || typesCsv.isBlank()) {
            result.add("table");
            result.add("column");
            result.add("indicator");
            return result;
        }
        for (String part : typesCsv.split(",")) {
            String value = part.trim().toLowerCase(Locale.ROOT);
            if ("table".equals(value) || "column".equals(value) || "indicator".equals(value)) {
                result.add(value);
            }
        }
        if (result.isEmpty()) {
            result.add("table");
            result.add("column");
            result.add("indicator");
        }
        return result;
    }

    private static <T> List<T> pageOf(List<T> items, int pageNo, int pageSize) {
        int from = (pageNo - 1) * pageSize;
        if (from >= items.size()) {
            return List.of();
        }
        int to = Math.min(items.size(), from + pageSize);
        return new ArrayList<>(items.subList(from, to));
    }

    private static String formatTime(java.util.Date date) {
        if (date == null) {
            return "";
        }
        if (date instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().format(TIME_FMT);
        }
        return new java.sql.Timestamp(date.getTime()).toLocalDateTime().format(TIME_FMT);
    }

    private static String formatTime(LocalDateTime time) {
        return time == null ? "" : time.format(TIME_FMT);
    }

    private static boolean intToBoolean(Integer value) {
        return value != null && value == 1;
    }

    private static String nullToDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
