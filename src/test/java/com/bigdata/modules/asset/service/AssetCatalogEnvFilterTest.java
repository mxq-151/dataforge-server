package com.bigdata.modules.asset.service;

import com.bigdata.modules.asset.vo.AssetSearchHitVO;
import com.bigdata.modules.asset.vo.AssetSearchResultVO;
import com.bigdata.modules.asset.vo.CatalogTreeNodeVO;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * 资产目录的「按环境切换」过滤口径。
 *
 * <p>「资产目录」页签的环境切换要在<b>服务端</b>落地：库表树、检索、输入建议
 * 三处展示的内容都必须只包含所选环境的表。这里用最小 mock 验证三处过滤确实生效，
 * 并且「不传环境」时保持全量（不能因为加了环境维度而把原有行为改窄）。</p>
 *
 * <p>环境取值不落在库名后缀上（如 {@code dwd_trade}）时视为「不属于任何环境」，
 * 在显式过滤时被排除 —— 这与「不猜测环境」的既有约定一致。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssetCatalogEnvFilterTest {

    @Mock
    private TableMetadataMapper tableMetadataMapper;

    @Mock
    private AssetCatalogSnapshotService snapshotService;

    @Mock
    private AssetPermissionSupport permissionSupport;

    @InjectMocks
    private AssetCatalogService assetCatalogService;

    private static TableMetadata table(long id, String databaseName, String tableName) {
        TableMetadata table = new TableMetadata();
        table.setId(id);
        table.setDatasourceId(7L);
        table.setDatabaseName(databaseName);
        table.setTableName(tableName);
        table.setStorageEngine(DatasourceType.HIVE);
        return table;
    }

    /** 快照未生成 → 走实时回退路径；权限过滤为恒等（本测试只关心环境维度） */
    private void stubLiveSource() {
        when(snapshotService.latestStatDate()).thenReturn(null);
        when(permissionSupport.filterSelectable(anyCollection())).thenAnswer(inv -> {
            Object arg = inv.getArgument(0);
            return arg == null ? List.of() : List.copyOf((java.util.Collection<TableMetadata>) arg);
        });
        when(permissionSupport.datasourceName(any())).thenReturn("hive");
        when(tableMetadataMapper.selectList(any())).thenReturn(List.of(
                table(1L, "dwd_trade_prod", "dwd_trade_order_d"),
                table(2L, "dwd_trade_gray", "dwd_trade_order_d"),
                table(3L, "dwd_trade_test", "dwd_trade_order_d"),
                table(4L, "dwd_trade", "dwd_trade_order_d")));
    }

    private List<String> databaseLabels(List<CatalogTreeNodeVO> roots) {
        return roots.stream()
                .flatMap(root -> root.getChildren().stream())
                .map(CatalogTreeNodeVO::getLabel)
                .sorted()
                .toList();
    }

    @Test
    @DisplayName("库表树：指定环境后只保留该环境的库")
    void treeKeepsSelectedEnvOnly() {
        stubLiveSource();

        List<CatalogTreeNodeVO> roots = assetCatalogService.tree("engine", null, null, null, "prod");

        assertEquals(List.of("dwd_trade_prod"), databaseLabels(roots));
        CatalogTreeNodeVO dbNode = roots.get(0).getChildren().get(0);
        assertEquals(1, dbNode.getAssetCount());
        assertEquals(1L, dbNode.getChildren().get(0).getTableMetadataId());
    }

    @Test
    @DisplayName("库表树：不传环境时四个库（含无环境后缀的库）全部保留")
    void treeWithoutEnvKeepsEverything() {
        stubLiveSource();

        List<CatalogTreeNodeVO> roots = assetCatalogService.tree("engine", null, null, null, null);

        assertEquals(List.of("dwd_trade", "dwd_trade_gray", "dwd_trade_prod", "dwd_trade_test"),
                databaseLabels(roots));
    }

    @Test
    @DisplayName("库表树：环境值非法时按「不过滤」处理，不清空结果")
    void treeWithIllegalEnvFallsBackToAll() {
        stubLiveSource();

        List<CatalogTreeNodeVO> roots = assetCatalogService.tree("engine", null, null, null, "production");

        assertEquals(4, databaseLabels(roots).size());
    }

    @Test
    @DisplayName("检索：只命中所选环境的表")
    void searchKeepsSelectedEnvOnly() {
        stubLiveSource();

        AssetSearchResultVO result = assetCatalogService.search(
                "order_d", "table", 1, 20, null, null, "test");

        assertEquals(1, result.getTotal());
        AssetSearchHitVO hit = result.getTables().get(0);
        assertEquals(3L, hit.getTableMetadataId());
        assertTrue(hit.getSubtitle().startsWith("dwd_trade_test."),
                "命中项副标题应指向测试库，实际=" + hit.getSubtitle());
    }

    @Test
    @DisplayName("输入建议：只返回所选环境的表名，且不传环境时仍可用")
    void suggestRespectsEnv() {
        stubLiveSource();

        List<String> prodNames = assetCatalogService.suggest("order_d", "prod");
        assertEquals(List.of("dwd_trade_order_d"), prodNames);

        // 表名在四个库里同名，全量口径去重后仍是一条
        assertEquals(List.of("dwd_trade_order_d"), assetCatalogService.suggest("order_d", null));
    }
}
