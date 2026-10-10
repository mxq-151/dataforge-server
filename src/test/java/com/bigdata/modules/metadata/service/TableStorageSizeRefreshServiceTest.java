package com.bigdata.modules.metadata.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * TableStorageSizeRefreshService 单测：验证按库刷新、系统库跳过、失败隔离与幂等覆盖语义。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TableStorageSizeRefreshService - 存储大小按日刷新")
class TableStorageSizeRefreshServiceTest {

    /**
     * LambdaUpdateWrapper 依赖 MyBatis-Plus 的实体字段缓存；
     * 纯 Mockito 单测无 MyBatis 运行时，需手动初始化一次。
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TableMetadata.class);
    }

    @Mock
    private DatasourceConfigService datasourceConfigService;

    @Mock
    private ExecutionStrategyFactory executionStrategyFactory;

    @Mock
    private TableMetadataMapper tableMetadataMapper;

    @Mock
    private ExecutionStrategy strategy;

    @InjectMocks
    private TableStorageSizeRefreshService refreshService;

    private DatasourceConfig config(Long id, String name) {
        DatasourceConfig config = new DatasourceConfig();
        config.setId(id);
        config.setDatasourceName(name);
        config.setDatasourceType(DatasourceType.MYSQL);
        return config;
    }

    @Test
    @DisplayName("按库刷新并跳过系统库；只对有大小的表执行覆盖更新")
    void testRefreshDatasource_skipsSystemDatabases() {
        DatasourceConfig config = config(1L, "mysql-prod");
        when(executionStrategyFactory.getStrategy(config)).thenReturn(strategy);
        when(strategy.listDatabases(config)).thenReturn(List.of("dw", "information_schema", "mysql"));
        Map<String, Long> sizes = new HashMap<>();
        sizes.put("order_detail", 4096L);
        sizes.put("empty_tbl", 0L);
        when(strategy.listTableSizes(config, "dw")).thenReturn(sizes);
        when(tableMetadataMapper.update(isNull(), any())).thenReturn(1);

        int updated = refreshService.refreshDatasource(config);

        assertEquals(1, updated, "大小为 0 的表不应触发更新");
        verify(strategy, never()).listTableSizes(config, "information_schema");
        verify(strategy, never()).listTableSizes(config, "mysql");
        verify(tableMetadataMapper, times(1)).update(isNull(), any());
    }

    @Test
    @DisplayName("单数据源失败被隔离，其余数据源继续刷新并计数")
    void testRefreshAll_isolatesFailure() {
        DatasourceConfig bad = config(1L, "bad");
        DatasourceConfig good = config(2L, "good");
        when(datasourceConfigService.getAllDatasources()).thenReturn(List.of(bad, good));
        when(executionStrategyFactory.getStrategy(bad)).thenThrow(new RuntimeException("连接失败"));
        when(executionStrategyFactory.getStrategy(good)).thenReturn(strategy);
        when(strategy.listDatabases(good)).thenReturn(List.of("dw"));
        when(strategy.listTableSizes(good, "dw")).thenReturn(Map.of("t1", 128L));
        when(tableMetadataMapper.update(isNull(), any())).thenReturn(1);

        TableStorageSizeRefreshService.RefreshResult result = refreshService.refreshAll();

        assertEquals(2, result.datasourceCount());
        assertEquals(1, result.updatedTables());
        assertEquals(1, result.failedDatasources());
    }

    @Test
    @DisplayName("无数据源时返回零值摘要，不抛异常")
    void testRefreshAll_noDatasources() {
        when(datasourceConfigService.getAllDatasources()).thenReturn(List.of());

        TableStorageSizeRefreshService.RefreshResult result = refreshService.refreshAll();

        assertEquals(0, result.datasourceCount());
        assertEquals(0, result.updatedTables());
        assertEquals(0, result.failedDatasources());
    }

    @Test
    @DisplayName("行数与大小同轮写入：只有行数的表也更新，行数 0 是合法值")
    void testRefreshDatasource_writesRowCount() {
        DatasourceConfig config = config(1L, "mysql-prod");
        when(executionStrategyFactory.getStrategy(config)).thenReturn(strategy);
        when(strategy.listDatabases(config)).thenReturn(List.of("dw"));
        // order_detail 两者都有；empty_tbl 只有行数（真实存在的空表）；size_only 只有大小
        when(strategy.listTableSizes(config, "dw")).thenReturn(Map.of("order_detail", 4096L, "size_only", 8192L));
        when(strategy.listTableRowCounts(config, "dw")).thenReturn(Map.of("order_detail", 120L, "empty_tbl", 0L));
        when(tableMetadataMapper.update(isNull(), any())).thenReturn(1);

        int updated = refreshService.refreshDatasource(config);

        assertEquals(3, updated,
                "order_detail（大小+行数）、empty_tbl（仅行数 0）、size_only（仅大小）三张表都应各更新一次");
        verify(strategy).listTableRowCounts(config, "dw");
        verify(tableMetadataMapper, times(3)).update(isNull(), any());
    }

    @Test
    @DisplayName("既无大小也无行数的表不产生更新语句")
    void testRefreshDatasource_skipsTableWithoutStats() {
        DatasourceConfig config = config(1L, "mysql-prod");
        when(executionStrategyFactory.getStrategy(config)).thenReturn(strategy);
        when(strategy.listDatabases(config)).thenReturn(List.of("dw"));
        when(strategy.listTableSizes(config, "dw")).thenReturn(Map.of("empty_tbl", 0L));
        when(strategy.listTableRowCounts(config, "dw")).thenReturn(Map.of());

        int updated = refreshService.refreshDatasource(config);

        assertEquals(0, updated);
        verify(tableMetadataMapper, never()).update(isNull(), any());
    }
}