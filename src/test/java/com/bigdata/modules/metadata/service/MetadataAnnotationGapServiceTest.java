package com.bigdata.modules.metadata.service;

import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.devops.mapper.DatasourceConfigMapper;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.ColumnMetadataMapper;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import com.bigdata.modules.metadata.vo.AnnotationGapSummaryVO;
import com.bigdata.modules.metadata.vo.AnnotationGapTableVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 「未标注表 / 字段」清单验证（风险项 R1）。
 * <p>
 * 该清单的意义是把「描述缺失」从不可见变成可见：只列出存在缺失的表，
 * 表注释缺失与字段注释缺失分别计数，并按调用者的表权限过滤。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MetadataAnnotationGapServiceTest {

    @Mock
    private TableMetadataMapper tableMetadataMapper;

    @Mock
    private ColumnMetadataMapper columnMetadataMapper;

    @Mock
    private DatasourceConfigMapper datasourceConfigMapper;

    @Mock
    private DataAccessPermissionService permissionService;

    @InjectMocks
    private MetadataAnnotationGapService service;

    @BeforeEach
    void setUp() {
        DatasourceConfig config = new DatasourceConfig();
        config.setDatasourceName("pg-test");
        when(datasourceConfigMapper.selectById(any())).thenReturn(config);
        when(permissionService.isAdmin()).thenReturn(true);
    }

    private TableMetadata table(Long id, String name, String comment) {
        TableMetadata table = new TableMetadata();
        table.setId(id);
        table.setDatasourceId(1L);
        table.setStorageEngine(DatasourceType.POSTGRESQL);
        table.setDatabaseName("testdb");
        table.setTableName(name);
        table.setTableComment(comment);
        return table;
    }

    private ColumnMetadata column(Long tableId, String name, String comment, int position) {
        ColumnMetadata column = new ColumnMetadata();
        column.setTableMetadataId(tableId);
        column.setDatabaseName("testdb");
        column.setTableName("t");
        column.setColumnName(name);
        column.setColumnType("text");
        column.setColumnComment(comment);
        column.setOrdinalPosition(position);
        return column;
    }

    @Test
    void listsTablesWithMissingTableCommentOrColumnComment() {
        when(tableMetadataMapper.selectList(any())).thenReturn(List.of(
                table(1L, "ad_events", null),            // 表注释缺失
                table(2L, "orders", "订单"),              // 表注释完整，但字段有缺失
                table(3L, "clean_t", "完全标注")          // 无缺失，不应出现在清单里
        ));
        when(columnMetadataMapper.selectList(any())).thenReturn(Arrays.asList(
                column(1L, "id", "主键", 1),
                column(1L, "uid", null, 2),
                column(2L, "id", "主键", 1),
                column(2L, "amount", null, 2),
                column(3L, "id", "主键", 1)
        ));

        AnnotationGapSummaryVO summary = service.query(1L, null, 100, null, null, null);

        assertEquals(3, summary.getTableScanned());
        assertEquals(1, summary.getUnannotatedTableCount(), "只有 ad_events 的表注释缺失");
        assertEquals(2, summary.getUnannotatedColumnCount(), "ad_events.uid 与 orders.amount");

        List<AnnotationGapTableVO> tables = summary.getTables();
        assertEquals(2, tables.size(), "只应列出存在缺失的表");

        AnnotationGapTableVO adEvents = tables.get(0);
        assertEquals("ad_events", adEvents.getTableName());
        assertTrue(adEvents.getTableCommentMissing());
        assertEquals(2, adEvents.getColumnCount());
        assertEquals(1, adEvents.getUnannotatedColumnCount());
        assertEquals("uid", adEvents.getUnannotatedColumns().get(0).getColumnName());
        assertEquals("pg-test", adEvents.getDatasourceName());

        AnnotationGapTableVO orders = tables.get(1);
        assertEquals("orders", orders.getTableName());
        assertFalse(orders.getTableCommentMissing());
        assertEquals(1, orders.getUnannotatedColumnCount());
    }

    @Test
    void marksTruncatedWhenMoreTablesThanLimit() {
        when(tableMetadataMapper.selectList(any())).thenReturn(List.of(
                table(1L, "t1", null),
                table(2L, "t2", null),
                table(3L, "t3", null)
        ));
        when(columnMetadataMapper.selectList(any())).thenReturn(List.of());

        AnnotationGapSummaryVO summary = service.query(1L, null, 2, null, null, null);

        assertTrue(summary.getTruncated(), "结果被 limit 截断时 truncated 应为 true");
        assertEquals(2, summary.getTableScanned());
        assertEquals(2, summary.getTables().size());
    }

    @Test
    void treatsBlankCommentAsMissing() {
        when(tableMetadataMapper.selectList(any())).thenReturn(List.of(table(1L, "t1", "   ")));
        when(columnMetadataMapper.selectList(any())).thenReturn(List.of(
                column(1L, "a", "", 1),
                column(1L, "b", "有注释", 2)));

        AnnotationGapSummaryVO summary = service.query(1L, null, 100, null, null, null);

        assertEquals(1, summary.getUnannotatedTableCount(), "空白表注释应视为缺失");
        assertEquals(1, summary.getUnannotatedColumnCount(), "空白字段注释应视为缺失");
    }

    @Test
    void appliesTablePermissionFilterForNonAdmin() {
        when(permissionService.isAdmin()).thenReturn(false);
        when(permissionService.getAccessibleDatabaseNames(any())).thenReturn(Set.of("testdb"));
        when(permissionService.getAccessibleTableNames(any(), eq("testdb"))).thenReturn(Set.of("ad_events"));
        when(tableMetadataMapper.selectList(any())).thenReturn(List.of(
                table(1L, "ad_events", null),
                table(2L, "secret_t", null)
        ));
        when(columnMetadataMapper.selectList(any())).thenReturn(List.of());

        AnnotationGapSummaryVO summary = service.query(1L, null, 100, null, null, null);

        assertEquals(1, summary.getTables().size(), "无权限的表不应出现在清单里");
        assertEquals("ad_events", summary.getTables().get(0).getTableName());
    }

    @Test
    void doesNotQueryColumnsWhenNoTableMatched() {
        when(tableMetadataMapper.selectList(any())).thenReturn(List.of());

        AnnotationGapSummaryVO summary = service.query(1L, "testdb", 100, null, null, null);

        assertEquals(0, summary.getTableScanned());
        assertTrue(summary.getTables().isEmpty());
        assertFalse(summary.getTruncated());
    }

    @Test
    void narrowsByLayerAndDomainCode() {
        when(tableMetadataMapper.selectList(any())).thenReturn(List.of(
                table(1L, "ods_olist_order_d", null),
                table(2L, "dws_olist_sales_d", null),
                table(3L, "dwd_order_detail_d", null),
                table(4L, "sales_summary", null)
        ));
        when(columnMetadataMapper.selectList(any())).thenReturn(List.of());

        AnnotationGapSummaryVO odsOnly = service.query(1L, null, 100, null, null, "ods");
        assertEquals(1, odsOnly.getTableScanned());
        assertEquals("ods_olist_order_d", odsOnly.getTables().get(0).getTableName());

        AnnotationGapSummaryVO olistDomain = service.query(1L, null, 100, null, "olist", null);
        assertEquals(2, olistDomain.getTableScanned());

        AnnotationGapSummaryVO unclassified = service.query(1L, null, 100, null, null, "__unclassified");
        assertEquals(1, unclassified.getTableScanned());
        assertEquals("sales_summary", unclassified.getTables().get(0).getTableName());
    }
}
