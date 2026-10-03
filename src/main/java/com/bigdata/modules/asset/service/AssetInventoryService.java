package com.bigdata.modules.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.asset.entity.AssetCatalogDaily;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** 环境看板的固定次序：生产 → 灰度 → 测试 */
    private static final List<String> ENV_ORDER = List.of(
            AssetEnvParser.ENV_PROD, AssetEnvParser.ENV_GRAY, AssetEnvParser.ENV_TEST);

    /** 无法按数据源归属到项目时的占位项目名 */
    private static final String UNASSIGNED_PROJECT_NAME = "未归属项目";

    /** TOP 资产默认条数与上限 */
    private static final int TOP_ASSET_DEFAULT = 100;
    private static final int TOP_ASSET_MAX = 500;

    /** 大小增长率对比窗口（天） */
    private static final int GROWTH_WINDOW_DAYS = 30;

    /** 大小突增默认阈值：增长率 10%、当前大小 10GB */
    private static final int GROWTH_DEFAULT_PERCENT = 10;
    private static final long GROWTH_DEFAULT_MIN_BYTES = 10L * 1024 * 1024 * 1024;

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

    /**
     * 盘点概览。
     *
     * <p>读 T+1 的资产目录快照：分层/业务域、规模、字段数都已在生成时算好，
     * 这里只做权限过滤与聚合。快照尚未生成过时（首次部署）回退到实时计算。</p>
     */
    public AssetInventoryVO overview(String projectCode) {
        LocalDate statDate = snapshotService.latestStatDate();
        return statDate == null
                ? overviewLive(projectCode)
                : overviewFromSnapshot(statDate, projectCode);
    }

    private AssetInventoryVO overviewLive(String projectCode) {
        List<TableMetadata> tables = visibleTables(projectCode, false);

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

            if (parsed.domainCode() != null) {
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
    private AssetInventoryVO overviewFromSnapshot(LocalDate statDate, String projectCode) {
        Long datasourceId = resolveProjectDatasource(projectCode);
        List<AssetCatalogDaily> rows = permissionSupport.filterSelectableSnapshots(
                snapshotService.load(statDate, datasourceId));

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

            if (row.getDomainCode() != null) {
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
     */
    public List<AssetHealthIssueVO> health(String projectCode) {
        LocalDate statDate = snapshotService.latestStatDate();
        return statDate == null
                ? healthService.overview(visibleTables(projectCode, true))
                : healthFromSnapshot(statDate, projectCode);
    }

    private List<AssetHealthIssueVO> healthFromSnapshot(LocalDate statDate, String projectCode) {
        Long datasourceId = resolveProjectDatasource(projectCode);
        List<AssetCatalogDaily> rows = permissionSupport.filterSelectableSnapshots(
                snapshotService.load(statDate, datasourceId));

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

    // ==================================================================
    // 资产概况看板（环境×项目 / TOP 资产 / 大小突增）
    // ==================================================================

    /**
     * 按环境与项目聚合表数量与占用空间。
     *
     * <p>环境由库名解析（见 {@link AssetEnvParser}），无环境后缀的库<b>不计入</b> ——
     * 不猜测环境，避免生产库被误判为测试库。项目归属只能按
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
            String env = AssetEnvParser.parseEnv(table.getDatabaseName());
            if (env == null) {
                continue;
            }
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
     * TOP 资产清单：排序与截断都在服务端完成，前端不做全量拉取后本地排序。
     *
     * @param sortBy {@code access}=近 30 天访问次数；{@code size}=占用空间。非法值按 access 处理
     * @param limit  条数上限，默认 100、硬上限 500
     */
    public List<AssetTopAssetVO> topAssets(String sortBy, Integer limit) {
        boolean bySize = "size".equalsIgnoreCase(sortBy == null ? "" : sortBy.trim());
        int top = limit == null ? TOP_ASSET_DEFAULT : Math.max(1, Math.min(TOP_ASSET_MAX, limit));

        List<TableMetadata> tables = permissionSupport.filterSelectable(
                tableMetadataMapper.selectList(new LambdaQueryWrapper<TableMetadata>()
                        .select(TableMetadata::getId, TableMetadata::getDatasourceId,
                                TableMetadata::getDatabaseName, TableMetadata::getTableName,
                                TableMetadata::getSizeBytes, TableMetadata::getBrowseCnt30d,
                                TableMetadata::getReadCnt30d, TableMetadata::getBusinessOwner)));
        Map<Long, ProjectRef> projects = projectByDatasource();

        List<AssetTopAssetVO> rows = new ArrayList<>();
        for (TableMetadata table : tables) {
            String env = AssetEnvParser.parseEnv(table.getDatabaseName());
            if (env == null) {
                continue;
            }
            ProjectRef project = projects.get(table.getDatasourceId());
            AssetTopAssetVO vo = new AssetTopAssetVO();
            vo.setTableMetadataId(table.getId());
            vo.setDatabaseName(table.getDatabaseName());
            vo.setTableName(table.getTableName());
            vo.setEnv(env);
            vo.setProjectCode(project == null ? "" : project.code());
            vo.setProjectName(project == null ? UNASSIGNED_PROJECT_NAME : project.name());
            // 访问次数 = 浏览 + 读取；query 在埋点汇总时已计入读取
            vo.setAccessCount30d(count30d(table.getBrowseCnt30d()) + count30d(table.getReadCnt30d()));
            vo.setSizeBytes(table.getSizeBytes() == null ? 0L : table.getSizeBytes());
            vo.setOwnerName(nullToDefault(table.getBusinessOwner(), ""));
            rows.add(vo);
        }

        Comparator<AssetTopAssetVO> comparator = bySize
                ? Comparator.comparingLong((AssetTopAssetVO item) ->
                        item.getSizeBytes() == null ? 0L : item.getSizeBytes()).reversed()
                : Comparator.comparingLong((AssetTopAssetVO item) ->
                        item.getAccessCount30d() == null ? 0 : item.getAccessCount30d()).reversed();
        rows.sort(comparator);
        return rows.size() > top ? new ArrayList<>(rows.subList(0, top)) : rows;
    }

    /**
     * 数据大小增长率突增表。
     *
     * <p>上期与本期都取自资产目录日快照（同一套采集口径，不拿实时值与历史值对比）：
     * 本期取最新快照日，上期取 30 天前的最近一个快照日；历史不足 30 天时退到最早的
     * 快照日，只有一天快照时无法比较、返回空列表。</p>
     *
     * @param growthPercent 增长率阈值（百分比），默认 10
     * @param minSizeBytes  当前大小下限（字节），默认 10GB
     */
    public List<AssetSizeGrowthVO> sizeGrowth(Integer growthPercent, Long minSizeBytes) {
        int percent = growthPercent == null ? GROWTH_DEFAULT_PERCENT : growthPercent;
        long minSize = minSizeBytes == null ? GROWTH_DEFAULT_MIN_BYTES : minSizeBytes;

        LocalDate currentDate = snapshotService.latestStatDate();
        if (currentDate == null) {
            return List.of();
        }
        LocalDate previousDate = resolveGrowthBaseline(currentDate);
        if (previousDate == null) {
            return List.of();
        }

        Map<Long, AssetCatalogDaily> previous = new HashMap<>();
        for (AssetCatalogDaily row : permissionSupport.filterSelectableSnapshots(
                snapshotService.load(previousDate, null))) {
            if (row.getTableMetadataId() != null) {
                previous.put(row.getTableMetadataId(), row);
            }
        }
        if (previous.isEmpty()) {
            return List.of();
        }

        Map<Long, ProjectRef> projects = projectByDatasource();
        List<AssetSizeGrowthVO> rows = new ArrayList<>();
        for (AssetCatalogDaily current : permissionSupport.filterSelectableSnapshots(
                snapshotService.load(currentDate, null))) {
            AssetCatalogDaily before = previous.get(current.getTableMetadataId());
            if (before == null) {
                continue;
            }
            String env = AssetEnvParser.parseEnv(current.getDatabaseName());
            if (env == null) {
                continue;
            }
            long currentSize = current.getSizeBytes() == null ? 0L : current.getSizeBytes();
            long previousSize = before.getSizeBytes() == null ? 0L : before.getSizeBytes();
            // 上期为 0 无法定义增长率；当前未达下限直接跳过
            if (previousSize <= 0 || currentSize <= minSize) {
                continue;
            }
            long growthBytes = currentSize - previousSize;
            double growthRate = (double) growthBytes / previousSize;
            if (growthRate * 100 <= percent) {
                continue;
            }
            ProjectRef project = projects.get(current.getDatasourceId());
            AssetSizeGrowthVO vo = new AssetSizeGrowthVO();
            vo.setTableMetadataId(current.getTableMetadataId());
            vo.setDatabaseName(current.getDatabaseName());
            vo.setTableName(current.getTableName());
            vo.setEnv(env);
            vo.setProjectName(project == null ? UNASSIGNED_PROJECT_NAME : project.name());
            vo.setPreviousSizeBytes(previousSize);
            vo.setCurrentSizeBytes(currentSize);
            vo.setGrowthBytes(growthBytes);
            vo.setGrowthRate(growthRate);
            rows.add(vo);
        }

        rows.sort(Comparator.comparingDouble((AssetSizeGrowthVO item) ->
                item.getGrowthRate() == null ? 0d : item.getGrowthRate()).reversed());
        return rows;
    }

    /**
     * 上期统计日：优先取 30 天前的最近一个快照日；历史不足时退到最早的快照日；
     * 只有一天快照（无法比较）时返回 null。
     */
    private LocalDate resolveGrowthBaseline(LocalDate currentDate) {
        LocalDate target = currentDate.minusDays(GROWTH_WINDOW_DAYS);
        LocalDate floorOfTarget = null;
        LocalDate earliest = null;
        for (LocalDate date : snapshotService.listStatDates()) {
            if (date == null || !date.isBefore(currentDate)) {
                continue;
            }
            if (earliest == null) {
                earliest = date;
            }
            // 升序遍历，最后一个不晚于 target 的即「30 天前最近的一条」
            if (!date.isAfter(target)) {
                floorOfTarget = date;
            }
        }
        return floorOfTarget != null ? floorOfTarget : earliest;
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
                new LambdaQueryWrapper<DevProject>()))) {
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

    private static int count30d(Integer value) {
        return value == null ? 0 : value;
    }

    // ==================================================================
    // 可见资产
    // ==================================================================

    /**
     * 取出当前用户可见的表（可选按项目收窄）。
     *
     * @param withOwnerColumns 是否一并取治理判定所需的属性列
     */
    private List<TableMetadata> visibleTables(String projectCode, boolean withOwnerColumns) {
        LambdaQueryWrapper<TableMetadata> wrapper = new LambdaQueryWrapper<>();
        if (withOwnerColumns) {
            wrapper.select(TableMetadata::getId, TableMetadata::getDatasourceId,
                    TableMetadata::getDatabaseName, TableMetadata::getTableName,
                    TableMetadata::getSizeBytes, TableMetadata::getBusinessOwner,
                    TableMetadata::getTableComment, TableMetadata::getLastAccessTime,
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

        return permissionSupport.filterSelectable(tableMetadataMapper.selectList(wrapper));
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
