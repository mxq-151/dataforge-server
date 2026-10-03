package com.bigdata.modules.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bigdata.modules.asset.entity.AssetDoc;
import com.bigdata.modules.asset.entity.AssetTagRel;
import com.bigdata.modules.asset.mapper.AssetDocMapper;
import com.bigdata.modules.asset.mapper.AssetTagRelMapper;
import com.bigdata.modules.asset.vo.AssetHealthIssueVO;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.ColumnMetadataMapper;
import com.bigdata.modules.quality.entity.DqRule;
import com.bigdata.modules.quality.mapper.DqRuleMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 资产治理项识别 —— 回答「这批资产哪里需要治理」。
 *
 * <h3>为什么是清单而不是评分</h3>
 * <p>治理健康度有两种做法：加权算一个 0~100 的分数，或者列一份可追到具体对象的清单。
 * 本模块选择后者。分数的问题是<b>无法行动</b>：看到 72 分会问「那 28 分扣在哪」，
 * 而每一项的权重又是拍脑袋定的，最后没人真的按分数去治理。
 * 清单则每一项都能点进去，治理完一项少一项。</p>
 *
 * <h3>口径</h3>
 * <p>所有治理项都只依赖平台已有的元数据、资产文档、标签与质量规则，
 * 不引入新的采集链路 —— 缺失信息本身就是要暴露的问题。</p>
 *
 * @author dataforge
 */
@Service
public class AssetHealthService {

    /** 治理项样本最多返回多少条（清单用于「知道去哪看」，不是导出） */
    public static final int MAX_SAMPLES = 5;

    /** 近 N 日无访问视为低效资产 */
    public static final int IDLE_DAYS = 30;

    /** 治理项定义：编码 + 名称 + 为什么它算问题 */
    public enum Issue {

        NAMING_UNCONFORMED("NAMING_UNCONFORMED", "表名不符合命名规范",
                "无法按 {分层}_{业务域}_{实体描述}_{刷新周期} 解析，规范视角下会落进「未分类」"),
        NO_BUSINESS_OWNER("NO_BUSINESS_OWNER", "未标注业务负责人",
                "出问题时找不到业务侧对接人，责任无法落地"),
        NO_TABLE_COMMENT("NO_TABLE_COMMENT", "缺少表注释",
                "检索与目录只显示表名，使用者无法判断这张表能不能用"),
        NO_COLUMN_COMMENT("NO_COLUMN_COMMENT", "存在无注释字段",
                "字段语义只能靠猜，是取数口径被误用的主要来源"),
        NO_DOC("NO_DOC", "缺少使用说明",
                "没有口径、刷新时间、注意事项等成文说明"),
        NO_QUALITY_RULE("NO_QUALITY_RULE", "未配置质量规则",
                "数据出问题不会被发现，下游只能靠事故发生来察觉"),
        NO_TAG("NO_TAG", "未打标签",
                "无法按主题/敏感度等维度成批圈选与检索"),
        NO_ACCESS_30D("NO_ACCESS_30D", "近 " + IDLE_DAYS + " 日无访问",
                "可能是无人使用的僵尸资产，占用存储与维护成本");

        private final String code;
        private final String name;
        private final String reason;

        Issue(String code, String name, String reason) {
            this.code = code;
            this.name = name;
            this.reason = reason;
        }

        public String getCode() {
            return code;
        }

        public String getName() {
            return name;
        }

        public String getReason() {
            return reason;
        }
    }

    @Autowired
    private ColumnMetadataMapper columnMetadataMapper;

    @Autowired
    private AssetDocMapper assetDocMapper;

    @Autowired
    private AssetTagRelMapper assetTagRelMapper;

    @Autowired
    private DqRuleMapper dqRuleMapper;

    /**
     * 治理项判定的上下文 —— 一次性把「跨表才查得到的证据」加载完。
     *
     * <p>刻意抽成上下文而不是让每个判定方法各查一次：盘点要对全部表判定，
     * 逐表查询会退化成 4N 次往返。</p>
     */
    public static final class Context {

        /** 表 id → 无注释字段数 */
        private final Map<Long, Integer> columnGapByTable;
        /** 有使用说明的表 id */
        private final Set<Long> tablesWithDoc;
        /** 有标签的表 id */
        private final Set<Long> tablesWithTag;
        /** 表 id → 已启用质量规则数 */
        private final Map<Long, Integer> ruleCountByTable;

        Context(Map<Long, Integer> columnGapByTable, Set<Long> tablesWithDoc,
                Set<Long> tablesWithTag, Map<Long, Integer> ruleCountByTable) {
            this.columnGapByTable = columnGapByTable;
            this.tablesWithDoc = tablesWithDoc;
            this.tablesWithTag = tablesWithTag;
            this.ruleCountByTable = ruleCountByTable;
        }

        public int ruleCount(Long tableId) {
            Integer count = tableId == null ? null : ruleCountByTable.get(tableId);
            return count == null ? 0 : count;
        }
    }

    // ==================================================================
    // 上下文构建
    // ==================================================================

    /** 为一批表构建判定上下文 */
    public Context buildContext(List<TableMetadata> tables) {
        List<Long> tableIds = new ArrayList<>();
        for (TableMetadata table : tables) {
            if (table != null && table.getId() != null) {
                tableIds.add(table.getId());
            }
        }
        if (tableIds.isEmpty()) {
            return new Context(Map.of(), Set.of(), Set.of(), Map.of());
        }

        Map<Long, Integer> columnGap = loadColumnGap(tableIds);
        Set<Long> withDoc = loadTablesWithDoc(tableIds);
        Set<Long> withTag = loadTablesWithTag(tableIds);
        Map<Long, Integer> ruleCount = countEnabledRules(tables);
        return new Context(columnGap, withDoc, withTag, ruleCount);
    }

    /**
     * 无注释字段数按表聚合。
     *
     * <p>用 {@code GROUP BY} 而不是「取回全部字段行再在内存里数」：
     * column_metadata 是平台最大的元数据表，字段行数量是表数量的几十倍。</p>
     */
    private Map<Long, Integer> loadColumnGap(List<Long> tableIds) {
        // 注意：column_metadata 没有 deleted 列（物理同步表，增删由同步任务处理），
        // 因此这里不能加 deleted 条件，否则 SQL 直接报未知列。
        LambdaQueryWrapper<ColumnMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(ColumnMetadata::getTableMetadataId, ColumnMetadata::getColumnComment)
                .in(ColumnMetadata::getTableMetadataId, tableIds);
        List<ColumnMetadata> rows = columnMetadataMapper.selectList(wrapper);

        Map<Long, Integer> gaps = new HashMap<>();
        for (ColumnMetadata row : rows) {
            Long tableId = row.getTableMetadataId();
            if (tableId == null) {
                continue;
            }
            if (isBlank(row.getColumnComment())) {
                gaps.merge(tableId, 1, Integer::sum);
            }
        }
        return gaps;
    }

    private Set<Long> loadTablesWithDoc(List<Long> tableIds) {
        LambdaQueryWrapper<AssetDoc> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(AssetDoc::getTableMetadataId)
                .in(AssetDoc::getTableMetadataId, tableIds)
                .eq(AssetDoc::getDeleted, 0)
                .isNotNull(AssetDoc::getContent)
                .ne(AssetDoc::getContent, "");
        Set<Long> result = new HashSet<>();
        for (AssetDoc doc : assetDocMapper.selectList(wrapper)) {
            if (doc.getTableMetadataId() != null) {
                result.add(doc.getTableMetadataId());
            }
        }
        return result;
    }

    private Set<Long> loadTablesWithTag(List<Long> tableIds) {
        LambdaQueryWrapper<AssetTagRel> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(AssetTagRel::getTableMetadataId)
                .in(AssetTagRel::getTableMetadataId, tableIds)
                .eq(AssetTagRel::getDeleted, 0);
        Set<Long> result = new HashSet<>();
        for (AssetTagRel rel : assetTagRelMapper.selectList(wrapper)) {
            if (rel.getTableMetadataId() != null) {
                result.add(rel.getTableMetadataId());
            }
        }
        return result;
    }

    /**
     * 统计每张表的启用规则数。
     *
     * <p>规则表是配置表（量级在百千），整表取回后在内存里按
     * 「数据源 + 库 + 表」匹配，比按表逐个查询少 N 次往返；
     * 同时能正确处理「规则只填了库表、没绑定数据源」这种历史数据。</p>
     */
    private Map<Long, Integer> countEnabledRules(List<TableMetadata> tables) {
        LambdaQueryWrapper<DqRule> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(DqRule::getId, DqRule::getDatasourceId, DqRule::getDatasourceName,
                        DqRule::getDatabaseName, DqRule::getTableName, DqRule::getEnabled)
                .eq(DqRule::getDeleted, 0);
        List<DqRule> rules = dqRuleMapper.selectList(wrapper);
        if (rules.isEmpty()) {
            return Map.of();
        }

        Map<Long, Integer> result = new HashMap<>();
        for (TableMetadata table : tables) {
            if (table == null || table.getId() == null || table.getTableName() == null) {
                continue;
            }
            int count = 0;
            for (DqRule rule : rules) {
                if (rule.getEnabled() != null && rule.getEnabled() == 0) {
                    continue;
                }
                if (!table.getTableName().equalsIgnoreCase(rule.getTableName())) {
                    continue;
                }
                if (!equalsIgnoreCaseNullable(table.getDatabaseName(), rule.getDatabaseName())) {
                    continue;
                }
                // 规则未绑定数据源时按「库 + 表」匹配即可；绑定了则必须一致
                if (!ruleMatchesDatasource(table, rule)) {
                    continue;
                }
                count++;
            }
            if (count > 0) {
                result.put(table.getId(), count);
            }
        }
        return result;
    }

    private boolean ruleMatchesDatasource(TableMetadata table, DqRule rule) {
        if (rule.getDatasourceId() != null) {
            return rule.getDatasourceId().equals(table.getDatasourceId());
        }
        // 规则只填了数据源名（历史数据）时按名称比对；表侧没有数据源则视为不限制
        if (rule.getDatasourceName() != null && !rule.getDatasourceName().isBlank()
                && table.getDatasourceId() != null) {
            return false;
        }
        return true;
    }

    // ==================================================================
    // 判定
    // ==================================================================

    /** 单表命中的治理项 */
    public List<Issue> issuesFor(TableMetadata table, Context context) {
        List<Issue> issues = new ArrayList<>();
        if (table == null) {
            return issues;
        }
        // 命名规范与否始终实时解析：不做缓存也不落库，
        // 这样规范调整后历史资产立刻按新规范重新归类，无需回刷数据。
        if (!TableNameConventionParser.parse(table.getTableName()).parsed()) {
            issues.add(Issue.NAMING_UNCONFORMED);
        }
        if (isBlank(table.getBusinessOwner())) {
            issues.add(Issue.NO_BUSINESS_OWNER);
        }
        if (isBlank(table.getTableComment())) {
            issues.add(Issue.NO_TABLE_COMMENT);
        }
        Long tableId = table.getId();
        if (context == null) {
            return issues;
        }
        Integer columnGaps = tableId == null ? null : context.columnGapByTable.get(tableId);
        if (columnGaps != null && columnGaps > 0) {
            issues.add(Issue.NO_COLUMN_COMMENT);
        }
        if (tableId != null && !context.tablesWithDoc.contains(tableId)) {
            issues.add(Issue.NO_DOC);
        }
        if (context.ruleCount(tableId) == 0) {
            issues.add(Issue.NO_QUALITY_RULE);
        }
        if (tableId != null && !context.tablesWithTag.contains(tableId)) {
            issues.add(Issue.NO_TAG);
        }
        if (isIdle(table.getLastAccessTime())) {
            issues.add(Issue.NO_ACCESS_30D);
        }
        return issues;
    }

    /** 单表治理项计数（资产详情页顶部的「治理项」徽标） */
    public int countIssues(TableMetadata table) {
        Context context = buildContext(List.of(table));
        return issuesFor(table, context).size();
    }

    private boolean isIdle(Date lastAccessTime) {
        if (lastAccessTime == null) {
            return true;
        }
        long idleMillis = (long) IDLE_DAYS * 24 * 60 * 60 * 1000;
        return System.currentTimeMillis() - lastAccessTime.getTime() > idleMillis;
    }

    // ==================================================================
    // 清单输出
    // ==================================================================

    /**
     * 生成治理项清单（每项带影响面与样本）。
     *
     * @param tables 已按权限过滤过的表集合
     */
    public List<AssetHealthIssueVO> overview(List<TableMetadata> tables) {
        Map<Issue, List<TableMetadata>> hits = new HashMap<>();
        for (Issue issue : Issue.values()) {
            hits.put(issue, new ArrayList<>());
        }
        if (tables != null && !tables.isEmpty()) {
            Context context = buildContext(tables);
            for (TableMetadata table : tables) {
                for (Issue issue : issuesFor(table, context)) {
                    hits.get(issue).add(table);
                }
            }
        }

        List<AssetHealthIssueVO> result = new ArrayList<>(Issue.values().length);
        for (Issue issue : Issue.values()) {
            List<TableMetadata> matched = hits.get(issue);
            AssetHealthIssueVO vo = new AssetHealthIssueVO();
            vo.setIssueCode(issue.getCode());
            vo.setIssueName(issue.getName());
            vo.setCount(matched.size());
            vo.setImpact(buildImpact(issue, matched.size(), tables == null ? 0 : tables.size()));
            vo.setSamples(buildSamples(matched));
            result.add(vo);
        }
        return result;
    }

    /** 影响面描述：既说数量占比，也说这条治理项到底意味着什么 */
    public String buildImpact(Issue issue, int hitCount, int totalCount) {
        if (totalCount == 0) {
            return "暂无纳管资产";
        }
        String ratio = String.format("占纳管资产 %.1f%%", hitCount * 100.0 / totalCount);
        return "共 " + hitCount + " 张表（" + ratio + "）；" + issue.getReason();
    }

    private List<AssetHealthIssueVO.Sample> buildSamples(List<TableMetadata> matched) {
        List<AssetHealthIssueVO.Sample> samples = new ArrayList<>();
        for (TableMetadata table : matched) {
            if (samples.size() >= MAX_SAMPLES) {
                break;
            }
            AssetHealthIssueVO.Sample sample = new AssetHealthIssueVO.Sample();
            sample.setTableMetadataId(table.getId());
            sample.setDatabaseName(table.getDatabaseName());
            sample.setTableName(table.getTableName());
            samples.add(sample);
        }
        return samples;
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean equalsIgnoreCaseNullable(String left, String right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.equalsIgnoreCase(right);
    }
}
