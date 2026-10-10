package com.bigdata.modules.asset.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.bigdata.modules.asset.entity.AssetCatalogDaily;
import com.bigdata.modules.asset.entity.AssetPartitionSizeDaily;
import com.bigdata.modules.asset.vo.AssetSizeGrowthVO;
import com.bigdata.modules.development.mapper.DevProjectMapper;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * sizeGrowth 双口径测试：全量表按「最新快照 vs 近 N 天快照均值」、
 * 天分区表按「最新分区 vs 近 N 个分区均值」。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AssetInventoryService.sizeGrowth - 分区表/全量表双口径")
class AssetInventorySizeGrowthTest {

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TableMetadata.class);
    }

    @Mock
    private AssetCatalogSnapshotService snapshotService;

    @Mock
    private AssetPermissionSupport permissionSupport;

    @Mock
    private TableMetadataMapper tableMetadataMapper;

    @Mock
    private DevProjectMapper devProjectMapper;

    @Mock
    private AssetPartitionSizeCollectService partitionSizeService;

    @InjectMocks
    private AssetInventoryService inventoryService;

    private static final LocalDate CURRENT = LocalDate.of(2026, 10, 9);

    private AssetCatalogDaily catalog(LocalDate statDate, Long tableId, String tableName, Long size) {
        AssetCatalogDaily row = new AssetCatalogDaily();
        row.setStatDate(statDate);
        row.setTableMetadataId(tableId);
        row.setDatasourceId(1L);
        row.setDatabaseName("dw_prod");
        row.setTableName(tableName);
        row.setSizeBytes(size);
        return row;
    }

    private AssetPartitionSizeDaily partition(LocalDate statDate, Long tableId, String tableName,
                                              Long size, String value) {
        AssetPartitionSizeDaily row = new AssetPartitionSizeDaily();
        row.setStatDate(statDate);
        row.setTableMetadataId(tableId);
        row.setDatasourceId(1L);
        row.setDatabaseName("dw_prod");
        row.setTableName(tableName);
        row.setSizeBytes(size);
        row.setPartitionValue(value);
        return row;
    }

    private TableMetadata table(Long id, String partitionColumns) {
        TableMetadata table = new TableMetadata();
        table.setId(id);
        table.setPartitionColumns(partitionColumns);
        return table;
    }

    @Test
    @DisplayName("分区表与全量表各按自己的口径计算，并标记 tableType")
    void computesBothBranches() {
        when(snapshotService.latestStatDate()).thenReturn(CURRENT);
        when(snapshotService.listStatDates()).thenReturn(List.of(
                CURRENT.minusDays(6), CURRENT.minusDays(5), CURRENT.minusDays(4),
                CURRENT.minusDays(3), CURRENT.minusDays(2), CURRENT.minusDays(1), CURRENT));

        when(snapshotService.load(eq(CURRENT), any())).thenReturn(List.of(
                catalog(CURRENT, 1L, "dim_user", 2000L),
                catalog(CURRENT, 2L, "dwd_order_detail_d", 999_999_999L)));

        // 权限过滤在单测里直接放行
        when(permissionSupport.filterSelectableSnapshots(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // 分区表判定：2 号表分区列为 dt
        when(tableMetadataMapper.selectList(any())).thenReturn(List.of(
                table(1L, null), table(2L, "dt")));

        // 全量表历史：近 6 天各 100 字节 → 均值 100
        List<AssetCatalogDaily> history = new ArrayList<>();
        for (int i = 6; i >= 1; i--) {
            history.add(catalog(CURRENT.minusDays(i), 1L, "dim_user", 100L));
        }
        when(snapshotService.loadSizeHistory(any(), any())).thenReturn(history);

        // 分区表历史：前一分区 1000、最新分区 3000 → 均值 1000
        when(partitionSizeService.loadSince(any())).thenReturn(List.of(
                partition(CURRENT.minusDays(1), 2L, "dwd_order_detail_d", 1000L, "2026-10-08"),
                partition(CURRENT, 2L, "dwd_order_detail_d", 3000L, "2026-10-09")));

        when(devProjectMapper.selectList(any())).thenReturn(List.of());

        List<AssetSizeGrowthVO> rows = inventoryService.sizeGrowth(10, 0L, 7, null);

        assertEquals(2, rows.size());
        // 按增长率降序：全量表 (2000-100)/100 = 19.0 在前
        AssetSizeGrowthVO full = rows.get(0);
        assertEquals(1L, full.getTableMetadataId());
        assertEquals("FULL", full.getTableType());
        assertEquals(19.0d, full.getGrowthRate(), 1e-6);
        assertEquals(100L, full.getPreviousSizeBytes());

        AssetSizeGrowthVO partitioned = rows.get(1);
        assertEquals(2L, partitioned.getTableMetadataId());
        assertEquals("PARTITIONED", partitioned.getTableType());
        assertEquals(2.0d, partitioned.getGrowthRate(), 1e-6);
        assertEquals(1000L, partitioned.getPreviousSizeBytes());
        assertEquals("2026-10-09", partitioned.getPartitionValue());
        assertEquals(1, partitioned.getCompareDays());
        assertTrue(rows.get(0).getGrowthRate() > rows.get(1).getGrowthRate());
    }

    @Test
    @DisplayName("分区表记录不足两期时不显示，不退回整表口径")
    void partitionedWithoutEnoughHistoryIsSkipped() {
        when(snapshotService.latestStatDate()).thenReturn(CURRENT);
        when(snapshotService.listStatDates()).thenReturn(List.of(CURRENT));
        when(snapshotService.load(eq(CURRENT), any())).thenReturn(List.of(
                catalog(CURRENT, 2L, "dwd_order_detail_d", 999_999_999L)));
        when(permissionSupport.filterSelectableSnapshots(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(tableMetadataMapper.selectList(any())).thenReturn(List.of(table(2L, "dt")));
        // 只有一条分区记录
        when(partitionSizeService.loadSince(any())).thenReturn(List.of(
                partition(CURRENT, 2L, "dwd_order_detail_d", 3000L, "2026-10-09")));
        when(devProjectMapper.selectList(any())).thenReturn(List.of());

        List<AssetSizeGrowthVO> rows = inventoryService.sizeGrowth(10, 0L, 7, null);

        assertTrue(rows.isEmpty());
    }
}
