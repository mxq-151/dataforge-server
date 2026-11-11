package com.bigdata.modules.retrieval.service;

import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.modules.asset.service.AssetPermissionSupport;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.ColumnMetadataMapper;
import com.bigdata.modules.metadata.mapper.IndicatorMapper;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import com.bigdata.modules.retrieval.vo.AgentFilters;
import com.bigdata.modules.retrieval.vo.AgentSearchItem;
import com.bigdata.modules.retrieval.vo.AgentSearchRequest;
import com.bigdata.modules.retrieval.vo.AgentSearchResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * 智能体库表检索的口径测试。
 *
 * <p>这里锁的是四条最容易回退的约定：</p>
 * <ol>
 *   <li>环境/分层/业务域在服务端过滤，不依赖模型在候选里挑；</li>
 *   <li>权限 fail-close，未授权的表不能出现在 items 或 notFound.nearest；</li>
 *   <li>名称精确命中优先于注释命中（相关度是可解释的规则，不是玄学）；</li>
 *   <li>未命中必须给出「试过什么 + 放宽后最接近什么」，而不是空数组。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentRetrievalServiceTest {

    @Mock
    private TableMetadataMapper tableMetadataMapper;

    @Mock
    private ColumnMetadataMapper columnMetadataMapper;

    @Mock
    private IndicatorMapper indicatorMapper;

    @Mock
    private AssetPermissionSupport permissionSupport;

    @Mock
    private DataAccessPermissionService permissionService;

    @Mock
    private RetrievalTermService retrievalTermService;

    @InjectMocks
    private AgentRetrievalService service;

    private static TableMetadata table(long id, String database, String tableName, String comment) {
        TableMetadata table = new TableMetadata();
        table.setId(id);
        table.setDatasourceId(7L);
        table.setDatabaseName(database);
        table.setTableName(tableName);
        table.setTableComment(comment);
        return table;
    }

    private void stubTables(List<TableMetadata> tables) {
        when(tableMetadataMapper.selectList(any())).thenReturn(tables);
        when(permissionSupport.filterSelectable(anyCollection())).thenAnswer(invocation -> {
            Collection<TableMetadata> arg = invocation.getArgument(0);
            return arg == null ? List.of() : List.copyOf(arg);
        });
        when(permissionSupport.datasourceName(any())).thenReturn("hive");
    }

    private static AgentSearchRequest request(List<String> keywords, AgentFilters filters, Integer limit) {
        AgentSearchRequest request = new AgentSearchRequest();
        request.setIntent("单元测试");
        request.setKeywords(keywords);
        request.setFilters(filters);
        request.setLimit(limit);
        return request;
    }

    @Test
    @DisplayName("环境过滤：只保留所选环境的库，三套同名表不再混排")
    void envFilterKeepsSelectedEnvOnly() {
        stubTables(List.of(
                table(1L, "dwd_trade_prod", "dwd_trade_order_d", "订单明细"),
                table(2L, "dwd_trade_gray", "dwd_trade_order_d", "订单明细"),
                table(3L, "dwd_trade_test", "dwd_trade_order_d", "订单明细"),
                table(4L, "dwd_trade", "dwd_trade_order_d", "订单明细")));

        AgentFilters filters = new AgentFilters();
        filters.setEnv("prod");
        AgentSearchResponse response = service.search(request(List.of("订单"), filters, 10));

        assertEquals(1, response.getTotal());
        assertEquals("dwd_trade_prod", response.getItems().get(0).getDatabase());
        assertEquals("prod", response.getItems().get(0).getEnv());
    }

    @Test
    @DisplayName("分层 + 业务域过滤：显式约束不满足的对象直接剔除")
    void layerAndDomainFilterApply() {
        stubTables(List.of(
                table(1L, "dwd_trade_prod", "dwd_trade_order_d", "订单明细"),
                table(2L, "ods_trade_prod", "ods_trade_order_d", "订单明细"),
                table(3L, "dwd_user_prod", "dwd_user_order_d", "用户订单")));

        AgentFilters filters = new AgentFilters();
        filters.setLayers(List.of("dwd"));
        filters.setDomains(List.of("trade"));
        AgentSearchResponse response = service.search(request(List.of("订单"), filters, 10));

        assertEquals(1, response.getTotal());
        assertEquals("dwd_trade_order_d", response.getItems().get(0).getName());
        assertEquals("dwd", response.getItems().get(0).getLayerCode());
        assertEquals("trade", response.getItems().get(0).getDomainCode());
    }

    @Test
    @DisplayName("打分：表名精确命中优先于注释命中")
    void nameExactMatchOutranksCommentMatch() {
        stubTables(List.of(
                table(1L, "dwd_trade_prod", "dwd_trade_order_detail_d", "订单明细宽表"),
                table(2L, "dwd_trade_prod", "order", "订单主表")));

        AgentSearchResponse response = service.search(request(List.of("order"), null, 10));

        assertEquals(2, response.getTotal());
        assertEquals("order", response.getItems().get(0).getName());
        assertEquals("name", response.getItems().get(0).getMatchedBy());
        assertEquals(1.0, response.getItems().get(0).getScore(), 0.001);
    }

    @Test
    @DisplayName("库名命中：按库检索时返回该库下的表，匹配依据标记为 database")
    void databaseNameKeywordFindsTablesInThatDatabase() {
        stubTables(List.of(
                table(1L, "test_db", "olist_geolocation_dataset", "地理坐标"),
                table(2L, "test_db", "olist_orders_dataset", "订单"),
                table(3L, "dwd_trade_prod", "dwd_trade_order_d", "订单明细")));

        AgentSearchResponse response = service.search(request(List.of("test_db"), null, 30));

        assertEquals(2, response.getTotal());
        assertTrue(response.getItems().stream().allMatch(item -> "test_db".equals(item.getDatabase())));
        assertTrue(response.getItems().stream().allMatch(item -> "database".equals(item.getMatchedBy())));
    }

    @Test
    @DisplayName("权限 fail-close：不可见的表不进 items，也不进 nearest")
    void permissionFiltersBothItemsAndNearest() {
        TableMetadata visible = table(1L, "dwd_trade_prod", "dwd_trade_order_d", "订单明细");
        TableMetadata hidden = table(2L, "dwd_trade_test", "dwd_trade_secret_d", "订单密表");
        when(tableMetadataMapper.selectList(any())).thenReturn(List.of(visible, hidden));
        when(permissionSupport.filterSelectable(anyCollection()))
                .thenAnswer(invocation -> {
                    Collection<TableMetadata> arg = invocation.getArgument(0);
                    return arg == null ? List.of() : arg.stream().filter(t -> t.getId() == 1L).toList();
                });
        when(permissionSupport.datasourceName(any())).thenReturn("hive");

        AgentFilters prodOnly = new AgentFilters();
        prodOnly.setEnv("prod");
        AgentSearchResponse hit = service.search(request(List.of("订单"), prodOnly, 10));
        assertEquals(1, hit.getTotal());

        AgentFilters testOnly = new AgentFilters();
        testOnly.setEnv("test");
        AgentSearchResponse miss = service.search(request(List.of("订单"), testOnly, 10));

        assertEquals(0, miss.getTotal());
        assertNotNull(miss.getNotFound());
        assertTrue(miss.getNotFound().getNearest().stream().noneMatch(item -> "dwd_trade_secret_d".equals(item.getName())),
                "隐藏表不得借由 nearest 泄露");
    }

    @Test
    @DisplayName("未命中：放宽约束给出近似候选，并明确试过哪些词")
    void notFoundRelaxesFiltersAndReportsTriedKeywords() {
        stubTables(List.of(table(3L, "dwd_trade_test", "dwd_trade_order_d", "订单明细")));

        AgentFilters filters = new AgentFilters();
        filters.setEnv("prod");
        filters.setLayers(List.of("ads"));
        AgentSearchResponse response = service.search(request(List.of("订单明细", "order_detail"), filters, 10));

        assertEquals(0, response.getTotal());
        assertFalse(response.isTruncated());
        NotFoundHintAssert.assertHasNearest(response);
        assertEquals(List.of("订单明细", "order_detail"), response.getNotFound().getTried());
        assertTrue(response.getNotFound().getSuggest().contains("放宽"));
    }

    @Test
    @DisplayName("字段命中：回到所属表并带上表名，模型可直接取详情")
    void columnHitResolvesToParentTable() {
        TableMetadata parent = table(1L, "dwd_trade_prod", "dwd_trade_order_d", "订单明细");
        ColumnMetadata column = new ColumnMetadata();
        column.setId(11L);
        column.setTableMetadataId(1L);
        column.setColumnName("order_id");
        column.setColumnComment("订单号");

        when(tableMetadataMapper.selectBatchIds(anyCollection())).thenReturn(List.of(parent));
        when(permissionSupport.filterSelectable(anyCollection())).thenAnswer(invocation -> {
            Collection<TableMetadata> arg = invocation.getArgument(0);
            return arg == null ? List.of() : List.copyOf(arg);
        });
        when(columnMetadataMapper.selectList(any())).thenReturn(List.of(column));
        when(permissionSupport.datasourceName(any())).thenReturn("hive");

        AgentSearchRequest request = request(List.of("order_id"), null, 10);
        request.setTypes(List.of("column"));
        AgentSearchResponse response = service.search(request);

        assertEquals(1, response.getTotal());
        AgentSearchItem item = response.getItems().get(0);
        assertEquals("column", item.getKind());
        assertEquals(1L, item.getId());
        assertEquals("order_id", item.getName());
        assertEquals("dwd_trade_order_d", item.getTable());
        assertTrue(item.getEvidence().contains("dwd_trade_order_d"));
    }

    @Test
    @DisplayName("截断：命中数超过 limit 时 total 仍是全量、truncated 为 true")
    void truncationKeepsTotalAndFlags() {
        stubTables(List.of(
                table(1L, "dwd_trade_prod", "dwd_trade_order_d", "订单明细"),
                table(2L, "dwd_trade_prod", "dwd_trade_order_item_d", "订单明细项"),
                table(3L, "dwd_trade_prod", "dwd_trade_order_pay_d", "订单支付")));

        AgentSearchResponse response = service.search(request(List.of("订单"), null, 2));

        assertEquals(3, response.getTotal());
        assertEquals(2, response.getItems().size());
        assertTrue(response.isTruncated());
    }

    @Test
    @DisplayName("术语扩展：业务口语经术语表扩展后能命中英文表名，且扩展词单独回显")
    void termExpansionWidensRecall() {
        stubTables(List.of(table(1L, "dwd_trade_prod", "dwd_trade_order_detail_d", "订单明细宽表")));
        when(retrievalTermService.expand(anyList())).thenReturn(List.of("order_detail"));

        AgentSearchResponse response = service.search(request(List.of("订单流水"), null, 10));

        assertEquals(1, response.getTotal());
        assertTrue(response.getExpandedKeywords().contains("order_detail"));
        assertTrue(response.getItems().get(0).getMatchedKeywords().contains("order_detail"),
                "命中依据应能看到扩展词，否则模型无法解释为什么这张表被召回");
    }

    @Test
    @DisplayName("缺关键词：直接返回可执行提示，不打库")
    void blankKeywordsReturnsActionableHint() {
        AgentSearchResponse response = service.search(request(List.of("  "), null, 10));

        assertEquals(0, response.getTotal());
        assertNotNull(response.getNotFound());
        assertTrue(response.getNotFound().getSuggest().contains("keywords"));
    }

    /** 断言辅助：避免每个用例都写一遍非空判断 */
    private static final class NotFoundHintAssert {
        private static void assertHasNearest(AgentSearchResponse response) {
            assertNotNull(response.getNotFound(), "未命中时必须有 notFound 提示");
            assertFalse(response.getNotFound().getNearest().isEmpty(), "放宽约束后应有近似候选");
            assertEquals("dwd_trade_order_d", response.getNotFound().getNearest().get(0).getName());
        }
    }
}
