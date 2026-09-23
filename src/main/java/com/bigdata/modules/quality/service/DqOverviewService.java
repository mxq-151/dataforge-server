package com.bigdata.modules.quality.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import com.bigdata.modules.quality.entity.DqRule;
import com.bigdata.modules.quality.entity.DqRuleResult;
import com.bigdata.modules.quality.entity.DqRunInstance;
import com.bigdata.modules.quality.entity.DqTask;
import com.bigdata.modules.quality.entity.DqTaskRuleRel;
import com.bigdata.modules.quality.enums.QualityCheckStatus;
import com.bigdata.modules.quality.enums.QualityDisposeMode;
import com.bigdata.modules.quality.enums.QualityRunStatus;
import com.bigdata.modules.quality.mapper.DqRuleMapper;
import com.bigdata.modules.quality.mapper.DqTaskMapper;
import com.bigdata.modules.quality.mapper.DqTaskRuleRelMapper;
import com.bigdata.modules.quality.vo.QualityOverviewVO;
import com.bigdata.modules.quality.vo.QualityPassRatePointVO;
import com.bigdata.modules.quality.vo.QualityProblemTableVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 质量大盘 Service。
 *
 * <h3>两个必须说清楚的统计口径</h3>
 * <ol>
 *   <li><b>规则覆盖率的分母</b>：契约写的是「应配置规则的核心表数（有产出任务的关键表）」。
 *       但服务端目前没有接入调度系统的产出任务视图，无法判定「哪张表有关键产出」。
 *       这里改用「已纳管元数据的表」作为分母 —— 口径是「纳管的表里有多少配了规则」，
 *       而不是「有关键产出的表里有多少配了规则」。这个差异必须是显式的：
 *       若把分母悄悄换成一个小集合，覆盖率会虚高成一个看起来很好看的数字。</li>
 *   <li><b>通过率的聚合层级</b>：按运行实例聚合（passCount / 总条数），而不是按结果行聚合。
 *       原因是一次运行的规则条数会影响按行聚合的权重，而「每天有多少次运行达标」
 *       才是运维真正看的东西；同时按运行实例聚合只需扫一张小表，
 *       按结果行聚合在日积月累后会扫爆 dq_rule_result。</li>
 * </ol>
 */
@Service
public class DqOverviewService {

    /** 默认统计窗口（天，含今天） */
    private static final int DEFAULT_WINDOW_DAYS = 7;

    /** 大盘列表最多扫描的运行实例数：防止统计接口把库拖垮 */
    private static final int MAX_RUNS_SCAN = 5000;

    /** TOP 问题表返回条数 */
    private static final int TOP_PROBLEM_LIMIT = 10;

    @Autowired
    private DqRuleMapper dqRuleMapper;

    @Autowired
    private DqTaskMapper dqTaskMapper;

    @Autowired
    private DqTaskRuleRelMapper dqTaskRuleRelMapper;

    @Autowired
    private TableMetadataMapper tableMetadataMapper;

    @Autowired
    private DqRunService dqRunService;

    public QualityOverviewVO getOverview(LocalDate startDate, LocalDate endDate, String projectCode) {
        LocalDate to = endDate == null ? LocalDate.now() : endDate;
        LocalDate from = startDate == null ? to.minusDays(DEFAULT_WINDOW_DAYS - 1L) : startDate;
        if (from.isAfter(to)) {
            throw new BusinessException("统计起始日期不能晚于结束日期：" + from + " > " + to);
        }

        QualityOverviewVO vo = new QualityOverviewVO();

        // ── 规则侧：限定到项目范围内的规则集合 ────────────────────────────
        Set<Long> scopedRuleIds = scopedRuleIds(projectCode);
        List<DqRule> rules = loadRules(scopedRuleIds);
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

        // ── 运行侧：窗口内按天聚合通过率 ────────────────────────────────
        LocalDateTime windowFrom = from.atStartOfDay();
        LocalDateTime windowTo = to.plusDays(1).atStartOfDay();
        List<DqRunInstance> runs = dqRunService.listRunsBetween(windowFrom, windowTo, MAX_RUNS_SCAN)
                .stream()
                .filter(run -> scopedRunMatches(run, projectCode, scopedRuleIds))
                .collect(Collectors.toList());

        vo.setPassRateTrend(buildTrend(runs, from, to));
        vo.setBlockCount(countBlocks(runs));

        List<DqRuleResult> unpassed = dqRunService.listUnpassedResults(
                runs.stream().map(DqRunInstance::getId).collect(Collectors.toList()));
        vo.setTopProblemTables(buildTopProblemTables(unpassed));
        return vo;
    }

    // ==================================================================
    // 规则范围
    // ==================================================================

    /**
     * 计算项目范围下的规则 id 集合。
     *
     * <p>dq_rule 本身不挂项目，项目归属只能通过「被哪个项目的质检任务引用」得到。
     * 因此传了 projectCode 时，只统计该项目任务实际引用的规则 ——
     * 未被任何任务引用的规则属于「配了但没跑」，不应计入某个项目的覆盖率。</p>
     *
     * @return 规则 id 集合；未指定 projectCode 时返回 {@code null} 表示不限定
     */
    private Set<Long> scopedRuleIds(String projectCode) {
        if (projectCode == null || projectCode.isBlank()) {
            return null;
        }
        List<DqTask> tasks = dqTaskMapper.selectList(new LambdaQueryWrapper<DqTask>()
                .eq(DqTask::getDeleted, 0)
                .eq(DqTask::getProjectCode, projectCode.trim()));
        if (tasks.isEmpty()) {
            return Set.of();
        }
        List<Long> taskIds = tasks.stream().map(DqTask::getId).collect(Collectors.toList());
        return dqTaskRuleRelMapper.selectList(new LambdaQueryWrapper<DqTaskRuleRel>()
                        .eq(DqTaskRuleRel::getDeleted, 0)
                        .in(DqTaskRuleRel::getTaskId, taskIds))
                .stream()
                .map(DqTaskRuleRel::getRuleId)
                .collect(Collectors.toSet());
    }

    private List<DqRule> loadRules(Set<Long> scopedRuleIds) {
        LambdaQueryWrapper<DqRule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DqRule::getDeleted, 0);
        if (scopedRuleIds != null) {
            if (scopedRuleIds.isEmpty()) {
                return List.of();
            }
            wrapper.in(DqRule::getId, scopedRuleIds);
        }
        return dqRuleMapper.selectList(wrapper);
    }

    /** 项目范围下，运行实例必须属于该项目任务引用的规则 */
    private boolean scopedRunMatches(DqRunInstance run, String projectCode, Set<Long> scopedRuleIds) {
        if (projectCode == null || projectCode.isBlank() || scopedRuleIds == null) {
            return true;
        }
        if (run.getTaskId() == null) {
            // 结果上报类运行没有任务归属；项目口径下不纳入统计
            return false;
        }
        DqTask task = dqTaskMapper.selectById(run.getTaskId());
        return task != null && projectCode.trim().equals(task.getProjectCode());
    }

    /**
     * 「应配置规则的表数」= 已纳管元数据的表数（去重 库.表）。
     *
     * <p>见类注释：真正的「核心表」口径需要调度系统的产出任务视图，当前以元数据纳管范围替代。
     * 只取未逻辑删除的元数据，避免把已下线表算进分母。</p>
     */
    private int countManagedTables() {
        List<TableMetadata> tables = tableMetadataMapper.selectList(new LambdaQueryWrapper<TableMetadata>()
                .select(TableMetadata::getDatabaseName, TableMetadata::getTableName)
                .eq(TableMetadata::getDeleted, 0));
        return (int) tables.stream()
                .map(t -> DqBatchSupport.tableKey(t.getDatabaseName(), t.getTableName()))
                .filter(key -> !key.isEmpty())
                .distinct()
                .count();
    }

    // ==================================================================
    // 趋势与 TOP 问题表
    // ==================================================================

    /** 按自然日补零的通过率趋势：没有运行的日期也要有点，否则折线图会把空档连成直线 */
    private List<QualityPassRatePointVO> buildTrend(List<DqRunInstance> runs, LocalDate from, LocalDate to) {
        Map<LocalDate, long[]> byDate = new LinkedHashMap<>();
        for (LocalDate cursor = from; !cursor.isAfter(to); cursor = cursor.plusDays(1)) {
            byDate.put(cursor, new long[]{0, 0, 0});
        }
        for (DqRunInstance run : runs) {
            if (run.getStartTime() == null) {
                continue;
            }
            long[] bucket = byDate.get(run.getStartTime().toLocalDate());
            if (bucket == null) {
                continue;
            }
            bucket[0] += nullSafe(run.getPassCount());
            bucket[1] += nullSafe(run.getWarnCount()) + nullSafe(run.getErrorCount()) + nullSafe(run.getFailedCount());
        }
        List<QualityPassRatePointVO> points = new ArrayList<>();
        for (Map.Entry<LocalDate, long[]> entry : byDate.entrySet()) {
            long pass = entry.getValue()[0];
            long fail = entry.getValue()[1];
            QualityPassRatePointVO point = new QualityPassRatePointVO();
            point.setStatDate(entry.getKey().toString());
            point.setFailCount((int) fail);
            point.setPassRate(ratio(pass, pass + fail));
            points.add(point);
        }
        return points;
    }

    /** 强规则阻断次数：处置策略为「阻断下游」的任务，其失败运行次数 */
    private int countBlocks(List<DqRunInstance> runs) {
        List<Long> taskIds = runs.stream()
                .map(DqRunInstance::getTaskId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (taskIds.isEmpty()) {
            return 0;
        }
        Map<Long, String> policies = dqRunService.mapTaskPolicies(taskIds);
        int blocks = 0;
        for (DqRunInstance run : runs) {
            if (!QualityRunStatus.FAILED.getCode().equalsIgnoreCase(run.getStatus()) || run.getTaskId() == null) {
                continue;
            }
            if (QualityDisposeMode.BLOCK.getCode().equalsIgnoreCase(policies.get(run.getTaskId()))) {
                blocks++;
            }
        }
        return blocks;
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
        Map<Long, DqRule> rules = dqRunService.mapRules(unpassed.stream()
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

    /** 按表名批量取元数据负责人，作为规则未填负责人时的兜底 */
    private Map<String, String> ownerFallback(List<String> tableNames) {
        if (tableNames.isEmpty()) {
            return Map.of();
        }
        List<TableMetadata> tables = tableMetadataMapper.selectList(new LambdaQueryWrapper<TableMetadata>()
                .select(TableMetadata::getTableName, TableMetadata::getBusinessOwner, TableMetadata::getTechOwner)
                .eq(TableMetadata::getDeleted, 0)
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

    /** 比例，保留 4 位小数（契约里 ruleCoverage / passRate 都是 0~1 的小数） */
    private static double ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0d;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static long nullSafe(Integer value) {
        return value == null ? 0L : value;
    }
}
