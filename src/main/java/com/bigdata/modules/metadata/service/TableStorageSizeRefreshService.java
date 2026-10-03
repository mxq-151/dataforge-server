package com.bigdata.modules.metadata.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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

import java.util.List;
import java.util.Map;

/**
 * 表存储大小按日刷新 —— 只更新 {@code table_metadata.size_bytes}。
 *
 * <h3>为什么独立于全量同步</h3>
 * <p>结构类元数据（表/字段/注释/分区）只在变更时需要同步，而存储大小每天都在变。
 * 本服务借 {@link ExecutionStrategy#listTableSizes} 按引擎走低成本系统表口径
 * （JDBC 查 information_schema / system.tables，Kyuubi 走 DESCRIBE EXTENDED，
 * HBase 走 RegionMetrics），不重建字段、不动其他列，可安全地每日执行。</p>
 *
 * <h3>覆盖写与幂等</h3>
 * <p>只按「数据源 + 库 + 表名」匹配已纳管表，取到多少写多少：漏跑一天、单库连接失败，
 * 都不会清空存量值；重复执行结果一致。表名大小写按系统表原样匹配
 * （与 listTables 同源，不会引入新的口径差异）。</p>
 *
 * <h3>系统库不参与</h3>
 * <p>复用 {@link MetadataSyncService#isSystemDatabase} 的排除口径，与全量同步保持一致。</p>
 *
 * @author dataforge
 */
@Service
public class TableStorageSizeRefreshService {

    private static final Logger log = LoggerFactory.getLogger(TableStorageSizeRefreshService.class);

    @Autowired
    private DatasourceConfigService datasourceConfigService;

    @Autowired
    @Lazy
    private ExecutionStrategyFactory executionStrategyFactory;

    @Autowired
    private TableMetadataMapper tableMetadataMapper;

    /** 刷新摘要，便于定时任务日志与单测断言 */
    public record RefreshResult(int datasourceCount, int updatedTables, int failedDatasources) {
    }

    /**
     * 刷新全部数据源的表存储大小。
     *
     * <p>逐数据源独立执行：单个数据源失败（连不上、权限不足）只计数并继续，
     * 不影响其余数据源。</p>
     */
    public RefreshResult refreshAll() {
        List<DatasourceConfig> datasources = datasourceConfigService.getAllDatasources();
        int updated = 0;
        int failed = 0;
        for (DatasourceConfig config : datasources) {
            if (config == null || config.getId() == null) {
                continue;
            }
            try {
                updated += refreshDatasource(config);
            } catch (Exception e) {
                failed++;
                log.warn("刷新数据源存储大小失败: id={}, name={}, reason={}",
                        config.getId(), config.getDatasourceName(), e.getMessage());
            }
        }
        return new RefreshResult(datasources.size(), updated, failed);
    }

    /**
     * 刷新单个数据源下全部库的表存储大小，返回实际更新的表数。
     */
    public int refreshDatasource(DatasourceConfig config) {
        ExecutionStrategy strategy = executionStrategyFactory.getStrategy(config);
        List<String> databases = strategy.listDatabases(config);
        if (databases == null || databases.isEmpty()) {
            return 0;
        }
        int updated = 0;
        for (String databaseName : databases) {
            if (MetadataSyncService.isSystemDatabase(databaseName)) {
                continue;
            }
            Map<String, Long> sizes = strategy.listTableSizes(config, databaseName);
            for (Map.Entry<String, Long> entry : sizes.entrySet()) {
                Long size = entry.getValue();
                if (size == null || size <= 0 || entry.getKey() == null) {
                    continue;
                }
                LambdaUpdateWrapper<TableMetadata> wrapper = new LambdaUpdateWrapper<>();
                wrapper.eq(TableMetadata::getDatasourceId, config.getId())
                        .eq(TableMetadata::getDatabaseName, databaseName)
                        .eq(TableMetadata::getTableName, entry.getKey())
                        .set(TableMetadata::getSizeBytes, size);
                updated += tableMetadataMapper.update(null, wrapper);
            }
        }
        return updated;
    }
}