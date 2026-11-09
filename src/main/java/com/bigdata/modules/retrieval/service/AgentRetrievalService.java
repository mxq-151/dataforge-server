package com.bigdata.modules.retrieval.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.common.utils.DeployEnvParser;
import com.bigdata.modules.asset.service.AssetPermissionSupport;
import com.bigdata.modules.asset.service.TableNameConventionParser;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.Indicator;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.ColumnMetadataMapper;
import com.bigdata.modules.metadata.mapper.IndicatorMapper;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import com.bigdata.modules.retrieval.vo.AgentFilters;
import com.bigdata.modules.retrieval.vo.AgentSearchItem;
import com.bigdata.modules.retrieval.vo.AgentSearchRequest;
import com.bigdata.modules.retrieval.vo.AgentSearchResponse;
import com.bigdata.modules.retrieval.vo.NotFoundHint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 智能体库表检索 —— 「召回 + 硬过滤 + 规则打分」三段。
 *
 * <h3>它替代了什么</h3>
 * <p>旧入口 {@code GET /metadata/agent/search} 是「单关键词 LIKE + LIMIT 2000 + 返回全量字段」：
 * 环境、分层、业务域都不参与过滤，返回的是整表结构。结果是模型在一堆同名表里挑一个，
 * 挑错也不会有人发现。本服务把「结构约束」在 SQL 层就落下去，把「相关度」用可解释的规则
 * 打分算出来，并只返回判断所需的摘要。</p>
 *
 * <h3>三条不可让步的规则</h3>
 * <ol>
 *   <li><b>权限 fail-close</b>：所有候选先过 {@link AssetPermissionSupport}，
 *       未授权的表名不得出现在响应里（包括 notFound.nearest）。</li>
 *   <li><b>不做语义猜词</b>：本层不内置同义词表。业务词与英文表名的映射靠
 *       keywords 多值召回 + 知识库术语表（后续接入），而不是在代码里写死。</li>
 *   <li><b>截断要明说</b>：命中数超过 limit 或扫描触顶时 {@code truncated=true}，
 *       不返回一个看起来精确、实际残缺的 total。</li>
 * </ol>
 *
 * @author dataforge
 */
@Service
public class AgentRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(AgentRetrievalService.class);

    /** 单个关键词最多扫描多少行（防止 LIKE 全表扫描拖死请求） */
    private static final int SCAN_PER_KEYWORD = 400;

    /** 单次检索最多接受多少个关键词 */
    private static final int MAX_KEYWORDS = 8;

    /** 关键词长度上限（防止超长字符串直达 SQL） */
    private static final int MAX_KEYWORD_LENGTH = 64;

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    /** 未命中时近似候选的条数 */
    private static final int NEAREST_LIMIT = 3;

    @Autowired
    private TableMetadataMapper tableMetadataMapper;

    @Autowired
    private ColumnMetadataMapper columnMetadataMapper;

    @Autowired
    private IndicatorMapper indicatorMapper;

    @Autowired
    private AssetPermissionSupport permissionSupport;

    @Autowired
    private DataAccessPermissionService permissionService;

    /** 知识库术语表：把业务口语扩展成平台命名，扩展词只看不写回 */
    @Autowired
    private RetrievalTermService retrievalTermService;

    public AgentSearchResponse search(AgentSearchRequest request) {
        AgentSearchRequest req = request == null ? new AgentSearchRequest() : request;
        List<String> keywords = normalizeKeywords(req.getKeywords());
        Set<String> types = normalizeTypes(req.getTypes());
        AgentFilters filters = normalizeFilters(req.getFilters());
        String env = DeployEnvParser.normalize(filters.getEnv());
        int limit = clampLimit(req.getLimit());

        log.info("智能体检索: intent={}, keywords={}, types={}, env={}, layers={}, domains={}, limit={}",
                req.getIntent(), keywords, types, env, filters.getLayers(), filters.getDomains(), limit);

        AgentSearchResponse response = new AgentSearchResponse();
        response.setAppliedFilters(filters);
        if (keywords.isEmpty()) {
            response.setTotal(0);
            NotFoundHint<AgentSearchItem> hint = new NotFoundHint<>();
            hint.setTried(List.of());
            hint.setNearest(List.of());
            hint.setSuggest("缺少 keywords：请给出业务词或英文命名片段后重试。");
            response.setNotFound(hint);
            return response;
        }

        ScanState state = new ScanState();
        // 术语表扩展只增加召回面，不改写用户给的原词：扩展词单独回显，便于模型判断
        // 「这条命中是因为我说的词，还是因为同义扩展」。
        List<String> expansions = retrievalTermService.expand(keywords);
        List<String> recallKeywords = new ArrayList<>(keywords);
        for (String expansion : expansions) {
            if (expansion != null && recallKeywords.stream().noneMatch(expansion::equalsIgnoreCase)) {
                recallKeywords.add(expansion);
            }
        }
        response.setExpandedKeywords(expansions);

        List<AgentSearchItem> all = collect(recallKeywords, types, filters, env, state);
        all.sort(byScoreThenName());

        response.setTotal(all.size());
        response.setTruncated(all.size() > limit || state.scanCapped);
        response.setItems(new ArrayList<>(all.subList(0, Math.min(all.size(), limit))));

        if (all.isEmpty()) {
            response.setNotFound(buildNotFound(keywords, recallKeywords, types));
        }
        return response;
    }

    /**
     * 多关键词并集召回 → 权限过滤 → 结构过滤 → 打分。
     *
     * <p>并集而非交集：同一实体在业务口径、英文命名、分层前缀三种说法下都应能命中；
     * 交集会让「订单明细」+「order」这类跨语言组合直接归零。</p>
     */
    private List<AgentSearchItem> collect(List<String> keywords, Set<String> types,
                                          AgentFilters filters, String env, ScanState state) {
        List<AgentSearchItem> items = new ArrayList<>();
        if (types.contains("table")) {
            items.addAll(searchTables(keywords, filters, env, state));
        }
        if (types.contains("column")) {
            items.addAll(searchColumns(keywords, filters, env, state));
        }
        if (types.contains("indicator")) {
            items.addAll(searchIndicators(keywords, filters));
        }
        return dedupe(items);
    }

    // ==================================================================
    // 表检索
    // ==================================================================

    private List<AgentSearchItem> searchTables(List<String> keywords, AgentFilters filters,
                                               String env, ScanState state) {
        Map<Long, TableMetadata> candidates = new LinkedHashMap<>();
        for (String keyword : keywords) {
            // 用字符串列名的 QueryWrapper 而非 LambdaQueryWrapper：本服务要在无 Spring 容器的
            // 单测里直接跑，而 Lambda 方式依赖 MyBatis-Plus 的实体元数据缓存（只有容器启动时才建）。
            // 大范围检索用投影列而不是整行，减少 IO —— 与资产检索的 suggest 同一取舍。
            QueryWrapper<TableMetadata> wrapper = new QueryWrapper<>();
            wrapper.select("id", "datasource_id", "database_name", "table_name",
                            "table_comment", "business_owner", "category_code")
                    .and(w -> w.like("table_name", keyword)
                            .or().like("table_comment", keyword))
                    .orderByAsc("table_name")
                    .last("LIMIT " + SCAN_PER_KEYWORD);
            List<TableMetadata> matched = tableMetadataMapper.selectList(wrapper);
            if (matched.size() >= SCAN_PER_KEYWORD) {
                state.scanCapped = true;
            }
            for (TableMetadata table : permissionSupport.filterSelectable(matched)) {
                if (table.getId() != null) {
                    candidates.putIfAbsent(table.getId(), table);
                }
            }
        }

        List<AgentSearchItem> items = new ArrayList<>();
        for (TableMetadata table : candidates.values()) {
            TableNameConventionParser.Parsed parsed = TableNameConventionParser.parse(table.getTableName());
            if (!matchesFilters(table, parsed, filters, env)) {
                continue;
            }
            Hit hit = scoreTable(table, keywords);
            if (hit == null) {
                continue;
            }
            AgentSearchItem item = new AgentSearchItem();
            item.setKind("table");
            item.setId(table.getId());
            item.setName(table.getTableName());
            item.setDatabase(table.getDatabaseName());
            item.setComment(table.getTableComment());
            item.setLayerCode(parsed.layerCode());
            item.setDomainCode(parsed.domainCode());
            item.setEnv(DeployEnvParser.parseEnv(table.getDatabaseName()));
            item.setScore(hit.score());
            item.setMatchedBy(hit.matchedBy());
            item.setEvidence(hit.evidence());
            item.setMatchedKeywords(hit.matchedKeywords());
            items.add(item);
        }
        return items;
    }

    // ==================================================================
    // 字段检索（命中后落到所属表）
    // ==================================================================

    private List<AgentSearchItem> searchColumns(List<String> keywords, AgentFilters filters,
                                                String env, ScanState state) {
        Map<Long, ColumnMetadata> candidates = new LinkedHashMap<>();
        for (String keyword : keywords) {
            QueryWrapper<ColumnMetadata> wrapper = new QueryWrapper<>();
            wrapper.select("id", "table_metadata_id", "column_name", "column_comment")
                    .and(w -> w.like("column_name", keyword)
                            .or().like("column_comment", keyword))
                    .orderByAsc("id")
                    .last("LIMIT " + SCAN_PER_KEYWORD);
            List<ColumnMetadata> matched = columnMetadataMapper.selectList(wrapper);
            if (matched.size() >= SCAN_PER_KEYWORD) {
                state.scanCapped = true;
            }
            for (ColumnMetadata column : matched) {
                if (column.getId() != null) {
                    candidates.putIfAbsent(column.getId(), column);
                }
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        Set<Long> tableIds = new LinkedHashSet<>();
        for (ColumnMetadata column : candidates.values()) {
            if (column.getTableMetadataId() != null) {
                tableIds.add(column.getTableMetadataId());
            }
        }
        Map<Long, TableMetadata> visibleTables = loadVisibleTables(tableIds);

        List<AgentSearchItem> items = new ArrayList<>();
        for (ColumnMetadata column : candidates.values()) {
            TableMetadata table = visibleTables.get(column.getTableMetadataId());
            if (table == null) {
                continue;
            }
            TableNameConventionParser.Parsed parsed = TableNameConventionParser.parse(table.getTableName());
            if (!matchesFilters(table, parsed, filters, env)) {
                continue;
            }
            Hit hit = scoreColumn(column, keywords);
            if (hit == null) {
                continue;
            }
            AgentSearchItem item = new AgentSearchItem();
            item.setKind("column");
            // 字段命中必须回到所属表：模型真正要建的是表，字段只是定位手段
            item.setId(table.getId());
            item.setName(column.getColumnName());
            item.setTable(table.getTableName());
            item.setDatabase(table.getDatabaseName());
            item.setComment(column.getColumnComment());
            item.setLayerCode(parsed.layerCode());
            item.setDomainCode(parsed.domainCode());
            item.setEnv(DeployEnvParser.parseEnv(table.getDatabaseName()));
            item.setScore(hit.score());
            item.setMatchedBy("column");
            item.setEvidence(hit.evidence() + "（所属表 " + table.getTableName() + "）");
            item.setMatchedKeywords(hit.matchedKeywords());
            items.add(item);
        }
        return items;
    }

    private Map<Long, TableMetadata> loadVisibleTables(Set<Long> tableIds) {
        if (tableIds.isEmpty()) {
            return Map.of();
        }
        List<TableMetadata> tables = tableMetadataMapper.selectBatchIds(tableIds);
        Map<Long, TableMetadata> result = new LinkedHashMap<>();
        for (TableMetadata table : permissionSupport.filterSelectable(tables)) {
            if (table.getId() != null) {
                result.put(table.getId(), table);
            }
        }
        return result;
    }

    // ==================================================================
    // 指标检索
    // ==================================================================

    private List<AgentSearchItem> searchIndicators(List<String> keywords, AgentFilters filters) {
        Map<Long, Indicator> candidates = new LinkedHashMap<>();
        for (String keyword : keywords) {
            QueryWrapper<Indicator> wrapper = new QueryWrapper<>();
            wrapper.select("id", "project_code", "topic", "name", "code",
                            "business_definition", "business_owner")
                    .and(w -> w.like("name", keyword)
                            .or().like("code", keyword)
                            .or().like("business_definition", keyword))
                    .orderByAsc("id");
            for (Indicator indicator : indicatorMapper.selectList(wrapper)) {
                if (indicator.getId() != null) {
                    candidates.putIfAbsent(indicator.getId(), indicator);
                }
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        // 指标按项目授权，与既有接口同一套可见性口径（null 表示管理员不过滤）
        Set<String> allowedProjects = permissionService.getAccessibleProjectCodes();
        List<String> layers = filters.getLayers();
        List<String> domains = filters.getDomains();
        if ((layers != null && !layers.isEmpty()) || (domains != null && !domains.isEmpty())) {
            // 指标没有分层/业务域维度：显式传了这两项说明模型把表级约束误用到指标上，
            // 直接返回空，比返回一堆不满足约束的指标更不容易误导。
            return List.of();
        }

        List<AgentSearchItem> items = new ArrayList<>();
        for (Indicator indicator : candidates.values()) {
            if (allowedProjects != null && !allowedProjects.contains(indicator.getProjectCode())) {
                continue;
            }
            Hit hit = scoreIndicator(indicator, keywords);
            if (hit == null) {
                continue;
            }
            AgentSearchItem item = new AgentSearchItem();
            item.setKind("indicator");
            item.setId(indicator.getId());
            item.setName(indicator.getName());
            item.setComment(abbreviate(indicator.getBusinessDefinition(), 160));
            item.setScore(hit.score());
            item.setMatchedBy(hit.matchedBy());
            item.setEvidence(hit.evidence());
            item.setMatchedKeywords(hit.matchedKeywords());
            items.add(item);
        }
        return items;
    }

    // ==================================================================
    // 过滤 / 打分
    // ==================================================================

    /**
     * 结构过滤：环境、分层、业务域、数据源。
     *
     * <p>四者都是「确定的枚举约束」，任何一个不满足都直接剔除 ——
     * 这类判断不该留给模型在候选里挑。</p>
     */
    private boolean matchesFilters(TableMetadata table, TableNameConventionParser.Parsed parsed,
                                   AgentFilters filters, String env) {
        if (!DeployEnvParser.matches(table.getDatabaseName(), env)) {
            return false;
        }
        if (!matchesAny(filters.getLayers(), parsed.layerCode())) {
            return false;
        }
        if (!matchesAny(filters.getDomains(), parsed.domainCode())) {
            return false;
        }
        if (filters.getDatasources() != null && !filters.getDatasources().isEmpty()) {
            String datasourceName = permissionSupport.datasourceName(table.getDatasourceId());
            if (datasourceName == null) {
                return false;
            }
            boolean matched = false;
            for (String expected : filters.getDatasources()) {
                if (expected != null && expected.trim().equalsIgnoreCase(datasourceName.trim())) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    /** 空列表表示「该项不过滤」；表名解析不出分层/业务域时，显式过滤视为不匹配 */
    private boolean matchesAny(List<String> expected, String actual) {
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        if (actual == null) {
            return false;
        }
        for (String value : expected) {
            if (value != null && value.trim().equalsIgnoreCase(actual.trim())) {
                return true;
            }
        }
        return false;
    }

    private Hit scoreTable(TableMetadata table, List<String> keywords) {
        String name = table.getTableName();
        String comment = table.getTableComment();
        Best best = new Best();
        for (String keyword : keywords) {
            String normalized = keyword.toLowerCase(Locale.ROOT);
            String lowerName = name == null ? "" : name.toLowerCase(Locale.ROOT);
            String lowerComment = comment == null ? "" : comment.toLowerCase(Locale.ROOT);

            if (lowerName.equals(normalized)) {
                best.offer(1.0, "name", "表名与关键词「" + keyword + "」完全一致", keyword);
            } else if (lowerName.startsWith(normalized)) {
                best.offer(0.92, "name", "表名前缀命中「" + keyword + "」", keyword);
            } else if (lowerName.contains(normalized)) {
                best.offer(0.84, "name", "表名包含「" + keyword + "」", keyword);
            }
            if (lowerComment.contains(normalized)) {
                best.offer(0.72, "comment", "表注释包含「" + keyword + "」", keyword);
            }
        }
        return best.toHit();
    }

    private Hit scoreColumn(ColumnMetadata column, List<String> keywords) {
        String name = column.getColumnName();
        String comment = column.getColumnComment();
        Best best = new Best();
        for (String keyword : keywords) {
            String normalized = keyword.toLowerCase(Locale.ROOT);
            String lowerName = name == null ? "" : name.toLowerCase(Locale.ROOT);
            String lowerComment = comment == null ? "" : comment.toLowerCase(Locale.ROOT);

            if (lowerName.equals(normalized)) {
                best.offer(1.0, "column", "字段名与关键词「" + keyword + "」完全一致", keyword);
            } else if (lowerName.startsWith(normalized)) {
                best.offer(0.9, "column", "字段名前缀命中「" + keyword + "」", keyword);
            } else if (lowerName.contains(normalized)) {
                best.offer(0.82, "column", "字段名包含「" + keyword + "」", keyword);
            }
            if (lowerComment.contains(normalized)) {
                best.offer(0.7, "column", "字段注释包含「" + keyword + "」", keyword);
            }
        }
        return best.toHit();
    }

    private Hit scoreIndicator(Indicator indicator, List<String> keywords) {
        String name = indicator.getName();
        String code = indicator.getCode();
        String definition = indicator.getBusinessDefinition();
        Best best = new Best();
        for (String keyword : keywords) {
            String normalized = keyword.toLowerCase(Locale.ROOT);
            String lowerName = name == null ? "" : name.toLowerCase(Locale.ROOT);
            String lowerCode = code == null ? "" : code.toLowerCase(Locale.ROOT);
            String lowerDefinition = definition == null ? "" : definition.toLowerCase(Locale.ROOT);

            if (lowerName.equals(normalized) || lowerCode.equals(normalized)) {
                best.offer(1.0, "name", "指标名/编码与关键词「" + keyword + "」完全一致", keyword);
            } else if (lowerName.contains(normalized) || lowerCode.contains(normalized)) {
                best.offer(0.88, "name", "指标名/编码包含「" + keyword + "」", keyword);
            }
            if (lowerDefinition.contains(normalized)) {
                best.offer(0.74, "definition", "指标口径包含「" + keyword + "」", keyword);
            }
        }
        return best.toHit();
    }

    private List<AgentSearchItem> dedupe(List<AgentSearchItem> items) {
        Map<String, AgentSearchItem> unique = new LinkedHashMap<>();
        for (AgentSearchItem item : items) {
            String key = item.getKind() + "\u0000" + item.getId() + "\u0000" + item.getName();
            AgentSearchItem existing = unique.get(key);
            if (existing == null || (item.getScore() != null && existing.getScore() != null
                    && item.getScore() > existing.getScore())) {
                unique.put(key, item);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private Comparator<AgentSearchItem> byScoreThenName() {
        return Comparator
                .comparingDouble((AgentSearchItem item) -> item.getScore() == null ? 0 : item.getScore())
                .reversed()
                .thenComparing(item -> item.getName() == null ? "" : item.getName(),
                        String.CASE_INSENSITIVE_ORDER);
    }

    // ==================================================================
    // 未命中提示
    // ==================================================================

    /**
     * 未命中时给「放宽约束后的近似候选」。
     *
     * <p>放宽的是环境/分层/业务域/数据源，<b>绝不放宽权限</b>：
     * 近似候选同样要经过 fail-close 过滤，否则等于用「提示」绕过了权限。</p>
     */
    private NotFoundHint<AgentSearchItem> buildNotFound(List<String> keywords, List<String> recallKeywords,
                                                        Set<String> types) {
        NotFoundHint<AgentSearchItem> hint = new NotFoundHint<>();
        hint.setTried(keywords);
        try {
            AgentFilters relaxed = new AgentFilters();
            List<AgentSearchItem> nearest = collect(recallKeywords, types, relaxed, null, new ScanState());
            nearest.sort(byScoreThenName());
            hint.setNearest(new ArrayList<>(nearest.subList(0, Math.min(nearest.size(), NEAREST_LIMIT))));
        } catch (RuntimeException e) {
            log.warn("检索未命中后放宽约束失败，忽略近似候选: {}", e.getMessage());
            hint.setNearest(List.of());
        }
        if (!hint.getNearest().isEmpty()) {
            hint.setSuggest("未找到满足全部约束的对象；nearest 是放宽环境/分层/业务域后的近似候选"
                    + "（仍受权限限制）。请核对约束是否写错，或用 nearest 中的命名重试。");
        } else {
            hint.setSuggest("元数据中未检索到相关对象。请改用业务实体英文名（如订单→order）、"
                    + "分层前缀（ods/dwd/dws/ads/dim）或知识库中出现的命名重试；"
                    + "确认不存在时必须向用户报告未找到，禁止臆造库表名。");
        }
        return hint;
    }

    // ==================================================================
    // 入参归一化
    // ==================================================================

    private List<String> normalizeKeywords(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : raw) {
            if (value == null) {
                continue;
            }
            String keyword = value.trim();
            if (keyword.isEmpty()) {
                continue;
            }
            if (keyword.length() > MAX_KEYWORD_LENGTH) {
                keyword = keyword.substring(0, MAX_KEYWORD_LENGTH);
            }
            unique.add(keyword);
            if (unique.size() >= MAX_KEYWORDS) {
                break;
            }
        }
        return new ArrayList<>(unique);
    }

    private Set<String> normalizeTypes(List<String> raw) {
        Set<String> result = new LinkedHashSet<>();
        if (raw != null) {
            for (String value : raw) {
                if (value == null) {
                    continue;
                }
                String type = value.trim().toLowerCase(Locale.ROOT);
                if ("table".equals(type) || "column".equals(type) || "indicator".equals(type)) {
                    result.add(type);
                }
            }
        }
        if (result.isEmpty()) {
            result.add("table");
        }
        return result;
    }

    private AgentFilters normalizeFilters(AgentFilters raw) {
        AgentFilters filters = new AgentFilters();
        if (raw == null) {
            return filters;
        }
        filters.setEnv(DeployEnvParser.normalize(raw.getEnv()));
        filters.setLayers(cleanList(raw.getLayers()));
        filters.setDomains(cleanList(raw.getDomains()));
        filters.setDatasources(cleanList(raw.getDatasources()));
        return filters;
    }

    private List<String> cleanList(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : raw) {
            if (value != null && !value.isBlank()) {
                unique.add(value.trim());
            }
        }
        return new ArrayList<>(unique);
    }

    private int clampLimit(Integer raw) {
        if (raw == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(MAX_LIMIT, raw));
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }

    /** 单次请求内的扫描状态（不跨请求复用，避免并发串扰） */
    private static final class ScanState {
        private boolean scanCapped;
    }

    /** 打分结果 */
    private record Hit(double score, String matchedBy, String evidence, List<String> matchedKeywords) {
    }

    /**
     * 多关键词取最佳命中，并按命中词数轻微加权。
     *
     * <p>加权上限 0.06：命中词多说明更贴合意图，但不能让「注释里恰好出现三个词」的表
     * 压过「表名完全一致」的表 —— 名称精确匹配仍然是最强信号。</p>
     */
    private static final class Best {
        private double score = -1;
        private String matchedBy;
        private String evidence;
        private final List<String> keywords = new ArrayList<>();

        void offer(double candidate, String by, String why, String keyword) {
            if (!keywords.contains(keyword)) {
                keywords.add(keyword);
            }
            if (candidate > score) {
                score = candidate;
                matchedBy = by;
                evidence = why;
            }
        }

        Hit toHit() {
            if (score < 0) {
                return null;
            }
            double bonus = Math.min(0.06, 0.03 * Math.max(0, keywords.size() - 1));
            double finalScore = Math.min(1.0, score + bonus);
            String finalEvidence = keywords.size() > 1
                    ? evidence + "；同时命中 " + String.join("、", keywords)
                    : evidence;
            return new Hit(round(finalScore), matchedBy, finalEvidence, List.copyOf(keywords));
        }

        private static double round(double value) {
            return Math.round(value * 1000.0) / 1000.0;
        }
    }
}
