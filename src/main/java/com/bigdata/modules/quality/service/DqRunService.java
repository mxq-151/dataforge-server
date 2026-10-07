package com.bigdata.modules.quality.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.common.utils.SecurityUtils;
import com.bigdata.modules.quality.dto.QualityRunReportDTO;
import com.bigdata.modules.quality.entity.DqAlertLog;
import com.bigdata.modules.quality.entity.DqAlertSubscription;
import com.bigdata.modules.quality.entity.DqRule;
import com.bigdata.modules.quality.entity.DqRuleResult;
import com.bigdata.modules.quality.entity.DqRunInstance;
import com.bigdata.modules.quality.entity.DqTask;
import com.bigdata.modules.quality.entity.DqTaskRuleRel;
import com.bigdata.modules.quality.entity.DataQualityCheckResult;
import com.bigdata.modules.quality.enums.QualityCheckStatus;
import com.bigdata.modules.quality.enums.QualityDisposeMode;
import com.bigdata.modules.quality.enums.QualityRuleType;
import com.bigdata.modules.quality.enums.QualityRunStatus;
import com.bigdata.modules.quality.enums.QualityTriggerType;
import com.bigdata.modules.quality.mapper.DqAlertLogMapper;
import com.bigdata.modules.quality.mapper.DqAlertSubscriptionMapper;
import com.bigdata.modules.quality.mapper.DqRuleMapper;
import com.bigdata.modules.quality.mapper.DqRuleResultMapper;
import com.bigdata.modules.quality.mapper.DqRunInstanceMapper;
import com.bigdata.modules.quality.mapper.DqTaskMapper;
import com.bigdata.modules.quality.mapper.DqTaskRuleRelMapper;
import com.bigdata.modules.quality.mapper.DataQualityCheckResultMapper;
import com.bigdata.modules.quality.sql.DqBuiltinTemplates;
import com.bigdata.modules.quality.sql.DqSqlBuilder;
import com.bigdata.modules.quality.vo.QualityRuleResultVO;
import com.bigdata.modules.quality.vo.QualityRunInstanceVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 运行实例 Service —— 质检任务的执行、运行记录查询、结果上报。
 *
 * <h3>为什么手动触发是异步的</h3>
 * <p>契约里 {@code runTask} 返回的是运行实例 id 并「用于随后轮询运行结果」，
 * 说明接口设计上就是异步的。这不是为了好看：一次校验要顺序下发 N 条 SQL，
 * 单条可能扫千万行，同步执行会把 HTTP 请求挂住几分钟甚至超时，
 * 而超时后前端拿不到 id，就永远查不到这次运行 —— 反而更糟。</p>
 *
 * <p>因此这里先落一条 {@code RUNNING} 的运行实例并立即返回 id，再在工作线程里执行。
 * 工作线程必须<b>手工把 SecurityContext 传进去</b>：校验 SQL 要走
 * CodeExecutionService，而它依赖 SecurityContext 做表级权限校验与行/列级改写；
 * 不传的话每条规则都会以「未登录」被拒，且失败原因看起来像权限配置问题，很难排查。</p>
 *
 * <h3>checkRecordCount / failRecordCount 的口径</h3>
 * <p>计数类规则的校验 SQL 只返回<b>异常条数</b>（不是扫描行数），因为让每条校验 SQL
 * 多返回一个总数会显著增加扫描成本。因此这里明确口径为：</p>
 * <ul>
 *   <li>{@code checkRecordCount} = Σ 各规则实际值对应的记录规模
 *       （行数类=当期行数，跨表类=主表口径值，计数类=异常条数）；</li>
 *   <li>{@code failRecordCount} = Σ 未通过规则的实际值。</li>
 * </ul>
 * <p>S3 侧回传结果时可以直接给出更精确的数值（见 {@link #reportRun}），届时以此为准。</p>
 */
@Service
public class DqRunService {

    private static final Logger log = LoggerFactory.getLogger(DqRunService.class);

    /**
     * 运行执行线程池。
     *
     * <p>刻意限定为 2 个线程且队列无界：校验是重 IO 操作，放开并发只会让目标库更容易被打满；
     * 排队执行虽然慢，但能保证每次运行都有确定的落库结果（不会因为线程饥饿而永远 RUNNING）。</p>
     */
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            2, 2, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            runnable -> {
                Thread thread = new Thread(runnable, "dq-run-" + THREAD_SEQ.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    /** 兼容写入的 fail_detail 序列化器（仅用于构造 JSON 明细文本） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private DqRunInstanceMapper dqRunInstanceMapper;

    @Autowired
    private DqRuleResultMapper dqRuleResultMapper;

    @Autowired
    private DqRuleMapper dqRuleMapper;

    @Autowired
    private DqTaskMapper dqTaskMapper;

    @Autowired
    private DqTaskRuleRelMapper dqTaskRuleRelMapper;

    @Autowired
    private DqAlertSubscriptionMapper dqAlertSubscriptionMapper;

    @Autowired
    private DqAlertLogMapper dqAlertLogMapper;

    @Autowired
    private DqExecutionService dqExecutionService;

    @Autowired
    private DqAlertSender dqAlertSender;

    @Autowired
    private DataAccessPermissionService permissionService;

    @Autowired
    private DataQualityCheckResultMapper dataQualityCheckResultMapper;

    // ==================================================================
    // 触发执行
    // ==================================================================

    /**
     * 手动/调度触发一次质检任务：先落 RUNNING 实例并返回 id，再异步执行。
     *
     * @param rules 本次要执行的规则（已按任务关联解析）
     * @return 运行实例 id
     */
    public Long startRun(DqTask task, List<DqRule> rules, QualityTriggerType triggerType,
                         Long scheduleInstanceId, String batchNo) {
        if (rules == null || rules.isEmpty()) {
            throw new BusinessException("任务「" + task.getTaskName()
                    + "」下没有可执行的规则，请先给任务挂载监控规则");
        }
        String effectiveBatchNo = batchNo == null || batchNo.isBlank() ? DqBatchSupport.todayBatchNo() : batchNo.trim();

        DqRunInstance instance = new DqRunInstance();
        instance.setTaskId(task.getId());
        instance.setTaskName(task.getTaskName());
        instance.setBatchNo(effectiveBatchNo);
        instance.setTriggerType(triggerType == null
                ? QualityTriggerType.MANUAL.getCode() : triggerType.getCode());
        instance.setScheduleInstanceId(scheduleInstanceId);
        instance.setTargetDatasourceId(task.getDatasourceId());
        instance.setTargetDatabaseName(task.getDatabaseName());
        instance.setTargetTableName(task.getTableName());
        instance.setStartTime(LocalDateTime.now());
        instance.setStatus(QualityRunStatus.RUNNING.getCode());
        instance.setCheckRecordCount(0L);
        instance.setFailRecordCount(0L);
        instance.setPassCount(0);
        instance.setWarnCount(0);
        instance.setErrorCount(0);
        instance.setFailedCount(0);
        dqRunInstanceMapper.insert(instance);

        Long runId = instance.getId();
        LocalDate batchDate = DqBatchSupport.parseBatchDate(effectiveBatchNo);
        boolean blockOnError = QualityDisposeMode.BLOCK.getCode().equalsIgnoreCase(task.getDisposePolicy());
        SecurityContext securityContext = SecurityContextHolder.getContext();

        executor.submit(() -> {
            try {
                SecurityContextHolder.setContext(securityContext);
                executeRun(runId, task, rules, batchDate, blockOnError);
            } catch (Exception e) {
                log.error("质检任务执行异常: runId={}, task={}", runId, task.getTaskName(), e);
                markRunFailed(runId, e.getMessage());
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        return runId;
    }

    /** 工作线程内的实际执行：逐条下发规则、落结果、算聚合、触发告警登记 */
    private void executeRun(Long runId, DqTask task, List<DqRule> rules,
                            LocalDate batchDate, boolean blockOnError) {
        List<QualityCheckStatus> statuses = new ArrayList<>();
        long checkTotal = 0L;
        long failTotal = 0L;
        List<DqRuleResult> results = new ArrayList<>();

        for (DqRule rule : rules) {
            DqRuleResult row = executeOneRule(runId, rule, batchDate);
            results.add(row);
            QualityCheckStatus status = QualityCheckStatus.fromCode(row.getStatus());
            if (status != null) {
                statuses.add(status);
            }
            checkTotal += toLong(row.getMetricValue());
            if (status != null && status.isNotPassed()) {
                failTotal += toLong(row.getMetricValue());
            }
        }
        for (DqRuleResult row : results) {
            dqRuleResultMapper.insert(row);
        }

        int passCount = DqBatchSupport.count(statuses, QualityCheckStatus.PASS);
        int warnCount = DqBatchSupport.count(statuses, QualityCheckStatus.WARN);
        int errorCount = DqBatchSupport.count(statuses, QualityCheckStatus.ERROR);
        int failedCount = DqBatchSupport.count(statuses, QualityCheckStatus.FAILED);

        DqRunInstance update = new DqRunInstance();
        update.setId(runId);
        update.setStatus(DqBatchSupport.resolveRunStatus(failedCount > 0, errorCount > 0, blockOnError));
        update.setEndTime(LocalDateTime.now());
        update.setCheckRecordCount(checkTotal);
        update.setFailRecordCount(failTotal);
        update.setPassCount(passCount);
        update.setWarnCount(warnCount);
        update.setErrorCount(errorCount);
        update.setFailedCount(failedCount);
        dqRunInstanceMapper.updateById(update);

        log.info("质检任务执行完成: runId={}, task={}, pass={}, warn={}, error={}, failed={}, status={}",
                runId, task.getTaskName(), passCount, warnCount, errorCount, failedCount, update.getStatus());

        archiveToLegacyResult(task, rules, results);
        registerAlerts(task, runId, results);
    }

    /**
     * 把本次运行结果同步一份到既有的 {@code data_quality_check_result}。
     *
     * <h3>为什么要有这条「冗余」写入</h3>
     * <p>新链路的结果落在 dq_rule_result（含 rule_type / metric_value / exec_sql 等完整留痕），
     * 但平台上已有的报表与看板仍在读 data_quality_check_result。
     * 如果不写，这些既有消费面会在新链路启用后「突然没有数据了」——
     * 使用者会以为质量监控停了，而实际上只是数据换了张表。
     * 因此这里刻意保留一份兼容写入，并在 7 类收敛后补上 rule_type / rule_name，
     * 让新旧两条链路看到的是同一件事。</p>
     *
     * <h3>字段口径（刻意不做美化）</h3>
     * <ul>
     *   <li>{@code check_item} = 规则类型的中文名（如「主键唯一性」）——
     *       该列的历史语义就是「检查项类别」，与类型标签对齐后旧报表按 check_item
     *       聚合出来的结果仍然可读；</li>
     *   <li>{@code check_record_count} = 行数类规则记当期行数；其余类型无法从
     *       执行结果中得到扫描规模，记 0 而不是编一个数；</li>
     *   <li>{@code fail_record_count} = 计数类规则记真实异常条数；其余类型按
     *       「未通过记 1、通过记 0」表达，保证旧报表的 {@code failOnly} 过滤依然有效。</li>
     * </ul>
     */
    private void archiveToLegacyResult(DqTask task, List<DqRule> rules, List<DqRuleResult> results) {
        if (results.isEmpty()) {
            return;
        }
        Map<Long, DqRule> ruleById = new LinkedHashMap<>();
        for (DqRule rule : rules) {
            if (rule.getId() != null) {
                ruleById.put(rule.getId(), rule);
            }
        }

        LocalDateTime now = LocalDateTime.now();
        int archived = 0;
        for (DqRuleResult result : results) {
            DqRule rule = ruleById.get(result.getRuleId());
            if (rule == null) {
                continue;
            }
            try {
                DataQualityCheckResult legacy = new DataQualityCheckResult();
                legacy.setDatasourceId(rule.getDatasourceId() != null
                        ? rule.getDatasourceId() : task.getDatasourceId());
                legacy.setDatasourceName(rule.getDatasourceName() != null
                        ? rule.getDatasourceName() : task.getDatasourceName());
                legacy.setDatabaseName(rule.getDatabaseName());
                legacy.setTableName(rule.getTableName());
                legacy.setCheckItem(checkItemLabel(rule));
                legacy.setRuleType(rule.getRuleType());
                legacy.setRuleName(rule.getRuleName());
                legacy.setCheckTime(result.getCreateTime() == null ? now : result.getCreateTime());

                QualityCheckStatus status = QualityCheckStatus.fromCode(result.getStatus());
                boolean notPassed = status != null && status.isNotPassed();
                BigDecimal metric = toNumber(result.getMetricValue());
                if (isAbnormalCountRule(rule)) {
                    legacy.setCheckRecordCount(0L);
                    legacy.setFailRecordCount(notPassed && metric != null ? metric.longValue() : 0L);
                } else if (isRowCountRule(rule)) {
                    legacy.setCheckRecordCount(metric == null ? 0L : metric.longValue());
                    legacy.setFailRecordCount(notPassed && metric != null ? metric.longValue() : 0L);
                } else {
                    legacy.setCheckRecordCount(0L);
                    legacy.setFailRecordCount(notPassed ? 1L : 0L);
                }
                legacy.setFailDetail(buildLegacyFailDetail(result));
                legacy.setCreateTime(now);
                legacy.setUpdateTime(now);
                dataQualityCheckResultMapper.insert(legacy);
                archived++;
            } catch (Exception e) {
                // 兼容写入失败不能影响主链路的运行结论（结果已在 dq_rule_result 留痕）
                log.warn("兼容写入 data_quality_check_result 失败: ruleId={}, 原因={}",
                        result.getRuleId(), e.getMessage());
            }
        }
        log.info("质检结果兼容写入完成: 条数={}", archived);
    }

    /** check_item 取值：优先用规则类型的中文名（与旧报表的「检查项」语义一致） */
    private static String checkItemLabel(DqRule rule) {
        QualityRuleType type = QualityRuleType.fromCode(rule.getRuleType());
        if (type != null) {
            return type.getLabel();
        }
        // 类型无法识别时退回规则名，至少不丢信息
        return rule.getRuleName() == null ? rule.getRuleType() : rule.getRuleName();
    }

    private static boolean isRowCountRule(DqRule rule) {
        QualityRuleType type = QualityRuleType.fromCode(rule.getRuleType());
        return type == QualityRuleType.ROW_COUNT_FLUCTUATION || type == QualityRuleType.ROW_COUNT_MOM;
    }

    private static boolean isAbnormalCountRule(DqRule rule) {
        DqBuiltinTemplates.Definition definition =
                DqBuiltinTemplates.of(QualityRuleType.fromCode(rule.getRuleType()));
        return definition != null
                && definition.metricKind() == DqSqlBuilder.MetricKind.ABNORMAL_ROW_COUNT;
    }

    private static String buildLegacyFailDetail(DqRuleResult result) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ruleId", result.getRuleId());
        detail.put("checkObject", result.getCheckObject());
        detail.put("threshold", result.getThreshold());
        detail.put("actualValue", result.getActualValue());
        detail.put("status", result.getStatus());
        if (result.getErrorMsg() != null && !result.getErrorMsg().isBlank()) {
            detail.put("errorMsg", result.getErrorMsg());
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(detail);
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal toNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 执行单条规则并组装结果行（不落库，由调用方批量插入） */
    private DqRuleResult executeOneRule(Long runId, DqRule rule, LocalDate batchDate) {
        DqRuleResult row = new DqRuleResult();
        row.setRunId(runId);
        row.setRuleId(rule.getId());
        row.setRuleName(rule.getRuleName());
        row.setRuleType(rule.getRuleType());
        try {
            BigDecimal baseline = dqExecutionService.resolveBaseline(rule);
            DqExecutionService.Outcome outcome = dqExecutionService.execute(rule, batchDate, baseline);
            row.setCheckObject(outcome.compiled().checkObject());
            row.setThreshold(outcome.evaluation().threshold());
            row.setActualValue(outcome.evaluation().actualValue());
            row.setMetricValue(DqExecutionService.metricValue(
                    outcome.compiled().metricKind(),
                    DqExecutionService.firstRow(outcome.execution())));
            QualityCheckStatus status = outcome.evaluation().status();
            row.setStatus(status == null ? QualityCheckStatus.FAILED.getCode() : status.getCode());
            row.setSeverity(outcome.evaluation().severity());
            row.setExecSql(outcome.compiled().sql());
            row.setErrorMsg(truncate(outcome.errorMessage(), 1000));
            row.setCostMs(outcome.costMs());
        } catch (Exception e) {
            // 单条规则失败不能让整次运行中断：其余规则的结果同样有价值
            log.error("规则执行异常: rule={}({}), runId={}", rule.getRuleName(), rule.getRuleType(), runId, e);
            row.setStatus(QualityCheckStatus.FAILED.getCode());
            row.setSeverity(QualityCheckStatus.FAILED.getCode());
            row.setErrorMsg(truncate(e.getMessage(), 1000));
            row.setCostMs(0L);
        }
        row.setCreateTime(LocalDateTime.now());
        row.setUpdateTime(LocalDateTime.now());
        return row;
    }

    private void markRunFailed(Long runId, String message) {
        try {
            DqRunInstance update = new DqRunInstance();
            update.setId(runId);
            update.setStatus(QualityRunStatus.FAILED.getCode());
            update.setEndTime(LocalDateTime.now());
            dqRunInstanceMapper.updateById(update);
            log.error("质检运行已置为 FAILED: runId={}, reason={}", runId, message);
        } catch (Exception e) {
            log.error("回写运行失败状态时再次异常: runId={}", runId, e);
        }
    }

    /**
     * 告警登记。
     *
     * <p>只做「登记待发」而不是「发送成功」：当前服务端没有接入邮件/IM 通道，
     * 若把发送记录写成 SUCCESS，运行记录会呈现一个从未真正发出的告警 ——
     * 那比没有告警更危险。因此命中订阅规则时登记一条 PENDING 记录并写明原因，
     * 通道接入后只需消费这张表即可。</p>
     */
    private void registerAlerts(DqTask task, Long runId, List<DqRuleResult> results) {
        List<DqAlertSubscription> subscriptions = dqAlertSubscriptionMapper.selectList(
                new LambdaQueryWrapper<DqAlertSubscription>()
                        .eq(DqAlertSubscription::getTaskId, task.getId())
                        .eq(DqAlertSubscription::getEnabled, 1));
        if (subscriptions.isEmpty()) {
            return;
        }
        try {
            for (DqRuleResult result : results) {
                if (QualityCheckStatus.PASS.getCode().equals(result.getStatus())) {
                    continue;
                }
                for (DqAlertSubscription subscription : subscriptions) {
                    if (!notifyMatched(subscription.getNotifyOn(), result.getStatus())) {
                        continue;
                    }
                    for (String channel : splitCsv(subscription.getChannels())) {
                        dqAlertLogMapper.insert(buildAlert(task, runId, result, subscription, channel));
                    }
                }
            }
        } catch (Exception e) {
            // 告警登记失败不影响校验结果本身的留痕
            log.error("登记告警记录失败: runId={}", runId, e);
        }
    }

    /**
     * 组装并投递一条告警记录。
     *
     * <p>{@code dq_alert_log.status} 是三档投递状态，界面按此展示：</p>
     * <ul>
     *   <li>{@code SENT} 已送达：站内记录落表即送达；Webhook 投递成功也是；</li>
     *   <li>{@code FAILED} 投递失败：{@code failReason} 写明原因，可重投；</li>
     *   <li>{@code PENDING} 待发：渠道未接入或未配置（邮件缺 SMTP、Webhook 缺地址）。</li>
     * </ul>
     *
     * <p>{@code sendTime} 只在真正送达时写 —— 待发的记录带一个发送时间会误导排查。</p>
     */
    private DqAlertLog buildAlert(DqTask task, Long runId, DqRuleResult result,
                                  DqAlertSubscription subscription, String channel) {
        DqAlertLog alert = new DqAlertLog();
        alert.setRunId(runId);
        alert.setTaskId(task.getId());
        alert.setRuleId(result.getRuleId());
        alert.setRuleName(result.getRuleName());
        alert.setChannel(channel);
        alert.setReceiver(joinCsv(splitCsv(subscription.getReceivers())));

        String normalized = channel == null ? "" : channel.trim().toUpperCase();
        switch (normalized) {
            case DqAlertSender.CHANNEL_INBOX -> {
                alert.setStatus(DqAlertSender.STATUS_SENT);
                alert.setSendTime(LocalDateTime.now());
            }
            case DqAlertSender.CHANNEL_WEBHOOK -> {
                String failure = dqAlertSender.sendWebhook(alert, result.getStatus());
                if (failure == null) {
                    alert.setStatus(DqAlertSender.STATUS_SENT);
                    alert.setSendTime(LocalDateTime.now());
                } else {
                    alert.setStatus(DqAlertSender.STATUS_FAILED);
                    alert.setFailReason(failure);
                }
            }
            case DqAlertSender.CHANNEL_EMAIL -> {
                alert.setStatus(DqAlertSender.STATUS_PENDING);
                // 见 DqAlertSender 类注释：平台没有 SMTP 配置，不做半成品
                alert.setFailReason("邮件通道未配置，已登记待发（第一期实际投递的是站内记录与 Webhook）");
            }
            default -> {
                alert.setStatus(DqAlertSender.STATUS_PENDING);
                alert.setFailReason("未知告警渠道编码「" + channel + "」，未投递");
            }
        }
        return alert;
    }

    private static boolean notifyMatched(String notifyOn, String status) {
        if (notifyOn == null || notifyOn.isBlank()) {
            // 未配置触发状态时，默认只对「红色异常」和「校验失败」告警
            return QualityCheckStatus.ERROR.getCode().equals(status)
                    || QualityCheckStatus.FAILED.getCode().equals(status);
        }
        return splitCsv(notifyOn).stream().anyMatch(s -> s.equalsIgnoreCase(status));
    }

    // ==================================================================
    // 查询
    // ==================================================================

    public Page<QualityRunInstanceVO> page(int current, int size, Long taskId, String batchNo,
                                           String status, LocalDate startDate, LocalDate endDate) {
        LambdaQueryWrapper<DqRunInstance> wrapper = new LambdaQueryWrapper<>();
        if (taskId != null) {
            wrapper.eq(DqRunInstance::getTaskId, taskId);
        }
        if (batchNo != null && !batchNo.isBlank()) {
            wrapper.eq(DqRunInstance::getBatchNo, batchNo.trim());
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(DqRunInstance::getStatus, status.trim());
        }
        if (startDate != null) {
            wrapper.ge(DqRunInstance::getStartTime, startDate.atStartOfDay());
        }
        if (endDate != null) {
            wrapper.lt(DqRunInstance::getStartTime, endDate.plusDays(1).atStartOfDay());
        }
        wrapper.orderByDesc(DqRunInstance::getId);

        Page<DqRunInstance> result = dqRunInstanceMapper.selectPage(new Page<>(current, size), wrapper);
        Page<QualityRunInstanceVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(DqRunService::toVO).collect(Collectors.toList()));
        return voPage;
    }

    public DqRunInstance getById(Long id) {
        if (id == null) {
            throw new BusinessException("运行实例 ID 不能为空");
        }
        DqRunInstance instance = dqRunInstanceMapper.selectById(id);
        if (instance == null) {
            throw new BusinessException("运行实例不存在: " + id);
        }
        return instance;
    }

    /** 规则级结果：满足提示词「规则名/校验对象/阈值/实际值/是否通过」五要素 */
    public List<QualityRuleResultVO> listResults(Long runId) {
        List<DqRuleResult> rows = dqRuleResultMapper.selectList(new LambdaQueryWrapper<DqRuleResult>()
                .eq(DqRuleResult::getRunId, runId)
                .orderByAsc(DqRuleResult::getId));
        return rows.stream().map(DqRunService::toVO).collect(Collectors.toList());
    }

    // ==================================================================
    // 结果上报（S3 侧回传）
    // ==================================================================

    /**
     * 结果上报：外部校验任务把单条规则的校验结果回传。
     *
     * <p>同一批次 + 同一目标表的多次上报会合并到同一个运行实例上 ——
     * 否则 S3 侧按规则逐条上报会生成几十条「只含一条结果」的运行记录，
     * 运行列表会变成噪音。合并窗口取「当天 + 目标表相同 + 尚无结束时间」，
     * 避免把昨天未收尾的实例当成今天的容器。</p>
     */
    public void reportRun(QualityRunReportDTO dto) {
        if (dto == null || dto.getRuleName() == null || dto.getRuleName().isBlank()) {
            throw new BusinessException("上报内容缺少规则名");
        }
        String batchNo = dto.getBatchNo() == null || dto.getBatchNo().isBlank()
                ? DqBatchSupport.todayBatchNo() : dto.getBatchNo().trim();

        // 先解析规则与任务：它们决定这次回传归到哪条规则、哪个任务，也决定新建实例时的归属
        Long ruleId = resolveRuleId(dto);
        DqTask boundTask = resolveTask(ruleId);

        // 表级读权限：回传是**写入**行为，越权写结果会污染平台数据。
        // fail-close —— 与资产目录的树/检索同一条安全原则：宁可拒绝落库，也不放行。
        assertTableReadable(dto);

        DqRunInstance instance = findReusableRun(batchNo, dto.getDatabaseName(), dto.getTableName());
        if (instance == null) {
            instance = new DqRunInstance();
            instance.setBatchNo(batchNo);
            // 能定位到任务就用任务名与 taskId；定位不到退回规则名，至少让运行记录里认得出是谁报的
            instance.setTaskId(boundTask == null ? null : boundTask.getId());
            instance.setTaskName(boundTask == null ? dto.getRuleName() : boundTask.getTaskName());
            instance.setTriggerType(QualityTriggerType.MANUAL.getCode());
            instance.setTargetDatasourceId(dto.getDatasourceId());
            instance.setTargetDatabaseName(dto.getDatabaseName());
            instance.setTargetTableName(dto.getTableName());
            instance.setStartTime(LocalDateTime.now());
            instance.setStatus(QualityRunStatus.RUNNING.getCode());
            instance.setCheckRecordCount(0L);
            instance.setFailRecordCount(0L);
            instance.setPassCount(0);
            instance.setWarnCount(0);
            instance.setErrorCount(0);
            instance.setFailedCount(0);
            dqRunInstanceMapper.insert(instance);
        }

        QualityCheckStatus status = Boolean.TRUE.equals(dto.getPassed())
                ? QualityCheckStatus.PASS : QualityCheckStatus.ERROR;
        DqRuleResult row = new DqRuleResult();
        row.setRunId(instance.getId());
        row.setRuleId(ruleId);
        row.setRuleName(dto.getRuleName());
        row.setRuleType(dto.getRuleType());
        row.setCheckObject(dto.getCheckObject());
        row.setThreshold(dto.getThreshold());
        row.setActualValue(dto.getActualValue());
        row.setMetricValue(dto.getActualValue());
        row.setStatus(status.getCode());
        row.setSeverity(Boolean.TRUE.equals(dto.getPassed()) ? null : status.getCode());
        row.setErrorMsg(dto.getFailDetail());
        row.setExecSql(dto.getExecSql());
        row.setCostMs(0L);
        row.setCreateTime(LocalDateTime.now());
        row.setUpdateTime(LocalDateTime.now());
        dqRuleResultMapper.insert(row);
        recalculateRun(instance.getId());

        // 有任务归属才登记告警：订阅是按任务配的，没有任务就无从匹配。
        // 程序回传路径此前完全不登记告警（只有平台执行路径会），这里补齐。
        if (boundTask != null) {
            registerAlerts(boundTask, instance.getId(), List.of(row));
        }
    }

    /**
     * 由规则反查唯一绑定的质检任务。
     *
     * <p>一条规则可以同时挂在多个任务上。这时"这次回传该归到哪个任务"没有正确答案 ——
     * 宁可留空也不猜：猜错会让告警发给错的人、任务维度统计错位，而留空的代价只是运行记录里
     * 少一层归属（{@code taskName} 退化为规则名，仍可辨认）。</p>
     */
    private DqTask resolveTask(Long ruleId) {
        if (ruleId == null) {
            return null;
        }
        List<DqTaskRuleRel> rels = dqTaskRuleRelMapper.selectList(new LambdaQueryWrapper<DqTaskRuleRel>()
                .eq(DqTaskRuleRel::getRuleId, ruleId));
        if (rels.size() != 1 || rels.get(0).getTaskId() == null) {
            return null;
        }
        return dqTaskMapper.selectById(rels.get(0).getTaskId());
    }

    /**
     * 校验当前用户对回传目标表有读权限。
     *
     * <p>缺表名时不校验（无从判定目标）；其余情况一律 fail-close：
     * 校验 SQL 是"读"，回传结果是"写"，能写一条自己读不到的表的结果就等于可以伪造它。</p>
     */
    private void assertTableReadable(QualityRunReportDTO dto) {
        if (dto.getTableName() == null || dto.getTableName().isBlank()) {
            return;
        }
        if (hasTableReadAccess(dto.getDatasourceName(), dto.getDatabaseName(), dto.getTableName().trim())) {
            return;
        }
        String target = (dto.getDatabaseName() == null || dto.getDatabaseName().isBlank())
                ? dto.getTableName().trim()
                : dto.getDatabaseName().trim() + "." + dto.getTableName().trim();
        throw new BusinessException("当前用户无权读取表 " + target + "，已拒绝登记其校验结果");
    }

    /**
     * 解析本次回传对应的规则 ID。
     *
     * <p>回传路径原先只落 rule_name 字符串，一旦改名（设计文档改了、平台里没跟着改）
     * 就与平台规则断链；而「行数波动 / 行数环比」这类规则的历史基线是按 rule_id 取的，
     * 断链后这些规则永远取不到程序回传的基线。这里把关联补上：</p>
     * <ul>
     *   <li>显式传了 {@code ruleId} 直接用（S3 登记过规则时走这条）；</li>
     *   <li>否则按「规则名 + 库 + 表」三段反查 —— 三段限定是为了避免跨库同名规则误配；</li>
     *   <li>查不到返回 {@code null} 且<strong>不报错</strong>：规则还没登记时回传仍应成功，
     *       回传失败不得改变校验任务本身的成败判定。</li>
     * </ul>
     */
    private Long resolveRuleId(QualityRunReportDTO dto) {
        if (dto.getRuleId() != null) {
            return dto.getRuleId();
        }
        if (dto.getRuleName() == null || dto.getRuleName().isBlank()) {
            return null;
        }
        LambdaQueryWrapper<DqRule> wrapper = new LambdaQueryWrapper<DqRule>()
                .eq(DqRule::getRuleName, dto.getRuleName().trim());
        if (dto.getDatabaseName() != null && !dto.getDatabaseName().isBlank()) {
            wrapper.eq(DqRule::getDatabaseName, dto.getDatabaseName().trim());
        }
        if (dto.getTableName() != null && !dto.getTableName().isBlank()) {
            wrapper.eq(DqRule::getTableName, dto.getTableName().trim());
        }
        wrapper.orderByDesc(DqRule::getId);
        List<DqRule> found = dqRuleMapper.selectList(wrapper.last("LIMIT 1"));
        return found.isEmpty() ? null : found.get(0).getId();
    }

    /** 上报场景的运行实例复用：同批次 + 同表 + 当天创建且未结束 */
    private DqRunInstance findReusableRun(String batchNo, String databaseName, String tableName) {
        LambdaQueryWrapper<DqRunInstance> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DqRunInstance::getBatchNo, batchNo)
                .eq(DqRunInstance::getStatus, QualityRunStatus.RUNNING.getCode())
                .ge(DqRunInstance::getStartTime, LocalDate.now().atStartOfDay());
        if (databaseName != null && !databaseName.isBlank()) {
            wrapper.eq(DqRunInstance::getTargetDatabaseName, databaseName);
        }
        if (tableName != null && !tableName.isBlank()) {
            wrapper.eq(DqRunInstance::getTargetTableName, tableName);
        }
        wrapper.orderByDesc(DqRunInstance::getId);
        List<DqRunInstance> found = dqRunInstanceMapper.selectList(
                wrapper.last("LIMIT 1"));
        return found.isEmpty() ? null : found.get(0);
    }

    /** 按已有结果重算运行实例的聚合计数与状态 */
    private void recalculateRun(Long runId) {
        List<DqRuleResult> rows = dqRuleResultMapper.selectList(new LambdaQueryWrapper<DqRuleResult>()
                .eq(DqRuleResult::getRunId, runId));
        int pass = 0;
        int warn = 0;
        int error = 0;
        int failed = 0;
        long checkTotal = 0L;
        long failTotal = 0L;
        for (DqRuleResult row : rows) {
            QualityCheckStatus status = QualityCheckStatus.fromCode(row.getStatus());
            if (status == null) {
                continue;
            }
            switch (status) {
                case PASS -> pass++;
                case WARN -> warn++;
                case ERROR -> error++;
                case FAILED -> failed++;
            }
            checkTotal += toLong(row.getMetricValue());
            if (status.isNotPassed()) {
                failTotal += toLong(row.getMetricValue());
            }
        }
        DqRunInstance update = new DqRunInstance();
        update.setId(runId);
        update.setPassCount(pass);
        update.setWarnCount(warn);
        update.setErrorCount(error);
        update.setFailedCount(failed);
        update.setCheckRecordCount(checkTotal);
        update.setFailRecordCount(failTotal);
        update.setStatus(DqBatchSupport.resolveRunStatus(failed > 0, error > 0, false));
        dqRunInstanceMapper.updateById(update);
    }

    // ==================================================================
    // 映射
    // ==================================================================

    public static QualityRunInstanceVO toVO(DqRunInstance instance) {
        QualityRunInstanceVO vo = new QualityRunInstanceVO();
        vo.setId(instance.getId());
        vo.setTaskId(instance.getTaskId());
        vo.setTaskName(instance.getTaskName());
        vo.setBatchNo(instance.getBatchNo());
        vo.setTriggerType(instance.getTriggerType());
        vo.setScheduleInstanceId(instance.getScheduleInstanceId());
        vo.setStartTime(instance.getStartTime());
        vo.setEndTime(instance.getEndTime());
        vo.setStatus(instance.getStatus());
        vo.setCheckRecordCount(instance.getCheckRecordCount());
        vo.setFailRecordCount(instance.getFailRecordCount());
        vo.setPassCount(instance.getPassCount());
        vo.setWarnCount(instance.getWarnCount());
        vo.setErrorCount(instance.getErrorCount());
        vo.setFailedCount(instance.getFailedCount());
        return vo;
    }

    public static QualityRuleResultVO toVO(DqRuleResult row) {
        QualityRuleResultVO vo = new QualityRuleResultVO();
        vo.setId(row.getId());
        vo.setRunId(row.getRunId());
        vo.setRuleId(row.getRuleId());
        vo.setRuleName(row.getRuleName());
        vo.setRuleType(row.getRuleType());
        vo.setCheckObject(row.getCheckObject());
        vo.setThreshold(row.getThreshold());
        vo.setActualValue(row.getActualValue());
        vo.setStatus(row.getStatus());
        vo.setSeverity(row.getSeverity());
        vo.setExecSql(row.getExecSql());
        vo.setErrorMsg(row.getErrorMsg());
        vo.setCostMs(row.getCostMs());
        return vo;
    }

    /** 供大盘复用：按时间窗取运行实例 */
    List<DqRunInstance> listRunsBetween(LocalDateTime from, LocalDateTime to, int limit) {
        LambdaQueryWrapper<DqRunInstance> wrapper = new LambdaQueryWrapper<>();
        wrapper.ge(DqRunInstance::getStartTime, from)
                .lt(DqRunInstance::getStartTime, to)
                .orderByDesc(DqRunInstance::getId);
        return dqRunInstanceMapper.selectList(wrapper.last("LIMIT " + limit));
    }

    /** 供大盘复用：取指定运行下的未通过结果 */
    List<DqRuleResult> listUnpassedResults(List<Long> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return List.of();
        }
        return dqRuleResultMapper.selectList(new LambdaQueryWrapper<DqRuleResult>()
                .in(DqRuleResult::getRunId, runIds)
                .ne(DqRuleResult::getStatus, QualityCheckStatus.PASS.getCode()));
    }

    /** 供大盘复用：按 id 取规则，用于把结果映射回「库.表」与负责人 */
    Map<Long, DqRule> mapRules(List<Long> ruleIds) {
        if (ruleIds == null || ruleIds.isEmpty()) {
            return Map.of();
        }
        List<DqRule> rules = dqRuleMapper.selectBatchIds(new LinkedHashSet<>(ruleIds));
        Map<Long, DqRule> byId = new LinkedHashMap<>();
        for (DqRule rule : rules) {
            byId.put(rule.getId(), rule);
        }
        return byId;
    }

    /** 供大盘复用：按任务取处置策略，判断哪些失败属于「阻断」 */
    Map<Long, String> mapTaskPolicies(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return Map.of();
        }
        List<DqTask> tasks = dqTaskMapper.selectBatchIds(new LinkedHashSet<>(taskIds));
        Map<Long, String> policies = new LinkedHashMap<>();
        for (DqTask task : tasks) {
            policies.put(task.getId(), task.getDisposePolicy());
        }
        return policies;
    }

    /** 当前用户是否可读某张表的元数据（供跨模块调用方做 fail-close 判断） */
    boolean hasTableReadAccess(String datasourceName, String databaseName, String tableName) {
        if (permissionService.isAdmin()) {
            return true;
        }
        if (SecurityUtils.getCurrentUserId() == null) {
            return false;
        }
        return permissionService.hasTablePermission(datasourceName, databaseName, tableName,
                DataAccessPermissionService.TABLE_OP_SELECT);
    }

    private static long toLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return new BigDecimal(raw.trim()).longValue();
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String truncate(String raw, int max) {
        if (raw == null) {
            return null;
        }
        return raw.length() <= max ? raw : raw.substring(0, max - 3) + "...";
    }

    private static Set<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private static String joinCsv(Set<String> values) {
        return String.join(",", values);
    }
}
