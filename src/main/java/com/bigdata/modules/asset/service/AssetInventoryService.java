package com.bigdata.modules.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.utils.DeployEnvParser;
import com.bigdata.modules.asset.entity.AssetCatalogDaily;
import com.bigdata.modules.asset.entity.AssetPartitionSizeDaily;
import com.bigdata.modules.asset.vo.AssetEnvProjectStatVO;
import com.bigdata.modules.asset.vo.AssetHealthIssueVO;
import com.bigdata.modules.asset.vo.AssetInventoryVO;
import com.bigdata.modules.asset.vo.AssetSizeGrowthVO;
import com.bigdata.modules.asset.vo.AssetTopAssetVO;
import com.bigdata.modules.development.entity.DevProject;
import com.bigdata.modules.development.mapper.DevProjectMapper;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.ColumnMetadataMapper;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 资产盘点 —— 「我们一共有多少数据资产、分布在哪儿、哪些需要治理」。
 *
 * <h3>分层与业务域的聚合为什么在内存里做</h3>
 * <p>分层与业务域不是数据库里的列，而是由表名命名规范实时解析出来的
 * （见 {@link TableNameConventionParser}）。既然解析是纯函数、不入库，
 * 就只能先把表取回来再分组 —— 这也是「规范调整后无需回刷」这一设计的必然代价。
 * 为了把代价压住，取回时只 select 盘点需要的列，不取 location / fingerprint 这类大字段。</p>
 *
 * <h3>按项目盘点的口径</h3>
 * <p>平台目前<b>没有</b>项目与资产的绑定表：表侧的归属维度是数据源，
 * 项目侧只有 {@code dev_project.default_datasource_id}。因此传入 projectCode 时，
 * 盘点范围被收窄为「该项目默认数据源下的资产」。这是一个明确的近似，
 * 不是完整的项目资产台账 —— 如果后续需要精确口径，需要补一张项目↔资产绑定表。
 * 刻意不做「查不到项目就忽略过滤条件」的处理：静默放宽范围比报错更危险。</p>
 *
 * @author dataforge
 */
@Service
public class AssetInventoryService {

    /** IN 子句分片大小：MySQL 的占位符上限是 65535，留足余量 */
    private static final int ID_CHUNK = 1000;

    /** 环境看板的固定次序：生产 → 灰度 → 测试 → 未识别（未识别排最后，不抢占前三位） */
    private static final List<String> ENV_ORDER = List.of(
            DeployEnvParser.ENV_PROD, DeployEnvParser.ENV_GRAY, DeployEnvParser.ENV_TEST,
            DeployEnvParser.ENV_UNKNOWN);

    /** 无法按数据源归属到项目时的占位项目名 */
    private static final String UNASSIGNED_PROJECT_NAME = "未归属项目";

    /** TOP 资产默认条数与上限 */
    private static final int TOP_ASSET_DEFAULT = 100;
    private static final int TOP_ASSET_MAX = 500;

    /** 大小增长率对比窗口（期数）：天分区表按「近 N 个分区」、全量表按「近 N 天快照」 */
    private static final int GROWTH_WINDOW_DEFAULT = 7;
    private static final int GROWTH_WINDOW_MIN = 2;
    private static final int GROWTH_WINDOW_MAX = 30;

    /** 大小突增默认阈值：增长率 10%、当前大小 10GB */
    private static final int GROWTH_DEFAULT_PERCENT = 10;
    private static final long GROWTH_DEFAULT_MIN_BYTES = 10L * 1024 * 1024 * 1024;

    /** 计算口径标记：天分区表 / 全量表 */
    private static final String TYPE_PARTITIONED = "PARTITIONED";
    private static final String TYPE_FULL = "FULL";

    @Autowired
    private TableMetadataMapper tableMetadataMapper;

    @Autowired
    private ColumnMetadataMapper columnMetadataMapper;

    @Autowired
    private DevProjectMapper devProjectMapper;

    @Autowired
    private AssetPermissionSupport permissionSupport;

    @Autowired
    private AssetHealthService healthService;

    @Autowired
    private AssetCatalogSnapshotService snapshotService;

    @Autowired
    private AssetPartitionSizeCollectService partitionSizeService;

    /**
     * 盘点概览。
     *
     * <p>读 T+1 的资产目录快照：分层/业务域、规模、字段数都已在生成时算好，
     * 这里只做权限过滤与聚合。快照尚未生成过时（首次部署）回退到实时计算。</p>
     *
     * @param env 可选，限定环境（按库名后缀 _prod/_gray/_test 解析，见 {@link DeployEnvParser}）；
     *            null 表示不按环境过滤
     */
    public AssetInventoryVO overview(String projectCode, String env) {
        String scopedEnv = DeployEnvParser.normalize(env);
        LocalDate statDate = snapshotService.latestStatDate();
        return statDate == null
                ? overviewLive(projectCode, scopedEnv)
                : overviewFromSnapshot(statDate, projectCode, scopedEnv);
    }

    private AssetInventoryVO overviewLive(String projectCode, String env) {
        List<TableMetadata> tables = visibleTables(projectCode, false, env);

        AssetInventoryVO vo = new AssetInventoryVO();
        vo.setTotalTables(tables.size());
        vo.setTotalColumns((int) countColumns(tables));
        vo.setByDatasource(buildDatasourceStats(tables));

        long totalSize = 0L;
        Map<String, AssetInventoryVO.LayerStat> layerStats = new LinkedHashMap<>();
        Map<String, AssetInventoryVO.DomainStat> domainStats = new LinkedHashMap<>();
        int unclassified = 0;

        for (TableMetadata table : tables) {
            long size = table.getSizeBytes() == null ? 0L : table.getSizeBytes();
            totalSize += size;

            TableNameConventionParser.Parsed parsed = TableNameConventionParser.parse(table.getTableName());
            if (!parsed.parsed()) {
                unclassified++;
            }

            // 解析失败但仍是已知分层的表，也归入该分层 —— 让它在规范视角里可见，
            // 同时靠 unclassifiedCount 单独计数，而不是被藏进「未分类」看不见。
            String layerCode = parsed.layerCode() != null ? parsed.layerCode()
                    : TableNameConventionParser.UNCLASSIFIED_LAYER;
            AssetInventoryVO.LayerStat layerStat = layerStats.computeIfAbsent(layerCode, code -> {
                AssetInventoryVO.LayerStat stat = new AssetInventoryVO.LayerStat();
                stat.setLayerCode(code);
                stat.setTableCount(0);
                stat.setSizeBytes(0L);
                return stat;
            });
            layerStat.setTableCount(layerStat.getTableCount() + 1);
            layerStat.setSizeBytes(layerStat.getSizeBytes() + size);

            // 业务域只在「分层成立」时才计入盘点维度：解析器为了目录树好读，会从未知分层的
            // 表名里也尽力抽出第二段当域（如 olist_customers_dataset → customers），
            // 那是展示用的「尽力而为」；盘点要的是业务域维度的口径，混进 customers /
            // geolocation / order 这类实体名，会让占用空间被拆到一堆并不存在的业务域上。
            if (parsed.layerCode() != null && parsed.domainCode() != null) {
                AssetInventoryVO.DomainStat domainStat = domainStats.computeIfAbsent(
                        parsed.domainCode(), code -> {
                            AssetInventoryVO.DomainStat stat = new AssetInventoryVO.DomainStat();
                            stat.setDomainCode(code);
                            stat.setTableCount(0);
                            stat.setSizeBytes(0L);
                            return stat;
                        });
                domainStat.setTableCount(domainStat.getTableCount() + 1);
                domainStat.setSizeBytes(domainStat.getSizeBytes() + size);
            }
        }

        vo.setTotalSizeBytes(totalSize);
        vo.setUnclassifiedCount(unclassified);

        List<AssetInventoryVO.LayerStat> byLayer = new ArrayList<>(layerStats.values());
        byLayer.sort(Comparator.comparingInt(AssetInventoryVO.LayerStat::getTableCount).reversed());
        vo.setByLayer(byLayer);

        List<AssetInventoryVO.DomainStat> byDomain = new ArrayList<>(domainStats.values());
        byDomain.sort(Comparator.comparingInt(AssetInventoryVO.DomainStat::getTableCount).reversed());
        vo.setByDomain(byDomain);

        return vo;
    }

    /** 从快照聚合盘点：不再解析表名，也不再逐表 COUNT 字段 */
    private AssetInventoryVO overviewFromSnapshot(LocalDate statDate, String projectCode, String env) {
        Long datasourceId = resolveProjectDatasource(projectCode);
        List<AssetCatalogDaily> rows = filterByEnv(
                permissionSupport.filterSelectableSnapshots(snapshotService.load(statDate, datasourceId)), env);

        AssetInventoryVO vo = new AssetInventoryVO();
        vo.setTotalTables(rows.size());

        long totalSize = 0L;
        long totalColumns = 0L;
        int unclassified = 0;
        Map<String, AssetInventoryVO.LayerStat> layerStats = new LinkedHashMap<>();
        Map<String, AssetInventoryVO.DomainStat> domainStats = new LinkedHashMap<>();
        Map<Long, AssetInventoryVO.DatasourceStat> datasourceStats = new LinkedHashMap<>();

        for (AssetCatalogDaily row : rows) {
            long size = row.getSizeBytes() == null ? 0L : row.getSizeBytes();
            totalSize += size;
            totalColumns += row.getColumnCount() == null ? 0L : row.getColumnCount();
            if (row.getConventionParsed() == null || row.getConventionParsed() != 1) {
                unclassified++;
            }

            String layerCode = row.getLayerCode() != null ? row.getLayerCode()
                    : TableNameConventionParser.UNCLASSIFIED_LAYER;
            AssetInventoryVO.LayerStat layerStat = layerStats.computeIfAbsent(layerCode, code -> {
                AssetInventoryVO.LayerStat stat = new AssetInventoryVO.LayerStat();
                stat.setLayerCode(code);
                stat.setTableCount(0);
                stat.setSizeBytes(0L);
                return stat;
            });
            layerStat.setTableCount(layerStat.getTableCount() + 1);
            layerStat.setSizeBytes(layerStat.getSizeBytes() + size);

            // 与实时口径一致：只有分层成立时业务域才参与盘点维度统计（见 overviewLive 的说明）
            if (row.getLayerCode() != null && row.getDomainCode() != null) {
                AssetInventoryVO.DomainStat domainStat = domainStats.computeIfAbsent(
                        row.getDomainCode(), code -> {
                            AssetInventoryVO.DomainStat stat = new AssetInventoryVO.DomainStat();
                            stat.setDomainCode(code);
                            stat.setTableCount(0);
                            stat.setSizeBytes(0L);
                            return stat;
                        });
                domainStat.setTableCount(domainStat.getTableCount() + 1);
                domainStat.setSizeBytes(domainStat.getSizeBytes() + size);
            }

            Long dsId = row.getDatasourceId();
            Long dsKey = dsId == null ? -1L : dsId;
            AssetInventoryVO.DatasourceStat dsStat = datasourceStats.computeIfAbsent(dsKey, k -> {
                AssetInventoryVO.DatasourceStat item = new AssetInventoryVO.DatasourceStat();
                item.setDatasourceId(dsId);
                item.setDatasourceName(dsId == null ? "未关联数据源"
                        : nullToDefault(row.getDatasourceName(), permissionSupport.datasourceName(dsId)));
                item.setTableCount(0);
                return item;
            });
            dsStat.setTableCount(dsStat.getTableCount() + 1);
        }

        vo.setTotalSizeBytes(totalSize);
        vo.setTotalColumns((int) totalColumns);
        vo.setUnclassifiedCount(unclassified);

        List<AssetInventoryVO.LayerStat> byLayer = new ArrayList<>(layerStats.values());
        byLayer.sort(Comparator.comparingInt(AssetInventoryVO.LayerStat::getTableCount).reversed());
        vo.setByLayer(byLayer);

        List<AssetInventoryVO.DomainStat> byDomain = new ArrayList<>(domainStats.values());
        byDomain.sort(Comparator.comparingInt(AssetInventoryVO.DomainStat::getTableCount).reversed());
        vo.setByDomain(byDomain);

        List<AssetInventoryVO.DatasourceStat> byDatasource = new ArrayList<>(datasourceStats.values());
        byDatasource.sort(Comparator.comparingInt(AssetInventoryVO.DatasourceStat::getTableCount).reversed());
        vo.setByDatasource(byDatasource);

        return vo;
    }

    /**
     * 治理项清单（按权限过滤后的资产范围统计）。
     *
     * <p>读快照里已固化的治理项编码，不再逐表重算；快照未生成时回退实时计算。</p>
     *
     * @param env 可选，限定环境；null 表示不按环境过滤
     */
    public List<AssetHealthIssueVO> health(String projectCode, String env) {
        String scopedEnv = DeployEnvParser.normalize(env);
        LocalDate statDate = snapshotService.latestStatDate();
        return statDate == null
                ? healthService.overview(visibleTables(projectCode, true, scopedEnv))
                : healthFromSnapshot(statDate, projectCode, scopedEnv);
    }

    private List<AssetHealthIssueVO> healthFromSnapshot(LocalDate statDate, String projectCode, String env) {
        Long datasourceId = resolveProjectDatasource(projectCode);
        List<AssetCatalogDaily> rows = filterByEnv(
                permissionSupport.filterSelectableSnapshots(snapshotService.load(statDate, datasourceId)), env);

        Map<String, List<AssetCatalogDaily>> byIssue = new LinkedHashMap<>();
        for (AssetHealthService.Issue issue : AssetHealthService.Issue.values()) {
            byIssue.put(issue.getCode(), new ArrayList<>());
        }
        for (AssetCatalogDaily row : rows) {
            for (String code : splitIssueCodes(row.getHealthIssueCodes())) {
                List<AssetCatalogDaily> bucket = byIssue.get(code);
                if (bucket != null) {
                    bucket.add(row);
                }
            }
        }

        List<AssetHealthIssueVO> result = new ArrayList<>(AssetHealthService.Issue.values().length);
        for (AssetHealthService.Issue issue : AssetHealthService.Issue.values()) {
            List<AssetCatalogDaily> matched = byIssue.get(issue.getCode());
            AssetHealthIssueVO vo = new AssetHealthIssueVO();
            vo.setIssueCode(issue.getCode());
            vo.setIssueName(issue.getName());
            vo.setCount(matched.size());
            vo.setImpact(healthService.buildImpact(issue, matched.size(), rows.size()));
            vo.setSamples(samplesOf(matched));
            result.add(vo);
        }
        return result;
    }

    private List<AssetHealthIssueVO.Sample> samplesOf(List<AssetCatalogDaily> rows) {
        List<AssetHealthIssueVO.Sample> samples = new ArrayList<>();
        for (AssetCatalogDaily row : rows) {
            if (samples.size() >= AssetHealthService.MAX_SAMPLES) {
                break;
            }
            AssetHealthIssueVO.Sample sample = new AssetHealthIssueVO.Sample();
            sample.setTableMetadataId(row.getTableMetadataId());
            sample.setDatabaseName(row.getDatabaseName());
            sample.setTableName(row.getTableName());
            samples.add(sample);
        }
        return samples;
    }

    private static List<String> splitIssueCodes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> codes = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                codes.add(trimmed);
            }
        }
        return codes;
    }

    private static String nullToDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }

    /**
     * 按环境过滤快照行（环境取自库名后缀）。
     *
     * <p>放在权限过滤之后执行：环境只是「看哪一套」，不改变「能看到什么」——
     * 越权的表不会因为选了环境而出现。</p>
     */
    private static List<AssetCatalogDaily> filterByEnv(List<AssetCatalogDaily> rows, String env) {
        if (env == null) {
            return rows;
        }
        List<AssetCatalogDaily> filtered = new ArrayList<>(rows.size());
        for (AssetCatalogDaily row : rows) {
            if (DeployEnvParser.matches(row.getDatabaseName(), env)) {
                filtered.add(row);
            }
        }
        return filtered;
    }

    /** 按环境过滤表元数据行（环境取自库名后缀） */
    private static List<TableMetadata> filterTablesByEnv(List<TableMetadata> tables, String env) {
        if (env == null) {
            return tables;
        }
        List<TableMetadata> filtered = new ArrayList<>(tables.size());
        for (TableMetadata table : tables) {
            if (DeployEnvParser.matches(table.getDatabaseName(), env)) {
                filtered.add(table);
            }
        }
        return filtered;
    }

    // ==================================================================
    // 资产概况看板（环境×项目 / TOP 资产 / 大小突增）
    // ==================================================================

    /**
     * 按环境与项目聚合表数量与占用空间。
     *
     * <p>环境由库名解析（见 {@link DeployEnvParser}）。库名不符合
     * {@code {分层}_{业务域}_{环境}} 规范时<b>不猜测环境</b>，但也不丢弃 ——
     * 归入 {@link DeployEnvParser#ENV_UNKNOWN} 分组并计入总量：
     * 「不知道是什么环境」与「这张表不存在」是两回事，直接丢弃会让源表库
     * （如 {@code test_db}）整层消失在环境看板上。项目归属只能按
     * {@code dev_project.default_datasource_id} 近似（平台暂无项目↔资产绑定表），
     * 未命中的资产记为「未归属项目」。</p>
     */
    public List<AssetEnvProjectStatVO> envProject() {
        // 必须带上 tableName：权限过滤按「数据源 + 库 + 表名」匹配，缺了表名会全部判为不可见
        List<TableMetadata> tables = permissionSupport.filterSelectable(
                tableMetadataMapper.selectList(new LambdaQueryWrapper<TableMetadata>()
                        .select(TableMetadata::getId, TableMetadata::getDatasourceId,
                                TableMetadata::getDatabaseName, TableMetadata::getTableName,
                                TableMetadata::getSizeBytes)));
        Map<Long, ProjectRef> projects = projectByDatasource();

        Map<String, AssetEnvProjectStatVO> grouped = new LinkedHashMap<>();
        for (TableMetadata table : tables) {
            String parsed = DeployEnvParser.parseEnv(table.getDatabaseName());
            String env = parsed == null ? DeployEnvParser.ENV_UNKNOWN : parsed;
            ProjectRef project = projects.get(table.getDatasourceId());
            String projectCode = project == null ? "" : project.code();
            AssetEnvProjectStatVO stat = grouped.computeIfAbsent(env + "\u0000" + projectCode, key -> {
                AssetEnvProjectStatVO item = new AssetEnvProjectStatVO();
                item.setEnv(env);
                item.setProjectCode(projectCode);
                item.setProjectName(project == null ? UNASSIGNED_PROJECT_NAME : project.name());
                item.setTableCount(0);
                item.setSizeBytes(0L);
                return item;
            });
            stat.setTableCount(stat.getTableCount() + 1);
            stat.setSizeBytes(stat.getSizeBytes() + (table.getSizeBytes() == null ? 0L : table.getSizeBytes()));
        }

        List<AssetEnvProjectStatVO> result = new ArrayList<>(grouped.values());
        result.sort(Comparator
                .comparingInt((AssetEnvProjectStatVO item) -> ENV_ORDER.indexOf(item.getEnv()))
                .thenComparing(Comparator.comparingInt(
                        (AssetEnvProjectStatVO item) -> item.getTableCount() == null ? 0 : item.getTableCount())
                        .reversed()));
        return result;
    }

    /**
     * TOP 资产清单：按占用空间降序，排序与截断都在服务端完成，前端不做全量拉取后本地排序。
     *
     * @param limit 条数上限，默认 100、硬上限 500
     * @param env   可选，限定环境（按库名后缀过滤）；null 表示不过滤
     */
    public List<AssetTopAssetVO> topAssets(Integer limit, String env) {
        int top = limit == null ? TOP_ASSET_DEFAULT : Math.max(1, Math.min(TOP_ASSET_MAX, limit));
        String scopedEnv = DeployEnvParser.normalize(env);

        List<TableMetadata> tables = filterTablesByEnv(permissionSupport.filterSelectable(
                tableMetadataMapper.selectList(new LambdaQueryWrapper<TableMetadata>()
                        .select(TableMetadata::getId, TableMetadata::getDatasourceId,
                                TableMetadata::getDatabaseName, TableMetadata::getTableName,
                                TableMetadata::getSizeBytes, TableMetadata::getBusinessOwner))), scopedEnv);
        Map<Long, ProjectRef> projects = projectByDatasource();

        List<AssetTopAssetVO> rows = new ArrayList<>();
        for (TableMetadata table : tables) {
            // 环境解析不出来只表示「库名不合规范」，不代表这张表不该出现在排行榜里：
            // 归入 unknown 分组，否则占用空间最大的源表会被整层漏掉。
            String parsedEnv = DeployEnvParser.parseEnv(table.getDatabaseName());
            String envCode = parsedEnv == null ? DeployEnvParser.ENV_UNKNOWN : parsedEnv;
            ProjectRef project = projects.get(table.getDatasourceId());
            AssetTopAssetVO vo = new AssetTopAssetVO();
            vo.setTableMetadataId(table.getId());
            vo.setDatabaseName(table.getDatabaseName());
            vo.setTableName(table.getTableName());
            vo.setEnv(envCode);
            vo.setProjectCode(project == null ? "" : project.code());
            vo.setProjectName(project == null ? UNASSIGNED_PROJECT_NAME : project.name());
            vo.setSizeBytes(table.getSizeBytes() == null ? 0L : table.getSizeBytes());
            vo.setOwnerName(nullToDefault(table.getBusinessOwner(), ""));
            rows.add(vo);
        }

        rows.sort(Comparator.comparingLong((AssetTopAssetVO item) ->
                item.getSizeBytes() == null ? 0L : item.getSizeBytes()).reversed());
        return rows.size() > top ? new ArrayList<>(rows.subList(0, top)) : rows;
    }

    /**
     * 数据大小增长率突增表 —— 按表类型分流两种口径。
     *
     * <h3>为什么分两种口径</h3>
     * <p>天分区表的整表大小只增不减（按天追加分区），整表环比永远显示「增长」，
     * 看不出「某一天分区异常膨胀」。因此：</p>
     * <ul>
     *   <li><b>天分区表</b>：本期 = 最新分区大小，上期 = 近 N 个分区大小均值
     *       （取自 {@code table_partition_size_daily}）；</li>
     *   <li><b>全量表</b>：本期 = 最新快照整表大小，上期 = 近 N 天快照大小均值
     *       （取自 {@code asset_catalog_daily}）。</li>
     * </ul>
     * <p>两者都用「近 N 天均值」而非单点对比，避免单日波动制造假突增。基线期数不足 1 期
     * （即只有本期）无法比较，跳过该表。</p>
     *
     * @param growthPercent 增长率阈值（百分比），默认 10
     * @param minSizeBytes  当前大小下限（字节），默认 10GB
     * @param windowDays    对比期数 N（分区表按分区数、全量表按天数），默认 7、夹到 2~30
     * @param env           可选，限定环境（按库名后缀过滤）；null 表示不过滤
     */
    public List<AssetSizeGrowthVO> sizeGrowth(Integer growthPercent, Long minSizeBytes,
                                              Integer windowDays, String env) {
        int percent = growthPercent == null ? GROWTH_DEFAULT_PERCENT : growthPercent;
        long minSize = minSizeBytes == null ? GROWTH_DEFAULT_MIN_BYTES : minSizeBytes;
        int window = windowDays == null ? GROWTH_WINDOW_DEFAULT
                : Math.max(GROWTH_WINDOW_MIN, Math.min(GROWTH_WINDOW_MAX, windowDays));
        String scopedEnv = DeployEnvParser.normalize(env);

        LocalDate currentDate = snapshotService.latestStatDate();
        if (currentDate == null) {
            return List.of();
        }

        // 当前快照（权限 + 环境过滤）：既是全量表的本期值，也是分区表的可见范围与元信息
        List<AssetCatalogDaily> currentRows = filterByEnv(permissionSupport.filterSelectableSnapshots(
                snapshotService.load(currentDate, null)), scopedEnv);
        if (currentRows.isEmpty()) {
            return List.of();
        }

        Set<Long> dailyPartitionedIds = dailyPartitionedTableIds();
        Map<Long, ProjectRef> projects = projectByDatasource();

        List<AssetSizeGrowthVO> rows = new ArrayList<>();
        rows.addAll(fullTableGrowth(currentDate, window, currentRows,
                dailyPartitionedIds, projects, percent, minSize));
        rows.addAll(partitionTableGrowth(currentDate, window, currentRows,
                dailyPartitionedIds, projects, percent, minSize));

        rows.sort(Comparator.comparingDouble((AssetSizeGrowthVO item) ->
                item.getGrowthRate() == null ? 0d : item.getGrowthRate()).reversed());
        return rows;
    }

    /**
     * 全量表分支：本期取最新快照，上期取窗口内更早各日快照的均值。
     *
     * <p>天分区表在此跳过 —— 整表口径对分区表无意义，交由 {@link #partitionTableGrowth} 处理。</p>
     */
    private List<AssetSizeGrowthVO> fullTableGrowth(LocalDate currentDate, int window,
                                                    List<AssetCatalogDaily> currentRows,
                                                    Set<Long> dailyPartitionedIds,
                                                    Map<Long, ProjectRef> projects,
                                                    int percent, long minSize) {
        List<LocalDate> baselineDates = baselineDates(currentDate, window);
        if (baselineDates.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Long>> history = new HashMap<>();
        for (AssetCatalogDaily row : permissionSupport.filterSelectableSnapshots(
                snapshotService.loadSizeHistory(baselineDates.get(0),
                        baselineDates.get(baselineDates.size() - 1)))) {
            if (row.getTableMetadataId() == null) {
                continue;
            }
            history.computeIfAbsent(row.getTableMetadataId(), k -> new ArrayList<>())
                    .add(row.getSizeBytes() == null ? 0L : row.getSizeBytes());
        }
        if (history.isEmpty()) {
            return List.of();
        }

        List<AssetSizeGrowthVO> result = new ArrayList<>();
        for (AssetCatalogDaily current : currentRows) {
            Long tableId = current.getTableMetadataId();
            if (tableId == null || dailyPartitionedIds.contains(tableId)) {
                continue;
            }
            List<Long> sizes = history.get(tableId);
            if (sizes == null || sizes.isEmpty()) {
                continue;
            }
            long currentSize = current.getSizeBytes() == null ? 0L : current.getSizeBytes();
            long baseline = average(sizes);
            AssetSizeGrowthVO vo = buildGrowth(current, currentSize, baseline, sizes.size(),
                    TYPE_FULL, null, projects, percent, minSize);
            if (vo != null) {
                result.add(vo);
            }
        }
        return result;
    }

    /**
     * 天分区表分支：本期取最新分区大小，上期取窗口中更早各分区大小的均值。
     *
     * <p>记录不足两期（无法比较）时跳过，不退回整表口径 —— 分区表整表环比无意义，
     * 宁可暂不显示，也不给出错误的突增结论。</p>
     */
    private List<AssetSizeGrowthVO> partitionTableGrowth(LocalDate currentDate, int window,
                                                        List<AssetCatalogDaily> currentRows,
                                                        Set<Long> dailyPartitionedIds,
                                                        Map<Long, ProjectRef> projects,
                                                        int percent, long minSize) {
        if (dailyPartitionedIds.isEmpty()) {
            return List.of();
        }
        // 取到 currentDate - window 天（含端点），可得最多 window+1 条记录 → 上期最多 window 期
        Map<Long, List<AssetPartitionSizeDaily>> history = new HashMap<>();
        for (AssetPartitionSizeDaily row : partitionSizeService.loadSince(currentDate.minusDays(window))) {
            Long tableId = row.getTableMetadataId();
            if (tableId == null || !dailyPartitionedIds.contains(tableId)) {
                continue;
            }
            history.computeIfAbsent(tableId, k -> new ArrayList<>()).add(row);
        }
        if (history.isEmpty()) {
            return List.of();
        }

        List<AssetSizeGrowthVO> result = new ArrayList<>();
        for (AssetCatalogDaily current : currentRows) {
            Long tableId = current.getTableMetadataId();
            if (tableId == null || !dailyPartitionedIds.contains(tableId)) {
                continue;
            }
            List<AssetPartitionSizeDaily> records = history.get(tableId);
            if (records == null || records.size() < 2) {
                continue;
            }
            // loadSince 按统计日升序，末条为最新分区
            AssetPartitionSizeDaily latest = records.get(records.size() - 1);
            long currentSize = latest.getSizeBytes() == null ? 0L : latest.getSizeBytes();
            List<Long> baselineSizes = new ArrayList<>(records.size() - 1);
            for (int i = 0; i < records.size() - 1; i++) {
                Long size = records.get(i).getSizeBytes();
                if (size != null) {
                    baselineSizes.add(size);
                }
            }
            if (baselineSizes.isEmpty()) {
                continue;
            }
            long baseline = average(baselineSizes);
            AssetSizeGrowthVO vo = buildGrowth(current, currentSize, baseline, baselineSizes.size(),
                    TYPE_PARTITIONED, latest.getPartitionValue(), projects, percent, minSize);
            if (vo != null) {
                result.add(vo);
            }
        }
        return result;
    }

    /** 组装突增行并做阈值过滤；未达阈值返回 null */
    private AssetSizeGrowthVO buildGrowth(AssetCatalogDaily current, long currentSize, long baselineSize,
                                          int compareDays, String tableType, String partitionValue,
                                          Map<Long, ProjectRef> projects, int percent, long minSize) {
        // 上期为 0 无法定义增长率；当前未达下限直接跳过
        if (baselineSize <= 0 || currentSize <= minSize) {
            return null;
        }
        long growthBytes = currentSize - baselineSize;
        double growthRate = (double) growthBytes / baselineSize;
        if (growthRate * 100 <= percent) {
            return null;
        }
        // 与 envProject / topAssets 同口径：环境识别不出来归入 unknown，不丢弃
        String parsedEnv = DeployEnvParser.parseEnv(current.getDatabaseName());
        String envCode = parsedEnv == null ? DeployEnvParser.ENV_UNKNOWN : parsedEnv;
        ProjectRef project = projects.get(current.getDatasourceId());
        AssetSizeGrowthVO vo = new AssetSizeGrowthVO();
        vo.setTableMetadataId(current.getTableMetadataId());
        vo.setDatabaseName(current.getDatabaseName());
        vo.setTableName(current.getTableName());
        vo.setEnv(envCode);
        vo.setProjectName(project == null ? UNASSIGNED_PROJECT_NAME : project.name());
        vo.setPreviousSizeBytes(baselineSize);
        vo.setCurrentSizeBytes(currentSize);
        vo.setGrowthBytes(growthBytes);
        vo.setGrowthRate(growthRate);
        vo.setTableType(tableType);
        vo.setCompareDays(compareDays);
        vo.setPartitionValue(partitionValue);
        return vo;
    }

    /**
     * 全量表基线日：早于当前快照日、最近至多 window 个可用快照日（升序）。
     */
    private List<LocalDate> baselineDates(LocalDate currentDate, int window) {
        List<LocalDate> previous = new ArrayList<>();
        for (LocalDate date : snapshotService.listStatDates()) {
            if (date != null && date.isBefore(currentDate)) {
                previous.add(date);
            }
        }
        if (previous.size() <= window) {
            return previous;
        }
        return new ArrayList<>(previous.subList(previous.size() - window, previous.size()));
    }

    /** 天分区表 id 集合：判定口径统一走 {@link DailyPartitionDetector} */
    private Set<Long> dailyPartitionedTableIds() {
        Set<Long> ids = new HashSet<>();
        List<TableMetadata> tables = tableMetadataMapper.selectList(
                new LambdaQueryWrapper<TableMetadata>()
                        .select(TableMetadata::getId, TableMetadata::getPartitionColumns));
        for (TableMetadata table : tables) {
            if (table.getId() != null && DailyPartitionDetector.isDailyPartitioned(table.getPartitionColumns())) {
                ids.add(table.getId());
            }
        }
        return ids;
    }

    /** 均值（四舍五入到字节）；空集合返回 0 */
    private static long average(List<Long> sizes) {
        if (sizes.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        for (Long size : sizes) {
            sum += size == null ? 0L : size;
        }
        return Math.round((double) sum / sizes.size());
    }

    /**
     * 数据源 → 项目。
     *
     * <p>近似归属：平台没有项目↔资产绑定表，只有 {@code dev_project.default_datasource_id}。
     * 多个项目共用同一默认数据源时取先出现的一个（不重复计入）。</p>
     */
    private Map<Long, ProjectRef> projectByDatasource() {
        Map<Long, ProjectRef> result = new HashMap<>();
        for (DevProject project : devProjectMapper.selectList(
                new LambdaQueryWrapper<DevProject>())) {
            if (project.getDefaultDatasourceId() == null) {
                continue;
            }
            result.putIfAbsent(project.getDefaultDatasourceId(), new ProjectRef(
                    nullToDefault(project.getProjectCode(), ""),
                    nullToDefault(project.getProjectName(), "")));
        }
        return result;
    }

    /** 项目引用（编码 + 显示名） */
    private record ProjectRef(String code, String name) {
    }

    // ==================================================================
    // 可见资产
    // ==================================================================

    /**
     * 取出当前用户可见的表（可选按项目收窄、按环境收窄）。
     *
     * @param withOwnerColumns 是否一并取治理判定所需的属性列
     * @param env              环境码，null 表示不按环境过滤（过滤按库名后缀，见 {@link DeployEnvParser}）
     */
    private List<TableMetadata> visibleTables(String projectCode, boolean withOwnerColumns, String env) {
        LambdaQueryWrapper<TableMetadata> wrapper = new LambdaQueryWrapper<>();
        if (withOwnerColumns) {
            wrapper.select(TableMetadata::getId, TableMetadata::getDatasourceId,
                    TableMetadata::getDatabaseName, TableMetadata::getTableName,
                    TableMetadata::getSizeBytes, TableMetadata::getBusinessOwner,
                    TableMetadata::getTableComment,
                    TableMetadata::getCategoryCode);
        } else {
            wrapper.select(TableMetadata::getId, TableMetadata::getDatasourceId,
                    TableMetadata::getDatabaseName, TableMetadata::getTableName,
                    TableMetadata::getSizeBytes);
        }

        Long datasourceId = resolveProjectDatasource(projectCode);
        if (datasourceId != null) {
            wrapper.eq(TableMetadata::getDatasourceId, datasourceId);
        }

        return filterTablesByEnv(permissionSupport.filterSelectable(tableMetadataMapper.selectList(wrapper)), env);
    }

    /**
     * 项目编码 → 默认数据源 id。
     *
     * @return null 表示未指定项目（不限定范围）
     * @throws BusinessException 项目不存在或未配置默认数据源
     */
    private Long resolveProjectDatasource(String projectCode) {
        if (projectCode == null || projectCode.isBlank()) {
            return null;
        }
        LambdaQueryWrapper<DevProject> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DevProject::getProjectCode, projectCode.trim());
        List<DevProject> projects = devProjectMapper.selectList(wrapper);
        if (projects.isEmpty()) {
            throw new BusinessException(404, "项目不存在：" + projectCode);
        }
        Long datasourceId = projects.get(0).getDefaultDatasourceId();
        if (datasourceId == null) {
            throw new BusinessException("项目 " + projectCode
                    + " 未配置默认数据源，无法按项目盘点资产（平台暂无项目↔资产绑定表，只能按默认数据源近似收窄）");
        }
        return datasourceId;
    }

    /** 字段总数：分片 COUNT(*)，避免把字段行整表拉到应用侧 */
    private long countColumns(List<TableMetadata> tables) {
        List<Long> tableIds = new ArrayList<>();
        for (TableMetadata table : tables) {
            if (table.getId() != null) {
                tableIds.add(table.getId());
            }
        }
        if (tableIds.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        for (int start = 0; start < tableIds.size(); start += ID_CHUNK) {
            List<Long> chunk = tableIds.subList(start, Math.min(start + ID_CHUNK, tableIds.size()));
            LambdaQueryWrapper<ColumnMetadata> wrapper = new LambdaQueryWrapper<>();
            wrapper.in(ColumnMetadata::getTableMetadataId, chunk);
            Long count = columnMetadataMapper.selectCount(wrapper);
            total += count == null ? 0L : count;
        }
        return total;
    }

    private List<AssetInventoryVO.DatasourceStat> buildDatasourceStats(List<TableMetadata> tables) {
        Map<Long, AssetInventoryVO.DatasourceStat> stats = new LinkedHashMap<>();
        for (TableMetadata table : tables) {
            Long datasourceId = table.getDatasourceId();
            Long key = datasourceId == null ? -1L : datasourceId;
            AssetInventoryVO.DatasourceStat stat = stats.computeIfAbsent(key, k -> {
                AssetInventoryVO.DatasourceStat item = new AssetInventoryVO.DatasourceStat();
                item.setDatasourceId(datasourceId);
                item.setDatasourceName(datasourceId == null ? "未关联数据源"
                        : permissionSupport.datasourceName(datasourceId));
                item.setTableCount(0);
                return item;
            });
            stat.setTableCount(stat.getTableCount() + 1);
        }
        List<AssetInventoryVO.DatasourceStat> result = new ArrayList<>(stats.values());
        result.sort(Comparator.comparingInt(AssetInventoryVO.DatasourceStat::getTableCount).reversed());
        return result;
    }
}
