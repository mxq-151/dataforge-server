package com.bigdata.modules.retrieval.evaluation;

import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.modules.asset.service.AssetPermissionSupport;
import com.bigdata.modules.knowledge.entity.KnowledgeDocument;
import com.bigdata.modules.knowledge.enums.DocType;
import com.bigdata.modules.knowledge.mapper.KnowledgeDocumentMapper;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.ColumnMetadataMapper;
import com.bigdata.modules.metadata.mapper.IndicatorMapper;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import com.bigdata.modules.retrieval.evaluation.RetrievalGoldenSet.GoldenSet;
import com.bigdata.modules.retrieval.evaluation.RetrievalGoldenSet.KnowledgeCase;
import com.bigdata.modules.retrieval.evaluation.RetrievalGoldenSet.Metrics;
import com.bigdata.modules.retrieval.evaluation.RetrievalGoldenSet.TableCase;
import com.bigdata.modules.retrieval.service.AgentRetrievalService;
import com.bigdata.modules.retrieval.service.KnowledgeSnippetService;
import com.bigdata.modules.retrieval.service.RetrievalTermService;
import com.bigdata.modules.retrieval.vo.AgentSearchRequest;
import com.bigdata.modules.retrieval.vo.AgentSearchResponse;
import com.bigdata.modules.retrieval.vo.KnowledgeSearchRequest;
import com.bigdata.modules.retrieval.vo.KnowledgeSearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * 检索回归基线（离线 fixture）。
 *
 * <h3>它保证什么</h3>
 * <p>用固定的一批库表与知识文档，对 golden-queries.json 里的每条用例跑真实检索，
 * 输出 Precision / Recall / MRR / 约束违例。任何一次打分权重、过滤口径、切词规则的
 * 调整，只要让这些指标下降，测试就会失败。</p>
 *
 * <h3>它不保证什么</h3>
 * <p>fixture 是理想数据：注释齐全、命名规范。它验证的是「逻辑对不对」，
 * 不是「线上脏数据下好不好用」。后者由 {@link RetrievalLiveEvalTest} 在联调环境上度量。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RetrievalGoldenSetTest {

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

    @Mock
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    @InjectMocks
    private AgentRetrievalService retrievalService;

    @InjectMocks
    private KnowledgeSnippetService knowledgeService;

    /** 20 张表覆盖 5 个分层、4 个业务域、3 套环境，足以暴露过滤与排序问题 */
    private static final List<TableMetadata> TABLES = List.of(
            table(1L, "dwd_trade_prod", "dwd_trade_order_detail_d", "订单明细"),
            table(2L, "dwd_trade_prod", "dwd_trade_order_item_detail_d", "订单明细项"),
            table(3L, "dwd_trade_prod", "dwd_trade_order_pay_d", "订单支付流水"),
            table(4L, "dwd_trade_prod", "dwd_trade_refund_detail_d", "订单退款明细"),
            table(5L, "dws_trade_prod", "dws_trade_order_summary_d", "订单汇总"),
            table(6L, "dws_trade_prod", "dws_trade_gmv_summary_d", "成交额汇总"),
            table(7L, "ads_trade_prod", "ads_trade_order_report_d", "订单报表"),
            table(8L, "ods_trade_prod", "ods_trade_order_incr_d", "订单增量"),
            table(9L, "ods_trade_prod", "ods_trade_refund_incr_d", "退款增量"),
            table(10L, "dim_trade_prod", "dim_trade_channel_df", "渠道维度"),
            table(11L, "dwd_user_prod", "dwd_user_profile_d", "用户画像"),
            table(12L, "dwd_user_prod", "dwd_user_login_log_d", "用户登录日志"),
            table(13L, "dws_user_prod", "dws_user_active_summary_d", "用户活跃汇总"),
            table(14L, "dwd_product_prod", "dwd_product_sku_detail_d", "商品SKU明细"),
            table(15L, "dws_product_prod", "dws_product_sales_summary_d", "商品销售汇总"),
            table(16L, "dwd_trade_gray", "dwd_trade_order_detail_d", "订单明细"),
            table(17L, "dwd_trade_test", "dwd_trade_order_detail_d", "订单明细"),
            table(18L, "tmp_trade_prod", "tmp_trade_order_backfill_d", "订单回补临时表"),
            table(19L, "dqc_trade_prod", "dqc_trade_order_check_d", "订单质检"),
            table(20L, "dwd_marketing_prod", "dwd_marketing_coupon_detail_d", "优惠券明细"));

    private static final List<KnowledgeDocument> DOCUMENTS = List.of(
            doc(1L, "订单主题域口径说明", DocType.BUSINESS_KNOWLEDGE, """
                    # 订单主题域

                    本文说明订单相关口径。

                    ## 口径

                    订单金额 = 支付金额 - 退款金额，按自然日统计。

                    ## 时效

                    T+1 产出，每日 06:00 前完成。
                    """),
            doc(2L, "集群资源与队列说明", DocType.CLUSTER_OPS, """
                    # 集群概览

                    生产集群 20 节点，测试集群 5 节点。

                    ## YARN 队列规划

                    default 队列占 60%，etl 队列占 30%，ad hoc 队列占 10%。

                    ## 常见故障

                    队列打满时先看 pending 容器数。
                    """),
            doc(3L, "开发规范-代码头", DocType.DEV_STANDARD, """
                    # 代码头字段

                    必填字段：@备注、@引擎、@数据源。

                    ## @引擎 取值

                    spark / flink / flink_stream，只能取这三个值。

                    ## @依赖 写法

                    {调度项目}.{前置任务名称}，多个用英文逗号分隔。
                    """),
            doc(4L, "业务术语表", DocType.BUSINESS_KNOWLEDGE, """
                    # 术语表

                    | 术语 | 别名 | 英文名 |
                    | --- | --- | --- |
                    | 订单明细 | 订单流水 | order_detail |
                    | 成交额 | GMV | gmv |
                    """),
            doc(5L, "指标口径总则", DocType.BUSINESS_KNOWLEDGE, """
                    # 指标命名

                    指标编码 = 域_主题_度量。

                    ## 口径一致性

                    同一指标在全链路只允许一个口径，上下游不得各算一套。

                    ## 更新频率

                    指标口径变更需同步知识库与本表。
                    """));

    @BeforeEach
    void setUp() {
        when(tableMetadataMapper.selectList(any())).thenReturn(TABLES);
        when(permissionSupport.filterSelectable(anyCollection())).thenAnswer(invocation -> {
            Collection<TableMetadata> argument = invocation.getArgument(0);
            return argument == null ? List.of() : List.copyOf(argument);
        });
        when(permissionSupport.datasourceName(any())).thenReturn("hive");
        when(retrievalTermService.expand(anyList())).thenReturn(List.of());
        when(knowledgeDocumentMapper.selectList(any())).thenReturn(DOCUMENTS);
    }

    @Test
    @DisplayName("基线：50+ 条用例的 top1 / recall@5 / MRR / 约束违例达标")
    void goldenSetMeetsThresholds() {
        GoldenSet set = RetrievalGoldenSet.load();

        // 用例数下限：防止有人用「删用例」的方式让指标变绿
        assertTrue(set.tableCases().size() >= 35, "库表用例不应少于 35 条，实际=" + set.tableCases().size());
        assertTrue(set.emptyCases().size() >= 8, "未命中用例不应少于 8 条，实际=" + set.emptyCases().size());
        assertTrue(set.knowledgeCases().size() >= 8, "知识库用例不应少于 8 条，实际=" + set.knowledgeCases().size());
        assertTrue(RetrievalGoldenSet.totalCases(set) >= 50);

        Metrics tableMetrics = RetrievalGoldenSet.evaluate(set, this::runTable);
        Metrics metrics = RetrievalGoldenSet.evaluateKnowledge(tableMetrics, set, this::runKnowledge);

        System.out.println(metrics.report());

        assertEquals(0, metrics.violations(), "约束违例必须为 0：说明服务端过滤失效，模型无法补救");
        assertEquals(1.0, metrics.emptyAccuracy(), 0.0001, "该为空的用例必须返回空 + notFound");
        assertTrue(metrics.top1() >= 0.9, "top1 低于 0.9，实际=" + metrics.top1());
        assertTrue(metrics.recallAt5() >= 0.95, "recall@5 低于 0.95，实际=" + metrics.recallAt5());
        assertTrue(metrics.mrr() >= 0.9, "MRR 低于 0.9，实际=" + metrics.mrr());
        assertTrue(metrics.knowledgeTitleAt3() >= 0.85, "文档命中@3 低于 0.85，实际=" + metrics.knowledgeTitleAt3());
        assertTrue(metrics.knowledgeSectionAt1() >= 0.7, "章节命中@1 低于 0.7，实际=" + metrics.knowledgeSectionAt1());
    }

    private AgentSearchResponse runTable(TableCase testCase) {
        AgentSearchRequest request = new AgentSearchRequest();
        request.setIntent("golden:" + testCase.id());
        request.setKeywords(testCase.keywords());
        request.setFilters(testCase.filters());
        request.setLimit(10);
        return retrievalService.search(request);
    }

    private KnowledgeSearchResponse runKnowledge(KnowledgeCase testCase) {
        KnowledgeSearchRequest request = new KnowledgeSearchRequest();
        request.setIntent("golden:" + testCase.id());
        request.setQuery(testCase.query());
        request.setDocTypes(testCase.docTypes());
        request.setTopK(5);
        return knowledgeService.search(request);
    }

    private static TableMetadata table(long id, String database, String tableName, String comment) {
        TableMetadata table = new TableMetadata();
        table.setId(id);
        table.setDatasourceId(7L);
        table.setDatabaseName(database);
        table.setTableName(tableName);
        table.setTableComment(comment);
        return table;
    }

    private static KnowledgeDocument doc(long id, String title, DocType type, String content) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(id);
        document.setTitle(title);
        document.setDocType(type);
        document.setContent(content);
        document.setStatus(1);
        document.setUpdateTime(LocalDateTime.of(2026, 10, 1, 9, 0));
        return document;
    }
}
