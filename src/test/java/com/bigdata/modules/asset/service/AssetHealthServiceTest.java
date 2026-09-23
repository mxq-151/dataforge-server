package com.bigdata.modules.asset.service;

import com.bigdata.modules.metadata.entity.TableMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资产治理项判定测试。
 *
 * <p>治理清单的价值在于「每一项都能追到具体对象」，因此判定必须精确：
 * 多报一条会让使用者去查一个不存在的问题（并从此不再信任这份清单），
 * 少报一条则等于把问题藏起来。这里逐项钉死。</p>
 *
 * <p>只测 {@code issuesFor} 这个纯判定函数：汇总入口需要数据库，属集成测试范畴。</p>
 */
@DisplayName("资产治理项判定")
class AssetHealthServiceTest {

    private final AssetHealthService service = new AssetHealthService();

    /** 一张「各项都合规」的表：命名规范、有负责人/注释/文档/标签/规则，且近期有访问 */
    private TableMetadata healthyTable() {
        TableMetadata table = new TableMetadata();
        table.setId(1L);
        table.setDatasourceId(10L);
        table.setDatabaseName("dw");
        table.setTableName("dwd_order_detail_d");
        table.setBusinessOwner("张三");
        table.setTableComment("订单明细表");
        table.setLastAccessTime(new Date());
        return table;
    }

    /** 一个「各项证据都齐备」的判定上下文 */
    private AssetHealthService.Context contextWith(Long tableId, boolean hasDoc, boolean hasTag, int ruleCount) {
        return new AssetHealthService.Context(
                Map.of(),
                hasDoc ? Set.of(tableId) : Set.of(),
                hasTag ? Set.of(tableId) : Set.of(),
                ruleCount > 0 ? Map.of(tableId, ruleCount) : Map.of());
    }

    @Test
    @DisplayName("合规表不产生任何治理项（避免清单里出现噪声）")
    void healthyTableHasNoIssues() {
        List<AssetHealthService.Issue> issues =
                service.issuesFor(healthyTable(), contextWith(1L, true, true, 1));
        assertTrue(issues.isEmpty(), "合规表不应产生治理项，实际: " + issues);
    }

    @Test
    @DisplayName("命名不合规被识别（表名无法按规范解析）")
    void detectsNamingUnconformed() {
        TableMetadata table = healthyTable();
        table.setTableName("stg_order_detail_d");
        List<AssetHealthService.Issue> issues = service.issuesFor(table, contextWith(1L, true, true, 1));
        assertEquals(List.of(AssetHealthService.Issue.NAMING_UNCONFORMED), issues);
    }

    @Test
    @DisplayName("未标注业务负责人 / 缺表注释分别被识别")
    void detectsAnnotationGaps() {
        TableMetadata table = healthyTable();
        table.setBusinessOwner("  ");
        table.setTableComment(null);
        List<AssetHealthService.Issue> issues = service.issuesFor(table, contextWith(1L, true, true, 1));
        assertTrue(issues.contains(AssetHealthService.Issue.NO_BUSINESS_OWNER));
        assertTrue(issues.contains(AssetHealthService.Issue.NO_TABLE_COMMENT));
        assertFalse(issues.contains(AssetHealthService.Issue.NO_DOC));
    }

    @Test
    @DisplayName("存在无注释字段被识别（依赖上下文里的字段缺口统计）")
    void detectsColumnCommentGaps() {
        AssetHealthService.Context context = new AssetHealthService.Context(
                Map.of(1L, 3), Set.of(1L), Set.of(1L), Map.of(1L, 1));
        List<AssetHealthService.Issue> issues = service.issuesFor(healthyTable(), context);
        assertEquals(List.of(AssetHealthService.Issue.NO_COLUMN_COMMENT), issues);

        // 缺口数为 0 时不应报（否则「有缺口」和「无缺口」都会被判为问题）
        AssetHealthService.Context noGap = new AssetHealthService.Context(
                Map.of(1L, 0), Set.of(1L), Set.of(1L), Map.of(1L, 1));
        assertTrue(service.issuesFor(healthyTable(), noGap).isEmpty());
    }

    @Test
    @DisplayName("缺使用说明 / 缺标签 / 未配质量规则分别被识别")
    void detectsDocTagAndRuleGaps() {
        List<AssetHealthService.Issue> issues = service.issuesFor(healthyTable(), contextWith(1L, false, false, 0));
        assertTrue(issues.contains(AssetHealthService.Issue.NO_DOC));
        assertTrue(issues.contains(AssetHealthService.Issue.NO_TAG));
        assertTrue(issues.contains(AssetHealthService.Issue.NO_QUALITY_RULE));
    }

    @Test
    @DisplayName("近 30 日无访问被识别；从未访问过也算（null 属于最坏情况）")
    void detectsIdleAssets() {
        TableMetadata stale = healthyTable();
        stale.setLastAccessTime(new Date(System.currentTimeMillis() - 45L * 24 * 60 * 60 * 1000));
        assertTrue(service.issuesFor(stale, contextWith(1L, true, true, 1))
                .contains(AssetHealthService.Issue.NO_ACCESS_30D));

        TableMetadata never = healthyTable();
        never.setLastAccessTime(null);
        assertTrue(service.issuesFor(never, contextWith(1L, true, true, 1))
                .contains(AssetHealthService.Issue.NO_ACCESS_30D));

        // 29 天前访问过：仍在窗口内，不算低效资产
        TableMetadata recent = healthyTable();
        recent.setLastAccessTime(new Date(System.currentTimeMillis() - 29L * 24 * 60 * 60 * 1000));
        assertFalse(service.issuesFor(recent, contextWith(1L, true, true, 1))
                .contains(AssetHealthService.Issue.NO_ACCESS_30D));
    }

    @Test
    @DisplayName("多项问题同时命中时全部返回，不做「取最高优先级」的合并")
    void aggregatesAllIssues() {
        TableMetadata table = healthyTable();
        table.setTableName("bad_name");
        table.setBusinessOwner(null);
        table.setTableComment(null);
        table.setLastAccessTime(null);

        List<AssetHealthService.Issue> issues = service.issuesFor(table, contextWith(1L, false, false, 0));
        assertEquals(7, issues.size(), "7 项治理项应全部命中，实际: " + issues);
    }

    @Test
    @DisplayName("null 表名/表不抛异常：治理判定不能因为一条脏数据中断整份清单")
    void handlesNullSafely() {
        assertTrue(service.issuesFor(null, contextWith(1L, true, true, 1)).isEmpty());

        TableMetadata noName = healthyTable();
        noName.setTableName(null);
        assertTrue(service.issuesFor(noName, contextWith(1L, true, true, 1))
                .contains(AssetHealthService.Issue.NAMING_UNCONFORMED));
    }

    @Test
    @DisplayName("治理项编码唯一且非空（前端按编码做图标与文案映射）")
    void issueCodesAreUnique() {
        Set<String> codes = new java.util.HashSet<>();
        for (AssetHealthService.Issue issue : AssetHealthService.Issue.values()) {
            assertTrue(codes.add(issue.getCode()), "治理项编码重复: " + issue.getCode());
            assertFalse(issue.getName().isBlank());
            assertFalse(issue.getReason().isBlank(), "治理项必须说明「为什么它算问题」");
        }
    }
}
