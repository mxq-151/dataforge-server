package com.bigdata.modules.asset.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.mapper.DatasourceConfigMapper;
import com.bigdata.modules.metadata.entity.TableMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资产权限适配层的 fail-close 语义测试。
 *
 * <p>资产目录一次要展示成百上千张表。如果过滤逻辑出了「取不到权限就放行」这类问题，
 * 越权的库名表名会直接到达客户端 —— 那是信息披露，不是权限控制。
 * 因此这里把三种收敛行为钉死：管理员放行、普通用户按权限收敛、无权限返回空。</p>
 *
 * <p>另外验证「按数据源 + 库分组调用」：权限匹配规则同时含数据源与库两个维度，
 * 把跨源跨库的表揉成一批交给权限服务会匹配错表。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("资产权限适配 —— fail-close 批量过滤")
class AssetPermissionSupportTest {

    @Mock
    private DataAccessPermissionService permissionService;

    @Mock
    private DatasourceConfigMapper datasourceConfigMapper;

    @InjectMocks
    private AssetPermissionSupport support;

    private TableMetadata table(long id, long datasourceId, String database, String name) {
        TableMetadata table = new TableMetadata();
        table.setId(id);
        table.setDatasourceId(datasourceId);
        table.setDatabaseName(database);
        table.setTableName(name);
        return table;
    }

    private void stubDatasource(long id, String name) {
        DatasourceConfig config = new DatasourceConfig();
        config.setId(id);
        config.setDatasourceName(name);
        when(datasourceConfigMapper.selectById(id)).thenReturn(config);
    }

    @Test
    @DisplayName("管理员跳过过滤，全量返回且顺序不变")
    void adminBypassesFiltering() {
        when(permissionService.isAdmin()).thenReturn(true);
        List<TableMetadata> input = List.of(
                table(1L, 10L, "dw", "dwd_a_d"),
                table(2L, 10L, "dw", "dwd_b_d"));

        List<TableMetadata> result = support.filterSelectable(input);

        assertEquals(2, result.size());
        assertEquals("dwd_a_d", result.get(0).getTableName());
        assertEquals("dwd_b_d", result.get(1).getTableName());
    }

    @Test
    @DisplayName("普通用户：只保留权限命中的表，且保持入参顺序")
    void filtersByGrantedTables() {
        when(permissionService.isAdmin()).thenReturn(false);
        stubDatasource(10L, "ds_main");
        when(permissionService.filterAccessibleTables(
                eq("ds_main"), eq("dw"), anyCollection(), eq(DataAccessPermissionService.TABLE_OP_SELECT)))
                .thenReturn(Set.of("dwd_b_d"));

        List<TableMetadata> result = support.filterSelectable(List.of(
                table(1L, 10L, "dw", "dwd_a_d"),
                table(2L, 10L, "dw", "dwd_b_d"),
                table(3L, 10L, "dw", "dwd_c_d")));

        assertEquals(1, result.size());
        assertEquals("dwd_b_d", result.get(0).getTableName());
        assertEquals(2L, result.get(0).getId());
    }

    @Test
    @DisplayName("无任何权限时返回空集合（fail-close，绝不降级为「全可见」）")
    void deniesWhenNoPermission() {
        when(permissionService.isAdmin()).thenReturn(false);
        stubDatasource(10L, "ds_main");
        when(permissionService.filterAccessibleTables(
                any(), any(), anyCollection(), any())).thenReturn(Set.of());

        List<TableMetadata> result = support.filterSelectable(List.of(
                table(1L, 10L, "dw", "dwd_a_d")));

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("按数据源 + 库分组调用：不同库分别判定，不揉成一批")
    void groupsByDatasourceAndDatabase() {
        when(permissionService.isAdmin()).thenReturn(false);
        stubDatasource(10L, "ds_main");
        stubDatasource(20L, "ds_other");
        when(permissionService.filterAccessibleTables(
                eq("ds_main"), eq("dw"), anyCollection(), any())).thenReturn(Set.of("dwd_a_d"));
        when(permissionService.filterAccessibleTables(
                eq("ds_main"), eq("dm"), anyCollection(), any())).thenReturn(Set.of("dim_x_d"));
        when(permissionService.filterAccessibleTables(
                eq("ds_other"), eq("dw"), anyCollection(), any())).thenReturn(Set.of("dwd_a_d"));

        List<TableMetadata> result = support.filterSelectable(List.of(
                table(1L, 10L, "dw", "dwd_a_d"),
                table(2L, 10L, "dm", "dim_x_d"),
                table(3L, 20L, "dw", "dwd_a_d"),
                table(4L, 20L, "dw", "dwd_z_d")));

        // ds_other.dw.dwd_a_d 可见、dwd_z_d 不可见；共 3 张
        assertEquals(3, result.size());
        verify(permissionService, times(1)).filterAccessibleTables(
                eq("ds_main"), eq("dw"), anyCollection(), any());
        verify(permissionService, times(1)).filterAccessibleTables(
                eq("ds_main"), eq("dm"), anyCollection(), any());
        verify(permissionService, times(1)).filterAccessibleTables(
                eq("ds_other"), eq("dw"), anyCollection(), any());
    }

    @Test
    @DisplayName("空入参不做任何权限查询，直接返回空")
    void emptyInputShortCircuits() {
        assertTrue(support.filterSelectable(List.of()).isEmpty());
        assertTrue(support.filterSelectable(null).isEmpty());
        verify(permissionService, times(0)).filterAccessibleTables(any(), any(), anyCollection(), any());
    }

    @Test
    @DisplayName("单表详情校验：委托给平台既有的 checkTablePermission（无权限时抛 403）")
    void checkSelectableDelegatesToPlatformCheck() {
        stubDatasource(10L, "ds_main");
        TableMetadata table = table(1L, 10L, "dw", "dwd_a_d");

        support.checkSelectable(table);

        verify(permissionService).checkTablePermission(
                eq("ds_main"), eq("dw"), eq("dwd_a_d"),
                eq(DataAccessPermissionService.TABLE_OP_SELECT));
    }

    @Test
    @DisplayName("表不存在时抛 404 而不是静默通过")
    void checkSelectableRejectsMissingTable() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> support.checkSelectable(null));
        assertEquals(404, error.getCode());
    }
}
