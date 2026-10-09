package com.bigdata.modules.quality.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import com.bigdata.modules.quality.entity.DqRule;
import com.bigdata.modules.quality.entity.DqRuleResult;
import com.bigdata.modules.quality.enums.QualityCheckStatus;
import com.bigdata.modules.quality.mapper.DqRuleMapper;
import com.bigdata.modules.quality.mapper.DqRuleResultMapper;
import com.bigdata.modules.quality.vo.QualityOverviewVO;
import com.bigdata.modules.quality.vo.QualityProblemTableVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 质量大盘 Service。
 *
 * <h3>两个必须说清楚的统计口径</h3>
 * <ol>
 *   <li><b>规则覆盖率的分母</b>：契约写的是「应配置规则的核心表数（有产出任务的关键表）」。
 *       但服务端没有接入调度系统的产出任务视图，无法判定「哪张表有关键产出」。
 *       这里改用「已纳管元数据的表」作为分母 —— 口径是「纳管的表里有多少配了规则」，
 *       而不是「有关键产出的表里有多少配了规则」。这个差异必须是显式的：
 *       若把分母悄悄换成一个小集合，覆盖率会虚高成一个看起来很好看的数字。</li>
 *   <li><b>TOP 问题表的数据源</b>：改为直接聚合 {@code dq_rule_result}（结果留痕）在统计窗口内
 *       未通过的结果行。运行实例链路（dq_run_instance）已删除，原先「按运行实例聚合通过率」
 *       的口径随之消失 —— 因此本服务不再输出通过率趋势与阻断次数：它们要么没有数据源，
 *       要么（阻断次数）依赖任务级处置策略。保留一个恒为 0 的指标比不展示更糟。</li>
 * </ol>
 */
@Service
public class DqOverviewService {

    /** 默认统计窗口（天，含今天） */
    private static final int DEFAULT_WINDOW_DAYS = 7;

    /** 大盘最多扫描的校验结果行数：防止统计接口把库拖垮 */
    private static final int MAX_RESULTS_SCAN = 5000;

    /** TOP 问题表返回条数 */
    private static final int TOP_PROBLEM_LIMIT = 10;

    @Autowired
    private DqRuleMapper dqRuleMapper;

    @Autowired
    private DqRuleResultMapper dqRuleResultMapper;

    @Autowired
    private TableMetadataMapper tableMetadataMapper;

    public QualityOverviewVO getOverview(LocalDate startDate, LocalDate endDate) {
        LocalDate to = endDate == null ? LocalDate.now() : endDate;
        LocalDate from = startDate == null ? to.minusDays(DEFAULT_WINDOW_DAYS - 1L) : startDate;
        if (from.isAfter(to)) {
            throw new BusinessException("统计起始日期不能晚于结束日期：" + from + " > " + to);
        }

        QualityOverviewVO vo = new QualityOverviewVO();

        // ── 规则侧：全量规则与覆盖率 ────────────────────────────────────
        List<DqRule> rules = dqRuleMapper.selectList(new LambdaQueryWrapper<>());
        List<DqRule> enabledRules = rules.stream()
                .filter(r -> r.getEnabled() == null || r.getEnabled() == 1)
                .collect(Collectors.toList());

        vo.setRuleCount(rules.size());
        vo.setEnabledRuleCount(enabledRules.size());

        long coveredTables = rules.stream()
                .map(r -> DqBatchSupport.tableKey(r.getDatabaseName(), r.getTableName()))
                .filter(key -> !key.isEmpty())
                .distinct()
                .count();
        vo.setCoveredTableCount((int) coveredTables);
        vo.setTargetTableCount(countManagedTables());
        vo.setRuleCoverage(ratio(coveredTables, vo.getTargetTableCount()));

        // ── 结果侧：窗口内未通过的结果 → TOP 问题表 ─────────────────────
        vo.setTopProblemTables(buildTopProblemTables(loadUnpassedResults(from, to)));
        return vo;
    }

    // ==================================================================
    // 规则范围
    // ==================================================================

    /**
     * 「应配置规则的表数」= 已纳管元数据的表数（去重 库.表）。
     *
     * <p>见类注释：真正的「核心表」口径需要调度系统的产出任务视图，当前以元数据纳管范围替代。</p>
     */
    private int countManagedTables() {
        List<TableMetadata> tables = tableMetadataMapper.selectList(new LambdaQueryWrapper<TableMetadata>()
                .select(TableMetadata::getDatabaseName, TableMetadata::getTableName));
        return (int) tables.stream()
                .map(t -> DqBatchSupport.tableKey(t.getDatabaseName(), t.getTableName()))
                .filter(key -> !key.isEmpty())
                .distinct()
                .count();
    }

    // ==================================================================
    // TOP 问题表
    // ==================================================================

    /**
     * 取统计窗口内未通过的校验结果。
     *
     * <p>用分页插件而不是 {@code last("LIMIT n")} 限制扫描量：后者在 PG/H2 上会写出方言相关 SQL
     * （见 DqExecutionService 里的同类处理）。</p>
     */
    private List<DqRuleResult> loadUnpassedResults(LocalDate from, LocalDate to) {
        LambdaQueryWrapper<DqRuleResult> wrapper = new LambdaQueryWrapper<>();
        wrapper.ge(DqRuleResult::getCreateTime, from.atStartOfDay())
                .lt(DqRuleResult::getCreateTime, to.plusDays(1).atStartOfDay())
                .ne(DqRuleResult::getStatus, QualityCheckStatus.PASS.getCode())
                .orderByDesc(DqRuleResult::getId);
        Page<DqRuleResult> page = dqRuleResultMapper.selectPage(new Page<>(1, MAX_RESULTS_SCAN), wrapper);
        return page.getRecords();
    }

    /**
     * TOP 问题表。
     *
     * <p>结果表里只有 checkObject（形如 库.表.字段）没有独立的表字段，因此这里用
     * ruleId 反查规则拿到「库.表」—— 比按字符串切分 checkObject 可靠，
     * 因为跨表规则的 checkObject 是「表A ↔ 表B」形态，切分会得出错误的表名。</p>
     */
    private List<QualityProblemTableVO> buildTopProblemTables(List<DqRuleResult> unpassed) {
        if (unpassed.isEmpty()) {
            return List.of();
        }
        Map<Long, DqRule> rules = mapRules(unpassed.stream()
                .map(DqRuleResult::getRuleId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList()));

        Map<String, int[]> counter = new LinkedHashMap<>();
        Map<String, String[]> identity = new LinkedHashMap<>();
        for (DqRuleResult result : unpassed) {
            DqRule rule = result.getRuleId() == null ? null : rules.get(result.getRuleId());
            String databaseName = rule != null ? rule.getDatabaseName() : null;
            String tableName = rule != null ? rule.getTableName() : result.getCheckObject();
            String key = DqBatchSupport.tableKey(databaseName, tableName);
            if (key.isEmpty()) {
                continue;
            }
            counter.computeIfAbsent(key, k -> new int[]{0})[0]++;
            identity.putIfAbsent(key, new String[]{databaseName, tableName, rule == null ? null : rule.getOwnerName()});
        }
        if (counter.isEmpty()) {
            return List.of();
        }

        // 负责人：优先规则的 ownerName，缺失时回落到元数据里的技术负责人
        Map<String, String> ownerFallback = ownerFallback(identity.values().stream()
                .map(v -> v[1]).filter(java.util.Objects::nonNull).distinct().collect(Collectors.toList()));

        return counter.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, int[]>>comparingInt(e -> e.getValue()[0]).reversed())
                .limit(TOP_PROBLEM_LIMIT)
                .map(entry -> {
                    String[] meta = identity.get(entry.getKey());
                    QualityProblemTableVO vo = new QualityProblemTableVO();
                    vo.setDatabaseName(meta[0]);
                    vo.setTableName(meta[1]);
                    vo.setFailCount(entry.getValue()[0]);
                    String owner = meta[2];
                    vo.setOwnerName(owner != null && !owner.isBlank()
                            ? owner : ownerFallback.get(meta[1]));
                    return vo;
                })
                .collect(Collectors.toList());
    }

    /** 按 id 批量取规则，用于把结果映射回「库.表」与负责人 */
    private Map<Long, DqRule> mapRules(List<Long> ruleIds) {
        if (ruleIds == null || ruleIds.isEmpty()) {
            return Map.of();
        }
        List<DqRule> rules = dqRuleMapper.selectBatchIds(ruleIds);
        Map<Long, DqRule> byId = new LinkedHashMap<>();
        for (DqRule rule : rules) {
            byId.put(rule.getId(), rule);
        }
        return byId;
    }

    /** 按表名批量取元数据负责人，作为规则未填负责人时的兜底 */
    private Map<String, String> ownerFallback(List<String> tableNames) {
        if (tableNames.isEmpty()) {
            return Map.of();
        }
        List<TableMetadata> tables = tableMetadataMapper.selectList(new LambdaQueryWrapper<TableMetadata>()
                .select(TableMetadata::getTableName, TableMetadata::getBusinessOwner, TableMetadata::getTechOwner)
                .in(TableMetadata::getTableName, tableNames));
        Map<String, String> owners = new LinkedHashMap<>();
        for (TableMetadata table : tables) {
            String owner = table.getTechOwner() != null && !table.getTechOwner().isBlank()
                    ? table.getTechOwner() : table.getBusinessOwner();
            if (owner != null && !owner.isBlank()) {
                owners.putIfAbsent(table.getTableName(), owner);
            }
        }
        return owners;
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 比例，保留 4 位小数（契约里 ruleCoverage 是 0~1 的小数） */
    private static double ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0d;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
