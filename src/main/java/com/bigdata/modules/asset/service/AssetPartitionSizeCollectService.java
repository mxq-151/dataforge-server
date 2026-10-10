package com.bigdata.modules.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bigdata.modules.asset.entity.AssetPartitionSizeDaily;
import com.bigdata.modules.asset.mapper.AssetPartitionSizeDailyMapper;
import com.bigdata.modules.development.service.execution.ExecutionStrategy;
import com.bigdata.modules.development.service.execution.ExecutionStrategyFactory;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.service.DatasourceConfigService;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 分区大小采集 —— 每日记录每张天分区表「当日最新分区」的值与大小，落 {@code table_partition_size_daily}。
 *
 * <h3>只采天分区表</h3>
 * <p>分区表整表大小只增不减，增长率要下沉到分区粒度才看得出单日突增；全量表不在此表记录
 * （其每日整表大小已由 {@code asset_catalog_daily} 覆盖）。天分区表的判定统一走
 * {@link DailyPartitionDetector}，不在此另立一套口径。</p>
 *
 * <h3>幂等与隔离</h3>
 * <p>先按 {@code stat_date} 删旧、再整体写入（照抄目录快照的幂等覆盖写）；单个数据源/库取数失败
 * 只跳过该范围并告警，不影响其余数据源，也不会清空存量值。</p>
 *
 * @author dataforge
 */
@Service
public class AssetPartitionSizeCollectService {

    private static final Logger log = LoggerFactory.getLogger(AssetPartitionSizeCollectService.class);

    /** 分区日大小保留天数：超过则随每日采集一并清理 */
    public static final int RETENTION_DAYS = 180;

    @Autowired
    private TableMetadataMapper tableMetadataMapper;

    @Autowired
    private DatasourceConfigService datasourceConfigService;

    @Autowired
    @Lazy
    private ExecutionStrategyFactory executionStrategyFactory;

    @Autowired
    private AssetPartitionSizeDailyMapper assetPartitionSizeDailyMapper;

    /** 采集摘要，便于定时任务日志与单测断言 */
    public record CollectResult(LocalDate statDate, int partitionTableCount, int rowCount) {
    }

    /**
     * 采集指定统计日的天分区表最新分区大小。幂等：先删当日旧数据再整体重写，重复执行结果一致。
     *
     * <p>调用方通常是每日定时任务，传入「昨天」。单测可直接传任意日期。</p>
     */
    @Transactional
    public CollectResult collect(LocalDate statDate) {
        List<TableMetadata> tables = tableMetadataMapper.selectList(
                new LambdaQueryWrapper<TableMetadata>()
                        .select(TableMetadata::getId, TableMetadata::getDatasourceId,
                                TableMetadata::getDatabaseName, TableMetadata::getTableName,
                                TableMetadata::getPartitionColumns));

        deleteByStatDate(statDate);

        // 数据源 → 库 → 天分区表；先按数据源与库归组，才能一次连接采一批表
        Map<Long, Map<String, List<PartitionTableRef>>> grouped = new LinkedHashMap<>();
        int partitionTables = 0;
        for (TableMetadata table : tables) {
            if (table.getId() == null || table.getDatasourceId() == null
                    || table.getDatabaseName() == null || table.getTableName() == null) {
                continue;
            }
            if (!DailyPartitionDetector.isDailyPartitioned(table.getPartitionColumns())) {
                continue;
            }
            partitionTables++;
            grouped.computeIfAbsent(table.getDatasourceId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(table.getDatabaseName(), k -> new ArrayList<>())
                    .add(new PartitionTableRef(table.getId(), table.getTableName(), table.getPartitionColumns()));
        }

        if (grouped.isEmpty()) {
            log.info("分区大小采集：{} 无天分区表", statDate);
            return new CollectResult(statDate, 0, 0);
        }

        int rowCount = 0;
        for (Map.Entry<Long, Map<String, List<PartitionTableRef>>> datasourceEntry : grouped.entrySet()) {
            DatasourceConfig config = datasourceConfigService.getDatasourceById(datasourceEntry.getKey());
            if (config == null) {
                log.warn("分区大小采集：数据源不存在，跳过 id={}", datasourceEntry.getKey());
                continue;
            }
            ExecutionStrategy strategy;
            try {
                strategy = executionStrategyFactory.getStrategy(config);
            } catch (Exception e) {
                log.warn("分区大小采集：数据源无可用策略，跳过 name={}, reason={}",
                        config.getDatasourceName(), e.getMessage());
                continue;
            }
            for (Map.Entry<String, List<PartitionTableRef>> databaseEntry : datasourceEntry.getValue().entrySet()) {
                rowCount += collectDatabase(statDate, config, strategy,
                        databaseEntry.getKey(), databaseEntry.getValue());
            }
        }
        log.info("分区大小采集完成：日期={}, 天分区表数={}, 落库行数={}", statDate, partitionTables, rowCount);
        return new CollectResult(statDate, partitionTables, rowCount);
    }

    /** 采集单个库：一次取回该库分区大小，按表写行 */
    private int collectDatabase(LocalDate statDate, DatasourceConfig config, ExecutionStrategy strategy,
                                String databaseName, List<PartitionTableRef> refs) {
        List<String> tableNames = new ArrayList<>(refs.size());
        Map<String, PartitionTableRef> refByName = new HashMap<>();
        for (PartitionTableRef ref : refs) {
            tableNames.add(ref.tableName());
            refByName.put(ref.tableName().toLowerCase(Locale.ROOT), ref);
        }
        Map<String, ExecutionStrategy.TablePartitionSize> sizes;
        try {
            sizes = strategy.listLatestPartitions(config, databaseName, tableNames);
        } catch (Exception e) {
            log.warn("分区大小采集：库采集失败，跳过 name={}, database={}, reason={}",
                    config.getDatasourceName(), databaseName, e.getMessage());
            return 0;
        }
        if (sizes == null || sizes.isEmpty()) {
            return 0;
        }
        int written = 0;
        for (Map.Entry<String, ExecutionStrategy.TablePartitionSize> entry : sizes.entrySet()) {
            PartitionTableRef ref = entry.getKey() == null
                    ? null : refByName.get(entry.getKey().toLowerCase(Locale.ROOT));
            ExecutionStrategy.TablePartitionSize size = entry.getValue();
            if (ref == null || size == null || size.sizeBytes() == null || size.sizeBytes() <= 0) {
                continue;
            }
            AssetPartitionSizeDaily row = new AssetPartitionSizeDaily();
            row.setStatDate(statDate);
            row.setTableMetadataId(ref.id());
            row.setDatasourceId(config.getId());
            row.setDatabaseName(databaseName);
            row.setTableName(ref.tableName());
            row.setPartitionColumn(DailyPartitionDetector.datePartitionColumn(ref.partitionColumns()));
            row.setPartitionValue(size.partitionValue());
            row.setSizeBytes(size.sizeBytes());
            assetPartitionSizeDailyMapper.insert(row);
            written++;
        }
        return written;
    }

    /** 清理早于 cutoff 的分区日大小，返回删除行数 */
    public int purgeBefore(LocalDate cutoff) {
        int deleted = assetPartitionSizeDailyMapper.delete(
                new LambdaQueryWrapper<AssetPartitionSizeDaily>()
                        .lt(AssetPartitionSizeDaily::getStatDate, cutoff));
        if (deleted > 0) {
            log.info("分区日大小清理：早于 {} 的记录删除 {} 行", cutoff, deleted);
        }
        return deleted;
    }

    /**
     * 读取统计日不早于 {@code fromStatDate} 的分区大小记录（按统计日升序）。
     *
     * <p>供增长率看板按窗口取数：只投影算增长率需要的列，不取行数等无关字段。</p>
     */
    public List<AssetPartitionSizeDaily> loadSince(LocalDate fromStatDate) {
        LambdaQueryWrapper<AssetPartitionSizeDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(AssetPartitionSizeDaily::getStatDate, AssetPartitionSizeDaily::getTableMetadataId,
                        AssetPartitionSizeDaily::getTableName, AssetPartitionSizeDaily::getPartitionValue,
                        AssetPartitionSizeDaily::getSizeBytes)
                .ge(AssetPartitionSizeDaily::getStatDate, fromStatDate)
                .orderByAsc(AssetPartitionSizeDaily::getStatDate);
        return assetPartitionSizeDailyMapper.selectList(wrapper);
    }

    private void deleteByStatDate(LocalDate statDate) {
        assetPartitionSizeDailyMapper.delete(new LambdaQueryWrapper<AssetPartitionSizeDaily>()
                .eq(AssetPartitionSizeDaily::getStatDate, statDate));
    }

    /** 待采集的天分区表引用（id + 表名 + 分区列） */
    private record PartitionTableRef(Long id, String tableName, String partitionColumns) {
    }
}
