package com.bigdata.modules.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.common.utils.SecurityUtils;
import com.bigdata.modules.asset.dto.AssetAccessReportDTO;
import com.bigdata.modules.asset.entity.AssetAccessLog;
import com.bigdata.modules.asset.entity.AssetBookmark;
import com.bigdata.modules.asset.entity.AssetUsageDaily;
import com.bigdata.modules.asset.mapper.AssetAccessLogMapper;
import com.bigdata.modules.asset.mapper.AssetBookmarkMapper;
import com.bigdata.modules.asset.mapper.AssetUsageDailyMapper;
import com.bigdata.modules.asset.vo.AssetUsageVO;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.ColumnMetadataMapper;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 资产使用热度 —— 埋点写入、按日汇总、热度查询。
 *
 * <h3>为什么埋点单独一张表</h3>
 * <p>浏览行为是高频、低价值的写入。如果直接写 operation_audit_log，
 * 真正的操作留痕（谁改了权限、谁发了任务）会被淹没在浏览记录里，
 * 审计追溯的可用性就没了。因此这里走独立轻量表 asset_access_log，
 * 只追加不更新。</p>
 *
 * <h3>为什么热度要 T+1 汇总</h3>
 * <p>「近 30 日浏览数」这种指标如果在查询时现算，每次打开目录页都要对
 * access_log 做一次范围聚合。汇总成 asset_usage_daily 后，
 * 明细表可以放心地按保留期清理，而历史趋势不受影响。
 * 汇总结果再回写到 table_metadata 的 browse_cnt_30d / read_cnt_30d 列，
 * 让目录列表排序不必 join 热度表。</p>
 *
 * @author dataforge
 */
@Service
public class AssetUsageService {

    private static final Logger log = LoggerFactory.getLogger(AssetUsageService.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 允许的资产类型（与前端 AssetSearchType 一致） */
    private static final Set<String> ASSET_TYPES = Set.of("table", "column", "indicator");

    /** 允许的埋点类型（与前端 AssetAccessType 一致） */
    private static final Set<String> ACCESS_TYPES = Set.of("browse", "read", "bookmark", "query");

    /** 字段热度星级上限 */
    private static final int MAX_HOT_SCORE = 5;

    /** 单次埋点上报最多接受多少条，防前端批量刷写 */
    private static final int MAX_REPORT_ITEMS = 200;

    @Autowired
    private AssetAccessLogMapper assetAccessLogMapper;

    @Autowired
    private AssetUsageDailyMapper assetUsageDailyMapper;

    @Autowired
    private AssetBookmarkMapper assetBookmarkMapper;

    @Autowired
    private ColumnMetadataMapper columnMetadataMapper;

    @Autowired
    private TableMetadataMapper tableMetadataMapper;

    // ==================================================================
    // 埋点写入
    // ==================================================================

    /**
     * 记录一批访问埋点。
     *
     * <p>非法类型直接丢弃（不抛错）：埋点是非关键路径，前端已经做了静默降级，
     * 服务端再抛错只会制造无意义的错误日志。但丢弃会打 debug 日志，
     * 便于排查「为什么热度数据对不上」。</p>
     *
     * @return 实际写入条数
     */
    @Transactional
    public int recordAccess(AssetAccessReportDTO dto) {
        if (dto == null || dto.getItems() == null || dto.getItems().isEmpty()) {
            return 0;
        }
        Long userId = SecurityUtils.getCurrentUserId();
        String userName = SecurityUtils.getCurrentUsername();
        LocalDateTime now = LocalDateTime.now();

        List<AssetAccessLog> logs = new ArrayList<>();
        Long latestTableId = null;
        for (AssetAccessReportDTO.AccessItem item : dto.getItems()) {
            if (logs.size() >= MAX_REPORT_ITEMS) {
                break;
            }
            if (item == null || item.getAssetId() == null) {
                continue;
            }
            String assetType = normalize(item.getAssetType(), ASSET_TYPES);
            String accessType = normalize(item.getAccessType(), ACCESS_TYPES);
            if (assetType == null || accessType == null) {
                log.debug("忽略非法埋点: assetType={}, accessType={}", item.getAssetType(), item.getAccessType());
                continue;
            }

            AssetAccessLog entry = new AssetAccessLog();
            entry.setUserId(userId);
            entry.setUserName(userName);
            entry.setAssetType(assetType);
            entry.setAssetId(item.getAssetId());
            entry.setAccessType(accessType);
            entry.setKeyword(truncate(item.getKeyword(), 200));
            entry.setAccessTime(now);
            if ("table".equals(assetType)) {
                entry.setTableMetadataId(item.getAssetId());
                latestTableId = item.getAssetId();
            } else if ("column".equals(assetType)) {
                entry.setTableMetadataId(resolveTableIdOfColumn(item.getAssetId()));
            }
            logs.add(entry);
        }
        if (logs.isEmpty()) {
            return 0;
        }
        for (AssetAccessLog entry : logs) {
            assetAccessLogMapper.insert(entry);
        }

        // 最近访问时间实时回写：目录列表按热度排序时需要它，
        // 而 T+1 汇总要等到第二天才有值。
        if (latestTableId != null) {
            touchLastAccessTime(latestTableId, now);
        }
        return logs.size();
    }

    private void touchLastAccessTime(Long tableMetadataId, LocalDateTime accessTime) {
        TableMetadata table = tableMetadataMapper.selectById(tableMetadataId);
        if (table == null) {
            return;
        }
        TableMetadata update = new TableMetadata();
        update.setId(tableMetadataId);
        update.setLastAccessTime(java.sql.Timestamp.valueOf(accessTime));
        // 只更新该列，避免把整行元数据写回去覆盖并发同步结果
        tableMetadataMapper.updateById(update);
    }

    private Long resolveTableIdOfColumn(Long columnId) {
        ColumnMetadata column = columnMetadataMapper.selectById(columnId);
        return column == null ? null : column.getTableMetadataId();
    }

    // ==================================================================
    // 热度查询
    // ==================================================================

    /**
     * 单表使用热度。
     *
     * @param days 趋势窗口天数；越界会被夹到 1~365
     */
    public AssetUsageVO usage(Long tableMetadataId, Integer days) {
        int window = days == null ? 30 : Math.max(1, Math.min(365, days));
        AssetUsageVO vo = new AssetUsageVO();
        vo.setBrowseTrend(buildBrowseTrend(tableMetadataId, window));
        List<ColumnMetadata> columns = columnsOf(tableMetadataId);
        vo.setTopColumns(buildTopColumns(columns));
        vo.setTopReaders(buildTopReaders(tableMetadataId, window));
        return vo;
    }

    /** 趋势：缺失的日期补 0，否则折线图会断档且横轴被压缩 */
    private List<AssetUsageVO.BrowseTrendPoint> buildBrowseTrend(Long tableMetadataId, int window) {
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(window - 1L);

        Map<LocalDate, int[]> byDate = new HashMap<>();
        LambdaQueryWrapper<AssetUsageDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AssetUsageDaily::getDeleted, 0)
                .eq(AssetUsageDaily::getAssetType, "table")
                .eq(AssetUsageDaily::getAssetId, tableMetadataId)
                .ge(AssetUsageDaily::getStatDate, from)
                .le(AssetUsageDaily::getStatDate, today);
        for (AssetUsageDaily row : assetUsageDailyMapper.selectList(wrapper)) {
            if (row.getStatDate() == null) {
                continue;
            }
            int[] counts = byDate.computeIfAbsent(row.getStatDate(), k -> new int[2]);
            counts[0] += row.getBrowseCount() == null ? 0 : row.getBrowseCount();
            counts[1] += row.getReadCount() == null ? 0 : row.getReadCount();
        }

        List<AssetUsageVO.BrowseTrendPoint> trend = new ArrayList<>(window);
        for (int i = 0; i < window; i++) {
            LocalDate date = from.plusDays(i);
            int[] counts = byDate.getOrDefault(date, new int[2]);
            AssetUsageVO.BrowseTrendPoint point = new AssetUsageVO.BrowseTrendPoint();
            point.setStatDate(date.format(DATE_FMT));
            point.setBrowseCount(counts[0]);
            point.setReadCount(counts[1]);
            trend.add(point);
        }
        return trend;
    }

    private List<ColumnMetadata> columnsOf(Long tableMetadataId) {
        // column_metadata 无 deleted 列
        LambdaQueryWrapper<ColumnMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ColumnMetadata::getTableMetadataId, tableMetadataId);
        return columnMetadataMapper.selectList(wrapper);
    }

    /**
     * 字段热度 TOP。
     *
     * <p>星级来自 column_metadata.hot_score（按日汇总时回写）；
     * hitCount 由该字段自己的使用汇总（asset_type='column'）累加得到 ——
     * 这是真实数据，而不是用星级反推出来的假次数。</p>
     */
    private List<AssetUsageVO.ColumnHot> buildTopColumns(List<ColumnMetadata> columns) {
        if (columns.isEmpty()) {
            return List.of();
        }
        List<Long> columnIds = new ArrayList<>();
        for (ColumnMetadata column : columns) {
            if (column.getId() != null) {
                columnIds.add(column.getId());
            }
        }

        Map<Long, Integer> hits = new HashMap<>();
        if (!columnIds.isEmpty()) {
            LambdaQueryWrapper<AssetUsageDaily> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(AssetUsageDaily::getDeleted, 0)
                    .eq(AssetUsageDaily::getAssetType, "column")
                    .in(AssetUsageDaily::getAssetId, columnIds);
            for (AssetUsageDaily row : assetUsageDailyMapper.selectList(wrapper)) {
                int count = (row.getBrowseCount() == null ? 0 : row.getBrowseCount())
                        + (row.getReadCount() == null ? 0 : row.getReadCount());
                hits.merge(row.getAssetId(), count, Integer::sum);
            }
        }

        List<AssetUsageVO.ColumnHot> result = new ArrayList<>();
        for (ColumnMetadata column : columns) {
            Integer hotScore = column.getHotScore() == null ? 0 : column.getHotScore();
            int hitCount = hits.getOrDefault(column.getId(), 0);
            if (hotScore == 0 && hitCount == 0) {
                continue;
            }
            AssetUsageVO.ColumnHot item = new AssetUsageVO.ColumnHot();
            item.setColumnName(column.getColumnName());
            item.setHotScore(hotScore);
            item.setHitCount(hitCount);
            result.add(item);
        }
        result.sort((left, right) -> Integer.compare(right.getHitCount(), left.getHitCount()));
        return result.size() > 10 ? new ArrayList<>(result.subList(0, 10)) : result;
    }

    /**
     * 高频读者：只统计 read / query ——
     * 浏览只能说明「点开看过」，读取才代表真的取数用了这张表。
     */
    private List<AssetUsageVO.TopReader> buildTopReaders(Long tableMetadataId, int window) {
        LocalDateTime from = LocalDate.now().minusDays(window - 1L).atStartOfDay();
        QueryWrapper<AssetAccessLog> wrapper = new QueryWrapper<>();
        wrapper.select("user_name AS user_name", "COUNT(*) AS read_count")
                .eq("table_metadata_id", tableMetadataId)
                .in("access_type", List.of("read", "query"))
                .ge("access_time", from)
                .isNotNull("user_name")
                .groupBy("user_name");
        List<Map<String, Object>> rows = assetAccessLogMapper.selectMaps(wrapper);

        List<AssetUsageVO.TopReader> readers = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            AssetUsageVO.TopReader item = new AssetUsageVO.TopReader();
            item.setUserName(str(row.get("user_name")));
            item.setReadCount(toInt(row.get("read_count")));
            readers.add(item);
        }
        readers.sort((left, right) -> Integer.compare(
                right.getReadCount() == null ? 0 : right.getReadCount(),
                left.getReadCount() == null ? 0 : left.getReadCount()));
        return readers.size() > 10 ? new ArrayList<>(readers.subList(0, 10)) : readers;
    }

    // ==================================================================
    // T+1 汇总
    // ==================================================================

    /**
     * 把某一天的访问明细汇总进 asset_usage_daily，并回写字段热度。
     *
     * @return 汇总摘要，便于定时任务日志与单测断言
     */
    @Transactional
    public RollupResult rollupDaily(LocalDate statDate) {
        LocalDateTime from = statDate.atStartOfDay();
        LocalDateTime to = statDate.plusDays(1).atStartOfDay();

        QueryWrapper<AssetAccessLog> wrapper = new QueryWrapper<>();
        wrapper.select("asset_type", "asset_id", "table_metadata_id", "access_type", "user_id", "user_name")
                .ge("access_time", from)
                .lt("access_time", to);
        List<Map<String, Object>> rows = assetAccessLogMapper.selectMaps(wrapper);
        if (rows.isEmpty()) {
            log.info("资产热度汇总：{} 无访问明细，跳过", statDate);
            return new RollupResult(statDate, 0, 0);
        }

        // 按「资产类型 + 资产 id」聚合当天计数
        Map<String, int[]> counted = new LinkedHashMap<>();
        Map<String, Long> tableIdOfAsset = new HashMap<>();
        Map<Long, Integer> columnHits = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String assetType = str(row.get("asset_type"));
            Long assetId = toLong(row.get("asset_id"));
            if (assetType == null || assetId == null) {
                continue;
            }
            String accessType = str(row.get("access_type"));
            String key = assetType + "\u0000" + assetId;
            int[] counts = counted.computeIfAbsent(key, k -> new int[4]);
            switch (accessType == null ? "" : accessType) {
                case "browse" -> counts[0]++;
                case "read" -> counts[1]++;
                case "bookmark" -> counts[2]++;
                default -> {
                    // query 计入读取：带条件取数同样是「用了这张表」
                    if ("query".equals(accessType)) {
                        counts[1]++;
                    }
                }
            }
            Long tableId = toLong(row.get("table_metadata_id"));
            if (tableId != null) {
                tableIdOfAsset.putIfAbsent(key, tableId);
            }
            if ("column".equals(assetType)) {
                columnHits.merge(assetId, 1, Integer::sum);
            }
        }

        int upserted = 0;
        for (Map.Entry<String, int[]> entry : counted.entrySet()) {
            String[] parts = entry.getKey().split("\u0000");
            String assetType = parts[0];
            Long assetId = Long.valueOf(parts[1]);
            int[] counts = entry.getValue();

            LambdaQueryWrapper<AssetUsageDaily> existingWrapper = new LambdaQueryWrapper<>();
            existingWrapper.eq(AssetUsageDaily::getDeleted, 0)
                    .eq(AssetUsageDaily::getAssetType, assetType)
                    .eq(AssetUsageDaily::getAssetId, assetId)
                    .eq(AssetUsageDaily::getStatDate, statDate);
            List<AssetUsageDaily> existing = assetUsageDailyMapper.selectList(existingWrapper);

            AssetUsageDaily daily = existing.isEmpty() ? new AssetUsageDaily() : existing.get(0);
            daily.setAssetType(assetType);
            daily.setAssetId(assetId);
            daily.setTableMetadataId(tableIdOfAsset.get(entry.getKey()));
            daily.setStatDate(statDate);
            daily.setBrowseCount(counts[0]);
            daily.setReadCount(counts[1]);
            daily.setBookmarkCount(counts[2]);
            daily.setRelateCount(counts[3]);
            if (existing.isEmpty()) {
                assetUsageDailyMapper.insert(daily);
            } else {
                assetUsageDailyMapper.updateById(daily);
            }
            upserted++;
        }

        int hotUpdated = refreshColumnHotScore(columnHits);
        log.info("资产热度汇总完成：日期={}, 资产数={}, 字段热度回写={}", statDate, upserted, hotUpdated);
        return new RollupResult(statDate, upserted, hotUpdated);
    }

    /**
     * 回写字段热度星级。
     *
     * <p>星级是「相对热度」而不是绝对次数：字段之间的访问量差异极大，
     * 用固定阈值会在冷库里把所有字段都判成 0 星、在热表里把所有字段都判成 5 星。
     * 这里按当天命中次数的升序把字段切成 5 档，最高档 5 星。</p>
     */
    private int refreshColumnHotScore(Map<Long, Integer> columnHits) {
        if (columnHits.isEmpty()) {
            return 0;
        }
        List<Map.Entry<Long, Integer>> sorted = new ArrayList<>(columnHits.entrySet());
        sorted.sort(Map.Entry.comparingByValue());

        int total = sorted.size();
        int updated = 0;
        for (int i = 0; i < total; i++) {
            Map.Entry<Long, Integer> entry = sorted.get(i);
            // 命中次数越高星级越高：把排名映射到 1..MAX_HOT_SCORE
            int score = Math.min(MAX_HOT_SCORE, 1 + (int) ((long) i * MAX_HOT_SCORE / total));
            if (score > MAX_HOT_SCORE) {
                score = MAX_HOT_SCORE;
            }
            ColumnMetadata update = new ColumnMetadata();
            update.setId(entry.getKey());
            update.setHotScore(score);
            updated += columnMetadataMapper.updateById(update);
        }
        return updated;
    }

    /**
     * 回写 table_metadata 上的热度冗余列（近 30 日浏览/读取、收藏数、最近访问时间）。
     *
     * <p>用聚合 SQL 而不是把明细拉回来 —— 这三列是目录列表排序的热字段，
     * 只关心结果不关心中间过程。汇总范围是近 30 日，与列语义一致。</p>
     */
    @Transactional
    public int refreshTableCounters() {
        LocalDate from = LocalDate.now().minusDays(29);

        QueryWrapper<AssetUsageDaily> usageWrapper = new QueryWrapper<>();
        usageWrapper.select("table_metadata_id AS table_id",
                        "SUM(browse_count) AS browse_cnt",
                        "SUM(read_count) AS read_cnt")
                .eq("deleted", 0)
                .eq("asset_type", "table")
                .ge("stat_date", from)
                .isNotNull("table_metadata_id")
                .groupBy("table_metadata_id");
        Map<Long, int[]> usageByTable = new HashMap<>();
        for (Map<String, Object> row : assetUsageDailyMapper.selectMaps(usageWrapper)) {
            Long tableId = toLong(row.get("table_id"));
            if (tableId == null) {
                continue;
            }
            usageByTable.put(tableId, new int[]{toInt(row.get("browse_cnt")), toInt(row.get("read_cnt"))});
        }

        QueryWrapper<AssetBookmark> bookmarkWrapper = new QueryWrapper<>();
        bookmarkWrapper.select("table_metadata_id AS table_id", "COUNT(*) AS bookmark_cnt")
                .eq("deleted", 0)
                .eq("asset_type", "table")
                .isNotNull("table_metadata_id")
                .groupBy("table_metadata_id");
        Map<Long, Integer> bookmarkByTable = new HashMap<>();
        for (Map<String, Object> row : assetBookmarkMapper.selectMaps(bookmarkWrapper)) {
            Long tableId = toLong(row.get("table_id"));
            if (tableId != null) {
                bookmarkByTable.put(tableId, toInt(row.get("bookmark_cnt")));
            }
        }

        QueryWrapper<AssetAccessLog> accessWrapper = new QueryWrapper<>();
        accessWrapper.select("table_metadata_id AS table_id", "MAX(access_time) AS last_access")
                .isNotNull("table_metadata_id")
                .groupBy("table_metadata_id");
        Map<Long, Date> lastAccessByTable = new HashMap<>();
        for (Map<String, Object> row : assetAccessLogMapper.selectMaps(accessWrapper)) {
            Long tableId = toLong(row.get("table_id"));
            Object value = row.get("last_access");
            if (tableId != null && value instanceof Date date) {
                lastAccessByTable.put(tableId, date);
            }
        }

        Set<Long> affected = new HashSet<>();
        affected.addAll(usageByTable.keySet());
        affected.addAll(bookmarkByTable.keySet());
        affected.addAll(lastAccessByTable.keySet());

        int updated = 0;
        for (Long tableId : affected) {
            int[] usage = usageByTable.getOrDefault(tableId, new int[2]);
            TableMetadata update = new TableMetadata();
            update.setId(tableId);
            update.setBrowseCnt30d(usage[0]);
            update.setReadCnt30d(usage[1]);
            update.setBookmarkCnt(bookmarkByTable.getOrDefault(tableId, 0));
            Date lastAccess = lastAccessByTable.get(tableId);
            if (lastAccess != null) {
                update.setLastAccessTime(lastAccess);
            }
            updated += tableMetadataMapper.updateById(update);
        }
        log.info("资产热度冗余列回写完成：涉及 {} 张表", updated);
        return updated;
    }

    /**
     * 收藏变化后同步单表的收藏数。
     *
     * <p>不走整表重算：收藏是用户高频动作，逐次全量汇总不划算；
     * 同时它也保证收藏数在 T+1 汇总之前就是准的。</p>
     */
    public void refreshBookmarkCount(Long tableMetadataId) {
        if (tableMetadataId == null) {
            return;
        }
        QueryWrapper<AssetBookmark> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted", 0)
                .eq("asset_type", "table")
                .eq("table_metadata_id", tableMetadataId);
        Long count = assetBookmarkMapper.selectCount(wrapper);

        TableMetadata update = new TableMetadata();
        update.setId(tableMetadataId);
        update.setBookmarkCnt(count == null ? 0 : count.intValue());
        tableMetadataMapper.updateById(update);
    }

    /** 汇总摘要 */
    public record RollupResult(LocalDate statDate, int assetCount, int hotUpdated) {
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private static String normalize(String raw, Set<String> allowed) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return allowed.contains(value) ? value : null;
    }

    private static String truncate(String raw, int max) {
        if (raw == null) {
            return null;
        }
        return raw.length() <= max ? raw : raw.substring(0, max);
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static int toInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return (int) Math.round(Double.parseDouble(value.toString().trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
