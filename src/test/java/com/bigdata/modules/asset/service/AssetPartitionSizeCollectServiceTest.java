package com.bigdata.modules.asset.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.bigdata.modules.asset.entity.AssetPartitionSizeDaily;
import com.bigdata.modules.asset.mapper.AssetPartitionSizeDailyMapper;
import com.bigdata.modules.development.service.execution.ExecutionStrategy;
import com.bigdata.modules.development.service.execution.ExecutionStrategyFactory;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.devops.service.DatasourceConfigService;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AssetPartitionSizeCollectService 单测：只采天分区表、按数据源/库隔离、失败不阻断。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AssetPartitionSizeCollectService - 分区大小采集")
class AssetPartitionSizeCollectServiceTest {

    /** LambdaQueryWrapper 依赖 MyBatis-Plus 实体字段缓存，纯 Mockito 需手动初始化 */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TableMetadata.class);
        TableInfoHelper.initTableInfo(assistant, AssetPartitionSizeDaily.class);
    }

    @Mock
    private TableMetadataMapper tableMetadataMapper;

    @Mock
    private DatasourceConfigService datasourceConfigService;

    @Mock
    private ExecutionStrategyFactory executionStrategyFactory;

    @Mock
    private AssetPartitionSizeDailyMapper assetPartitionSizeDailyMapper;

    @Mock
    private ExecutionStrategy strategy;

    @InjectMocks
    private AssetPartitionSizeCollectService collectService;

    private DatasourceConfig config() {
        DatasourceConfig config = new DatasourceConfig();
        config.setId(1L);
        config.setDatasourceName("mysql-prod");
        config.setDatasourceType(DatasourceType.MYSQL);
        return config;
    }

    private TableMetadata table(Long id, String tableName, String partitionColumns) {
        TableMetadata table = new TableMetadata();
        table.setId(id);
        table.setDatasourceId(1L);
        table.setDatabaseName("dw");
        table.setTableName(tableName);
        table.setPartitionColumns(partitionColumns);
        return table;
    }

    @Test
    @DisplayName("只采天分区表并落库，非日期分区列与无分区列的表跳过")
    void collectsOnlyDailyPartitionedTables() {
        when(tableMetadataMapper.selectList(any())).thenReturn(Arrays.asList(
                table(1L, "dwd_order_detail_d", "dt"),
                table(2L, "dws_user_summary_f", "created_at"),
                table(3L, "dwd_log_d", null)));
        when(assetPartitionSizeDailyMapper.delete(any())).thenReturn(0);

        DatasourceConfig config = config();
        when(datasourceConfigService.getDatasourceById(1L)).thenReturn(config);
        when(executionStrategyFactory.getStrategy(config)).thenReturn(strategy);
        when(strategy.listLatestPartitions(eq(config), eq("dw"), anyList()))
                .thenReturn(Map.of("dwd_order_detail_d",
                        new ExecutionStrategy.TablePartitionSize("2026-10-09", 1024L)));
        when(assetPartitionSizeDailyMapper.insert(any(AssetPartitionSizeDaily.class))).thenReturn(1);

        AssetPartitionSizeCollectService.CollectResult result =
                collectService.collect(LocalDate.of(2026, 10, 9));

        assertEquals(1, result.partitionTableCount());
        assertEquals(1, result.rowCount());
        verify(assetPartitionSizeDailyMapper, times(1)).insert(any(AssetPartitionSizeDaily.class));
    }

    @Test
    @DisplayName("库采集失败时不落库、不抛异常，返回 0 行")
    void toleratesPerDatabaseFailure() {
        when(tableMetadataMapper.selectList(any())).thenReturn(
                List.of(table(1L, "dwd_order_detail_d", "dt")));
        when(assetPartitionSizeDailyMapper.delete(any())).thenReturn(0);

        DatasourceConfig config = config();
        when(datasourceConfigService.getDatasourceById(1L)).thenReturn(config);
        when(executionStrategyFactory.getStrategy(config)).thenReturn(strategy);
        when(strategy.listLatestPartitions(eq(config), eq("dw"), anyList()))
                .thenThrow(new RuntimeException("connection refused"));

        AssetPartitionSizeCollectService.CollectResult result =
                collectService.collect(LocalDate.of(2026, 10, 9));

        assertEquals(1, result.partitionTableCount());
        assertEquals(0, result.rowCount());
        verify(assetPartitionSizeDailyMapper, never()).insert(any(AssetPartitionSizeDaily.class));
    }

    @Test
    @DisplayName("清理早于 cutoff 的历史")
    void purgeBeforeDelegates() {
        when(assetPartitionSizeDailyMapper.delete(any())).thenReturn(5);
        assertEquals(5, collectService.purgeBefore(LocalDate.of(2026, 4, 1)));
    }
}
