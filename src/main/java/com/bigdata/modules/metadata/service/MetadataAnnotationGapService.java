package com.bigdata.modules.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.common.utils.DeployEnvParser;
import com.bigdata.modules.asset.service.TableNameConventionParser;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.mapper.DatasourceConfigMapper;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.ColumnMetadataMapper;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import com.bigdata.modules.metadata.vo.AnnotationGapColumnVO;
import com.bigdata.modules.metadata.vo.AnnotationGapSummaryVO;
import com.bigdata.modules.metadata.vo.AnnotationGapTableVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 「未标注表 / 字段」清单（风险项 R1）。
 * <p>
 * 缺口背景：库表与指标描述不完整时，智能体只能靠列名猜语义，口径错误会被放大。
 * 但此前缺失是「不可见」的——需要逐个表点开才能发现没写注释。
 * 本服务把缺失汇总成一份可查询的清单：表注释为空的表、字段注释为空的字段，
 * 并按调用者的表权限过滤（与 {@code /metadata/tables} 保持一致的口径）。
 * </p>
 */
@Service
public class MetadataAnnotationGapService {

    private static final Logger log = LoggerFactory.getLogger(MetadataAnnotationGapService.class);

    /** 一次查询最多分析的列记录批次大小，避免 IN 子句过长 */
    private static final int COLUMN_BATCH_SIZE = 500;

    /** 默认扫描表数量上限 */
    public static final int DEFAULT_LIMIT = 500;
    /** 扫描表数量上限 */
    public static final int MAX_LIMIT = 2000;

    @Autowired
    private TableMetadataMapper tableMetadataMapper;

    @Autowired
    private ColumnMetadataMapper columnMetadataMapper;

    @Autowired
    private DatasourceConfigMapper datasourceConfigMapper;

    @Autowired
    private DataAccessPermissionService dataAccessPermissionService;

    /**
     * 统计表 / 字段注释缺失情况。
     *
     * @param datasourceId 可选，限定数据源
     * @param databaseName 可选，限定库
     * @param limit        扫描表数量上限，null 时取默认值
     * @param env          可选，限定环境（按库名后缀 _prod/_gray/_test 解析）；null 表示不按环境过滤
     * @param domainCode   可选，限定业务域（规范视角的「数仓名称」节点，表名第二段解析）；null 表示不过滤
     * @param layerCode    可选，限定分层（规范视角的「分层」节点，表名首段解析）；
     *                     {@link TableNameConventionParser#UNCLASSIFIED_LAYER} 命中未解析出分层的表；null 表示不过滤
     */
    public AnnotationGapSummaryVO query(Long datasourceId, String databaseName, Integer limit, String env,
                                        String domainCode, String layerCode) {
        int effectiveLimit = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);

        LambdaQueryWrapper<TableMetadata> wrapper = new LambdaQueryWrapper<>();
        if (datasourceId != null) {
            wrapper.eq(TableMetadata::getDatasourceId, datasourceId);
        }
        if (databaseName != null && !databaseName.isBlank()) {
            wrapper.eq(TableMetadata::getDatabaseName, databaseName);
        }
        String scopedEnv = DeployEnvParser.normalize(env);
        applyEnvFilter(wrapper, scopedEnv);
        applyConventionFilter(wrapper, layerCode, domainCode);
        wrapper.orderByAsc(TableMetadata::getDatabaseName).orderByAsc(TableMetadata::getTableName);
        // 多取一条用于判断是否被 limit 截断
        wrapper.last("LIMIT " + (effectiveLimit + 1));

        List<TableMetadata> scanned = tableMetadataMapper.selectList(wrapper);
        boolean truncated = scanned.size() > effectiveLimit;
        if (truncated) {
            scanned = new ArrayList<>(scanned.subList(0, effectiveLimit));
        }

        List<TableMetadata> visible = filterByPermission(scanned, datasourceId);
        visible = filterByConvention(visible, layerCode, domainCode);
        Map<Long, List<ColumnMetadata>> columnsByTable = loadColumns(visible);

        Map<Long, String> datasourceNames = new HashMap<>();
        AnnotationGapSummaryVO summary = new AnnotationGapSummaryVO();
        summary.setTableScanned(visible.size());
        summary.setTruncated(truncated);

        List<AnnotationGapTableVO> gaps = new ArrayList<>();
        int unannotatedTableCount = 0;
        int unannotatedColumnCount = 0;
        for (TableMetadata table : visible) {
            boolean tableCommentMissing = isBlank(table.getTableComment());
            List<ColumnMetadata> columns = columnsByTable.getOrDefault(table.getId(), List.of());
            List<AnnotationGapColumnVO> unannotatedColumns = new ArrayList<>();
            for (ColumnMetadata column : columns) {
                if (isBlank(column.getColumnComment())) {
                    AnnotationGapColumnVO vo = new AnnotationGapColumnVO();
                    vo.setColumnName(column.getColumnName());
                    vo.setColumnType(column.getColumnType());
                    vo.setOrdinalPosition(column.getOrdinalPosition());
                    vo.setIsPartition(column.getIsPartition());
                    unannotatedColumns.add(vo);
                }
            }
            if (!tableCommentMissing && unannotatedColumns.isEmpty()) {
                continue;
            }
            if (tableCommentMissing) {
                unannotatedTableCount++;
            }
            unannotatedColumnCount += unannotatedColumns.size();

            AnnotationGapTableVO vo = new AnnotationGapTableVO();
            vo.setId(table.getId());
            vo.setDatasourceId(table.getDatasourceId());
            vo.setDatasourceName(resolveDatasourceName(table.getDatasourceId(), datasourceNames));
            vo.setStorageEngine(table.getStorageEngine());
            vo.setDatabaseName(table.getDatabaseName());
            vo.setTableName(table.getTableName());
            vo.setTableComment(table.getTableComment());
            vo.setTableCommentMissing(tableCommentMissing);
            vo.setColumnCount(columns.size());
            vo.setUnannotatedColumnCount(unannotatedColumns.size());
            unannotatedColumns.sort(Comparator.comparing(AnnotationGapColumnVO::getOrdinalPosition,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            vo.setUnannotatedColumns(unannotatedColumns);
            gaps.add(vo);
        }

        summary.setUnannotatedTableCount(unannotatedTableCount);
        summary.setUnannotatedColumnCount(unannotatedColumnCount);
        summary.setTables(gaps);
        log.info("未标注元数据清单: 扫描表={}, 表注释缺失={}, 字段注释缺失={}, 截断={}",
                visible.size(), unannotatedTableCount, unannotatedColumnCount, truncated);
        return summary;
    }

    /**
     * 把「按环境过滤」下推到 SQL（库名后缀匹配）。
     *
     * <p>为什么必须在 SQL 层：环境过滤与 {@code LIMIT} 截断是同一层语义。
     * 若改成「先按 limit 取回、再在内存里过滤」，那么被截断掉的可能是别的环境的表，
     * 命中数与截断标记都会失去意义。</p>
     *
     * <p>模式 {@code %!_prod} 里 {@code !} 转义了 {@code _}：LIKE 中 {@code _} 是「任意单字符」，
     * 不转义会误配 {@code xaprod} 这类并不以下划线分段的库名。
     * 转义字符取 {@code !} 而不是反斜杠，是因为 {@code !} 在 MySQL / PostgreSQL 的字符串
     * 字面量里都不特殊，无需再处理「反斜杠本身还要不要再转义」的方言差异。</p>
     *
     * @param env 已归一化的环境码；null 表示不追加条件
     */
    static void applyEnvFilter(LambdaQueryWrapper<TableMetadata> wrapper, String env) {
        if (env == null) {
            return;
        }
        wrapper.apply("database_name LIKE {0} ESCAPE '!'", "%!" + "_" + env);
    }

    /**
     * 把「按分层 / 业务域过滤」下推为表名 LIKE 粗筛。
     *
     * <p>分层是表名首段、业务域是第二段，都来自命名规范、库里没有对应列，无法精确下推；
     * 因此这里只用一个「超集」模式减少候选行，再由 {@link #filterByConvention} 用解析结果精筛。
     * 之所以仍要下推：与 env 同理，粗筛能让 {@code LIMIT} 截断落在真正相关的表上，
     * 而不是先截断再过滤导致命中数虚低。</p>
     *
     * <p>占位码 {@code __unclassified} 代表「解析不出分段」，无法用表名模式表达，
     * 此时不追加 SQL 条件，完全交给内存精筛。</p>
     */
    static void applyConventionFilter(LambdaQueryWrapper<TableMetadata> wrapper, String layerCode, String domainCode) {
        String layer = concreteCategory(layerCode);
        String domain = concreteCategory(domainCode);
        if (layer == null && domain == null) {
            return;
        }
        String pattern;
        if (layer != null && domain != null) {
            pattern = layer + "!_" + domain + "!_%";
        } else if (layer != null) {
            pattern = layer + "!_%";
        } else {
            pattern = "%!_" + domain + "!_%";
        }
        wrapper.apply("table_name LIKE {0} ESCAPE '!'", pattern);
    }

    /** 过滤出可用于 SQL 下推的具体编码；空值与 {@code __unclassified} 占位码返回 null。 */
    private static String concreteCategory(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String trimmed = code.trim();
        return TableNameConventionParser.UNCLASSIFIED_LAYER.equalsIgnoreCase(trimmed) ? null : trimmed;
    }

    /**
     * 用命名规范解析结果精确过滤分层 / 业务域。
     *
     * <p>与盘点概览同一口径：解析不出分层的表归入 {@link TableNameConventionParser#UNCLASSIFIED_LAYER}，
     * 因此点击目录树的「未分类」节点也能命中这些表。</p>
     */
    private static List<TableMetadata> filterByConvention(List<TableMetadata> tables,
                                                          String layerCode, String domainCode) {
        boolean hasLayer = layerCode != null && !layerCode.isBlank();
        boolean hasDomain = domainCode != null && !domainCode.isBlank();
        if (!hasLayer && !hasDomain) {
            return tables;
        }
        String targetLayer = hasLayer ? layerCode.trim() : null;
        String targetDomain = hasDomain ? domainCode.trim() : null;
        List<TableMetadata> filtered = new ArrayList<>(tables.size());
        for (TableMetadata table : tables) {
            TableNameConventionParser.Parsed parsed = TableNameConventionParser.parse(table.getTableName());
            if (hasLayer && !matchesCategory(parsed.layerCode(), targetLayer)) {
                continue;
            }
            if (hasDomain && !matchesCategory(parsed.domainCode(), targetDomain)) {
                continue;
            }
            filtered.add(table);
        }
        return filtered;
    }

    /** 分类匹配：解析为空的段归入 UNCLASSIFIED_LAYER 占位，未分类节点可命中 */
    private static boolean matchesCategory(String actual, String target) {
        return target.equalsIgnoreCase(actual != null ? actual : TableNameConventionParser.UNCLASSIFIED_LAYER);
    }

    /** 按调用者的库 / 表权限过滤，口径与 /metadata/tables 一致。 */    private List<TableMetadata> filterByPermission(List<TableMetadata> tables, Long datasourceId) {
        if (dataAccessPermissionService.isAdmin()) {
            return tables;
        }
        String datasourceName = datasourceId != null
                ? resolveDatasourceName(datasourceId, new HashMap<>()) : null;
        Set<String> accessibleDatabases = dataAccessPermissionService.getAccessibleDatabaseNames(datasourceName);
        Map<String, Set<String>> accessibleTablesCache = new HashMap<>();
        return tables.stream().filter(table -> {
            if (accessibleDatabases != null && !accessibleDatabases.isEmpty()
                    && (table.getDatabaseName() == null || !accessibleDatabases.contains(table.getDatabaseName()))) {
                return false;
            }
            if (table.getDatabaseName() == null) {
                return true;
            }
            Set<String> accessibleTables = accessibleTablesCache.computeIfAbsent(table.getDatabaseName(),
                    db -> dataAccessPermissionService.getAccessibleTableNames(datasourceName, db));
            if (accessibleTables == null || accessibleTables.isEmpty()) {
                return true;
            }
            return table.getTableName() != null && accessibleTables.contains(table.getTableName());
        }).collect(Collectors.toList());
    }

    /** 批量加载这些表下的全部字段（分批次，避免 IN 子句过长）。 */
    private Map<Long, List<ColumnMetadata>> loadColumns(List<TableMetadata> tables) {
        List<Long> tableIds = tables.stream()
                .map(TableMetadata::getId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, List<ColumnMetadata>> result = new LinkedHashMap<>();
        if (tableIds.isEmpty()) {
            return result;
        }
        for (int start = 0; start < tableIds.size(); start += COLUMN_BATCH_SIZE) {
            List<Long> batch = tableIds.subList(start, Math.min(start + COLUMN_BATCH_SIZE, tableIds.size()));
            LambdaQueryWrapper<ColumnMetadata> wrapper = new LambdaQueryWrapper<>();
            wrapper.in(ColumnMetadata::getTableMetadataId, batch);
            List<ColumnMetadata> columns = columnMetadataMapper.selectList(wrapper);
            for (ColumnMetadata column : columns) {
                result.computeIfAbsent(column.getTableMetadataId(), k -> new ArrayList<>()).add(column);
            }
        }
        return result;
    }

    private String resolveDatasourceName(Long datasourceId, Map<Long, String> cache) {
        if (datasourceId == null) {
            return null;
        }
        String cached = cache.get(datasourceId);
        if (cached != null) {
            return cached;
        }
        DatasourceConfig config = datasourceConfigMapper.selectById(datasourceId);
        String name = config != null ? config.getDatasourceName() : null;
        if (name != null) {
            cache.put(datasourceId, name);
        }
        return name;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
