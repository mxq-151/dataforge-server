package com.bigdata.modules.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.modules.asset.entity.AssetCatalogDaily;
import com.bigdata.modules.asset.entity.AssetDoc;
import com.bigdata.modules.asset.entity.AssetTagRel;
import com.bigdata.modules.asset.mapper.AssetCatalogDailyMapper;
import com.bigdata.modules.asset.mapper.AssetDocMapper;
import com.bigdata.modules.asset.mapper.AssetTagRelMapper;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.devops.mapper.DatasourceConfigMapper;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.IndicatorField;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.ColumnMetadataMapper;
import com.bigdata.modules.metadata.mapper.IndicatorFieldMapper;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 资产目录日快照 —— 生成 {@code asset_catalog_daily}，并对外提供按统计日的读取能力。
 *
 * <h3>为什么在 T+1 固化</h3>
 * <p>目录树、检索、盘点、治理项原先都在查询时临时拼接：全量扫描 table_metadata、
 * 内存里按表名解析分层与业务域、逐表重算治理项与关联计数。这些都是「一天之内不会变」
 * 的结果，放在每次请求里算纯属浪费。本服务把它们按天算一次落库，查询侧只读快照。</p>
 *
 * <h3>口径复用而不是重写</h3>
 * <p>治理项判定与质量规则计数直接调用 {@link AssetHealthService#buildContext} /
 * {@link AssetHealthService#issuesFor}，分层与业务域解析调用
 * {@link TableNameConventionParser#parse}。快照只做「把既有口径的结果存下来」，
 * 不引入第二套判定逻辑 —— 否则两处口径迟早漂移。</p>
 *
 * <h3>权限不落库</h3>
 * <p>快照存的是全量资产。可见性仍由各查询入口按当前用户实时过滤
 * （见 {@link AssetPermissionSupport}），授权变更即时生效，不被快照滞后一天。</p>
 *
 * @author dataforge
 */
@Service
public class AssetCatalogSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(AssetCatalogSnapshotService.class);

    /** 快照保留天数：超过则随每日生成一并清理 */
    public static final int RETENTION_DAYS = 180;

    /** 指标关联计数的匹配键分隔符（库名 + 表名） */
    private static final String KEY_SEP = "\u0000";

    @Autowired
    private TableMetadataMapper tableMetadataMapper;

    @Autowired
    private ColumnMetadataMapper columnMetadataMapper;

    @Autowired
    private AssetTagRelMapper assetTagRelMapper;

    @Autowired
    private AssetDocMapper assetDocMapper;

    @Autowired
    private IndicatorFieldMapper indicatorFieldMapper;

    @Autowired
    private DatasourceConfigMapper datasourceConfigMapper;

    @Autowired
    private AssetCatalogDailyMapper assetCatalogDailyMapper;

    @Autowired
    private AssetHealthService healthService;

    /** 生成摘要，便于定时任务日志与单测断言 */
    public record SnapshotResult(LocalDate statDate, int tableCount) {
    }

    // ==================================================================
    // 读取
    // ==================================================================

    /**
     * 最新可用的快照日。
     *
     * @return null 表示尚未生成过任何快照 —— 调用方应回退到实时计算，
     *         而不是把目录显示成空的
     */
    public LocalDate latestStatDate() {
        LambdaQueryWrapper<AssetCatalogDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(AssetCatalogDaily::getStatDate)
                .orderByDesc(AssetCatalogDaily::getStatDate);
        Page<AssetCatalogDaily> page = new Page<>(1, 1, false);
        List<AssetCatalogDaily> rows = assetCatalogDailyMapper.selectPage(page, wrapper).getRecords();
        return rows.isEmpty() ? null : rows.get(0).getStatDate();
    }

    /** 按统计日取快照（可选限定数据源） */
    public List<AssetCatalogDaily> load(LocalDate statDate, Long datasourceId) {
        return load(statDate, datasourceId, null, null);
    }

    /**
     * 全部可用快照日（升序）。
     *
     * <p>供「与上期对比」类看板挑选基线：历史快照从首次部署起逐日累积。</p>
     */
    public List<LocalDate> listStatDates() {
        LambdaQueryWrapper<AssetCatalogDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(AssetCatalogDaily::getStatDate)
                .groupBy(AssetCatalogDaily::getStatDate)
                .orderByAsc(AssetCatalogDaily::getStatDate);
        List<LocalDate> dates = new ArrayList<>();
        for (AssetCatalogDaily row : assetCatalogDailyMapper.selectList(wrapper)) {
            if (row.getStatDate() != null) {
                dates.add(row.getStatDate());
            }
        }
        return dates;
    }

    /**
     * 按统计日取快照。
     *
     * @param databaseName 可选，限定库
     * @param keyword      可选，按表名 / 表注释模糊过滤
     */
    public List<AssetCatalogDaily> load(LocalDate statDate, Long datasourceId,
                                        String databaseName, String keyword) {
        LambdaQueryWrapper<AssetCatalogDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AssetCatalogDaily::getStatDate, statDate);
        if (datasourceId != null) {
            wrapper.eq(AssetCatalogDaily::getDatasourceId, datasourceId);
        }
        if (databaseName != null && !databaseName.isBlank()) {
            wrapper.eq(AssetCatalogDaily::getDatabaseName, databaseName.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String value = keyword.trim();
            wrapper.and(w -> w.like(AssetCatalogDaily::getTableName, value)
                    .or().like(AssetCatalogDaily::getTableComment, value));
        }
        wrapper.orderByAsc(AssetCatalogDaily::getDatabaseName)
                .orderByAsc(AssetCatalogDaily::getTableName)
                .orderByAsc(AssetCatalogDaily::getId);
        return assetCatalogDailyMapper.selectList(wrapper);
    }

    // ==================================================================
    // 生成
    // ==================================================================

    /**
     * 生成某个统计日的快照。幂等：先删当日旧数据再整体重写，重复执行结果一致。
     *
     * <p>调用方通常是每日定时任务，传入「昨天」。单测可直接传任意日期。</p>
     */
    @Transactional
    public SnapshotResult snapshot(LocalDate statDate) {
        List<TableMetadata> tables = tableMetadataMapper.selectList(
                new LambdaQueryWrapper<TableMetadata>());

        deleteByStatDate(statDate);
        if (tables.isEmpty()) {
            log.info("资产目录快照：{} 无纳管资产", statDate);
            return new SnapshotResult(statDate, 0);
        }

        // 跨表证据一次性加载，避免逐表查询退化成 N 次往返（与 AssetHealthService 同思路）
        AssetHealthService.Context healthContext = healthService.buildContext(tables);
        Map<Long, Integer> columnCounts = loadColumnCounts();
        Map<Long, Integer> tagCounts = loadTagCounts();
        Set<Long> tablesWithDoc = loadTablesWithDoc();
        Map<String, Integer> indicatorCounts = loadIndicatorCounts();
        Map<Long, String> datasourceNames = loadDatasourceNames();

        List<AssetCatalogDaily> rows = new ArrayList<>(tables.size());
        for (TableMetadata table : tables) {
            if (table.getId() == null) {
                continue;
            }
            rows.add(buildRow(statDate, table, healthContext,
                    columnCounts, tagCounts, tablesWithDoc, indicatorCounts, datasourceNames));
        }
        for (AssetCatalogDaily row : rows) {
            assetCatalogDailyMapper.insert(row);
        }
        log.info("资产目录快照生成完成：日期={}, 资产数={}", statDate, rows.size());
        return new SnapshotResult(statDate, rows.size());
    }

    /** 清理早于 cutoff 的快照，返回删除行数 */
    public int purgeBefore(LocalDate cutoff) {
        int deleted = assetCatalogDailyMapper.delete(
                new LambdaQueryWrapper<AssetCatalogDaily>()
                        .lt(AssetCatalogDaily::getStatDate, cutoff));
        if (deleted > 0) {
            log.info("资产目录快照清理：早于 {} 的记录删除 {} 行", cutoff, deleted);
        }
        return deleted;
    }

    private void deleteByStatDate(LocalDate statDate) {
        assetCatalogDailyMapper.delete(new LambdaQueryWrapper<AssetCatalogDaily>()
                .eq(AssetCatalogDaily::getStatDate, statDate));
    }

    private AssetCatalogDaily buildRow(LocalDate statDate, TableMetadata table,
                                       AssetHealthService.Context healthContext,
                                       Map<Long, Integer> columnCounts,
                                       Map<Long, Integer> tagCounts,
                                       Set<Long> tablesWithDoc,
                                       Map<String, Integer> indicatorCounts,
                                       Map<Long, String> datasourceNames) {
        Long tableId = table.getId();
        TableNameConventionParser.Parsed parsed = TableNameConventionParser.parse(table.getTableName());
        List<AssetHealthService.Issue> issues = healthService.issuesFor(table, healthContext);

        AssetCatalogDaily row = new AssetCatalogDaily();
        row.setStatDate(statDate);
        row.setTableMetadataId(tableId);
        row.setDatasourceId(table.getDatasourceId());
        row.setDatasourceName(table.getDatasourceId() == null
                ? null : datasourceNames.get(table.getDatasourceId()));
        DatasourceType engine = table.getStorageEngine();
        row.setStorageEngine(engine == null ? null : engine.getCode());
        row.setDatabaseName(table.getDatabaseName());
        row.setTableName(table.getTableName());
        row.setTableComment(table.getTableComment());
        row.setTableType(table.getTableType());

        row.setLayerCode(parsed.layerCode());
        row.setDomainCode(parsed.domainCode());
        row.setConventionParsed(parsed.parsed() ? 1 : 0);
        row.setRefreshCycle(parsed.refreshCycle());

        row.setCategoryCode(table.getCategoryCode());
        row.setBusinessOwner(table.getBusinessOwner());
        row.setTechOwner(table.getTechOwner());
        row.setTopic(table.getTopic());
        row.setVisibility(table.getVisibility());
        row.setAssetLevel(table.getAssetLevel());

        row.setRowCount(table.getRowCount());
        row.setSizeBytes(table.getSizeBytes());
        row.setColumnCount(columnCounts.getOrDefault(tableId, 0));

        row.setTagCount(tagCounts.getOrDefault(tableId, 0));
        row.setIndicatorCount(indicatorCounts.getOrDefault(
                indicatorKey(table.getDatabaseName(), table.getTableName()), 0));
        row.setQualityRuleCount(healthContext == null ? 0 : healthContext.ruleCount(tableId));
        row.setHasDoc(tablesWithDoc.contains(tableId) ? 1 : 0);

        List<String> codes = new ArrayList<>(issues.size());
        for (AssetHealthService.Issue issue : issues) {
            codes.add(issue.getCode());
        }
        row.setHealthIssueCodes(codes.isEmpty() ? null : String.join(",", codes));
        row.setHealthIssueCount(codes.size());

        row.setBrowseCount30d(table.getBrowseCnt30d());
        row.setReadCount30d(table.getReadCnt30d());
        row.setBookmarkCount(table.getBookmarkCnt());
        row.setLastAccessTime(table.getLastAccessTime());
        return row;
    }

    // ==================================================================
    // 批量证据
    // ==================================================================

    /** 每张表的字段数 */
    private Map<Long, Integer> loadColumnCounts() {
        List<ColumnMetadata> rows = columnMetadataMapper.selectList(
                new LambdaQueryWrapper<ColumnMetadata>().select(ColumnMetadata::getTableMetadataId));
        Map<Long, Integer> counts = new HashMap<>();
        for (ColumnMetadata row : rows) {
            if (row.getTableMetadataId() != null) {
                counts.merge(row.getTableMetadataId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /** 每张表的标签数（仅统计表级标签，字段级标签不计入） */
    private Map<Long, Integer> loadTagCounts() {
        List<AssetTagRel> rows = assetTagRelMapper.selectList(
                new LambdaQueryWrapper<AssetTagRel>()
                        .select(AssetTagRel::getTableMetadataId)
                        .eq(AssetTagRel::getAssetType, "table"));
        Map<Long, Integer> counts = new HashMap<>();
        for (AssetTagRel row : rows) {
            if (row.getTableMetadataId() != null) {
                counts.merge(row.getTableMetadataId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /** 已有使用说明的表 */
    private Set<Long> loadTablesWithDoc() {
        List<AssetDoc> rows = assetDocMapper.selectList(
                new LambdaQueryWrapper<AssetDoc>()
                        .select(AssetDoc::getTableMetadataId)
                        .isNotNull(AssetDoc::getContent)
                        .ne(AssetDoc::getContent, ""));
        Set<Long> result = new HashSet<>();
        for (AssetDoc row : rows) {
            if (row.getTableMetadataId() != null) {
                result.add(row.getTableMetadataId());
            }
        }
        return result;
    }

    /**
     * 每张表关联的指标数。
     *
     * <p>匹配口径是「库名 + 表名」，与 {@code AssetCatalogService#indicators} 一致：
     * 指标字段表里的数据源名可能为空（历史数据），按数据源限制反而会漏掉已有指标。</p>
     */
    private Map<String, Integer> loadIndicatorCounts() {
        List<IndicatorField> fields = indicatorFieldMapper.selectList(
                new LambdaQueryWrapper<IndicatorField>()
                        .select(IndicatorField::getIndicatorsId, IndicatorField::getDbName,
                                IndicatorField::getTblName));
        Map<String, Set<Long>> distinct = new LinkedHashMap<>();
        for (IndicatorField field : fields) {
            if (field.getIndicatorsId() == null || field.getDbName() == null || field.getTblName() == null) {
                continue;
            }
            distinct.computeIfAbsent(indicatorKey(field.getDbName(), field.getTblName()),
                    k -> new HashSet<>()).add(field.getIndicatorsId());
        }
        Map<String, Integer> counts = new HashMap<>();
        for (Map.Entry<String, Set<Long>> entry : distinct.entrySet()) {
            counts.put(entry.getKey(), entry.getValue().size());
        }
        return counts;
    }

    private Map<Long, String> loadDatasourceNames() {
        List<DatasourceConfig> configs = datasourceConfigMapper.selectList(null);
        Map<Long, String> names = new HashMap<>();
        for (DatasourceConfig config : configs) {
            if (config.getId() != null) {
                names.put(config.getId(), config.getDatasourceName());
            }
        }
        return names;
    }

    private static String indicatorKey(String databaseName, String tableName) {
        return (databaseName == null ? "" : databaseName) + KEY_SEP
                + (tableName == null ? "" : tableName);
    }
}
