package com.bigdata.modules.quality.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.quality.entity.DqAlertLog;
import com.bigdata.modules.quality.entity.DqAlertSubscription;
import com.bigdata.modules.quality.entity.DqRuleResult;
import com.bigdata.modules.quality.entity.DqTask;
import com.bigdata.modules.quality.enums.QualityCheckStatus;
import com.bigdata.modules.quality.mapper.DqAlertLogMapper;
import com.bigdata.modules.quality.mapper.DqAlertSubscriptionMapper;
import com.bigdata.modules.quality.mapper.DqRuleResultMapper;
import com.bigdata.modules.quality.mapper.DqTaskMapper;
import com.bigdata.modules.quality.vo.QualityAlertLogVO;
import com.bigdata.modules.quality.vo.QualityAlertSubscriptionVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 告警订阅 Service。
 *
 * <p>订阅是「哪个任务的哪些状态要通知谁」的配置；实际发送记录落在 dq_alert_log，
 * 由 {@link DqRunService} 在运行结束时按订阅条件登记。</p>
 *
 * <p>这里对 {@code notifyOn} 做<b>取值白名单校验</b>：它决定哪些异常会触发通知，
 * 写错一个字符串（例如 {@code ERROE}）不会报错，只会让告警永远不触发 ——
 * 这类「静默失效」在告警配置里最危险，因此在保存时就挡住。</p>
 */
@Service
public class DqAlertService {

    @Autowired
    private DqAlertSubscriptionMapper dqAlertSubscriptionMapper;

    @Autowired
    private DqTaskMapper dqTaskMapper;

    @Autowired
    private DqAlertLogMapper dqAlertLogMapper;

    @Autowired
    private DqRuleResultMapper dqRuleResultMapper;

    /**
     * 站内告警箱：分页查告警记录。
     *
     * <p>「站内记录」渠道落表即送达，所以这张表本身就是可用的告警箱 ——
     * 在补上这个查询入口之前，{@code dq_alert_log} 写进去却没有任何界面能看到。</p>
     *
     * <p>触发状态（WARN / ERROR / FAILED）不在告警表里，按 {@code runId + ruleId} 回校验结果表取，
     * 一次 {@code in (runIds)} 批量查询而不是逐行查，避免 N+1。</p>
     */
    public Page<QualityAlertLogVO> pageLogs(int current, int size, Long taskId, String channel, String status) {
        LambdaQueryWrapper<DqAlertLog> wrapper = new LambdaQueryWrapper<>();
        if (taskId != null) {
            wrapper.eq(DqAlertLog::getTaskId, taskId);
        }
        if (channel != null && !channel.isBlank()) {
            wrapper.eq(DqAlertLog::getChannel, channel.trim().toUpperCase());
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(DqAlertLog::getStatus, status.trim().toUpperCase());
        }
        wrapper.orderByDesc(DqAlertLog::getId);

        Page<DqAlertLog> result = dqAlertLogMapper.selectPage(new Page<>(current, size), wrapper);
        List<DqAlertLog> records = result.getRecords();

        Map<Long, String> taskNames = taskNamesOf(records.stream()
                .map(DqAlertLog::getTaskId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList()));
        Map<String, String> checkStatuses = checkStatusesOf(records);

        Page<QualityAlertLogVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(records.stream()
                .map(row -> toLogVO(row, taskNames.get(row.getTaskId()),
                        resolveCheckStatus(row, checkStatuses)))
                .collect(Collectors.toList()));
        return voPage;
    }

    private Map<Long, String> taskNamesOf(List<Long> taskIds) {
        Map<Long, String> names = new HashMap<>();
        if (taskIds.isEmpty()) {
            return names;
        }
        for (DqTask task : dqTaskMapper.selectBatchIds(taskIds)) {
            names.put(task.getId(), task.getTaskName());
        }
        return names;
    }

    /** 取这批告警涉及的所有校验结果状态，键为「runId:ruleId」与「runId:name:ruleName」两种 */
    private Map<String, String> checkStatusesOf(List<DqAlertLog> records) {
        Map<String, String> statuses = new HashMap<>();
        List<Long> runIds = records.stream()
                .map(DqAlertLog::getRunId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (runIds.isEmpty()) {
            return statuses;
        }
        for (DqRuleResult row : dqRuleResultMapper.selectList(new LambdaQueryWrapper<DqRuleResult>()
                .in(DqRuleResult::getRunId, runIds))) {
            if (row.getRuleId() != null) {
                statuses.putIfAbsent(row.getRunId() + ":" + row.getRuleId(), row.getStatus());
            }
            if (row.getRuleName() != null) {
                statuses.putIfAbsent(row.getRunId() + ":name:" + row.getRuleName(), row.getStatus());
            }
        }
        return statuses;
    }

    /**
     * 优先按 ruleId 定位触发状态；程序回传的告警可能没有 ruleId（规则未登记时），
     * 此时退回按规则名匹配 —— 与结果回传的服务端反查是同一套退化思路。
     */
    private static String resolveCheckStatus(DqAlertLog row, Map<String, String> statuses) {
        if (row.getRunId() == null) {
            return null;
        }
        if (row.getRuleId() != null) {
            String byId = statuses.get(row.getRunId() + ":" + row.getRuleId());
            if (byId != null) {
                return byId;
            }
        }
        return row.getRuleName() == null ? null
                : statuses.get(row.getRunId() + ":name:" + row.getRuleName());
    }

    static QualityAlertLogVO toLogVO(DqAlertLog entity, String taskName, String checkStatus) {
        QualityAlertLogVO vo = new QualityAlertLogVO();
        vo.setId(entity.getId());
        vo.setRunId(entity.getRunId());
        vo.setTaskId(entity.getTaskId());
        vo.setTaskName(taskName);
        vo.setRuleId(entity.getRuleId());
        vo.setRuleName(entity.getRuleName());
        vo.setChannel(entity.getChannel());
        vo.setReceiver(entity.getReceiver());
        vo.setStatus(entity.getStatus());
        vo.setFailReason(entity.getFailReason());
        vo.setSendTime(entity.getSendTime());
        vo.setCheckStatus(checkStatus);
        return vo;
    }

    public Page<QualityAlertSubscriptionVO> page(int current, int size, Long taskId) {
        LambdaQueryWrapper<DqAlertSubscription> wrapper = new LambdaQueryWrapper<>();
        if (taskId != null) {
            wrapper.eq(DqAlertSubscription::getTaskId, taskId);
        }
        wrapper.orderByDesc(DqAlertSubscription::getId);

        Page<DqAlertSubscription> result = dqAlertSubscriptionMapper.selectPage(new Page<>(current, size), wrapper);
        List<Long> taskIds = result.getRecords().stream()
                .map(DqAlertSubscription::getTaskId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        java.util.Map<Long, String> taskNames = new java.util.HashMap<>();
        if (!taskIds.isEmpty()) {
            for (DqTask task : dqTaskMapper.selectBatchIds(taskIds)) {
                taskNames.put(task.getId(), task.getTaskName());
            }
        }

        Page<QualityAlertSubscriptionVO> voPage =
                new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream()
                .map(row -> toVO(row, taskNames.get(row.getTaskId())))
                .collect(Collectors.toList()));
        return voPage;
    }

    /**
     * 保存订阅（有 id 即更新，否则新建）。
     *
     * <p>同一任务只保留一条订阅：多条订阅会让同一次异常触发多轮通知，
     * 而通知渠道与接收人本来就可以在一条订阅里配置多个（逗号分隔）。</p>
     */
    public void save(QualityAlertSubscriptionVO vo) {
        if (vo == null || vo.getTaskId() == null) {
            throw new BusinessException("告警订阅必须指定质检任务");
        }
        DqTask task = dqTaskMapper.selectById(vo.getTaskId());
        if (task == null) {
            throw new BusinessException("质检任务不存在: " + vo.getTaskId());
        }
        String notifyOn = normalizeNotifyOn(vo.getNotifyOn());
        String channels = trimToNull(vo.getChannels());
        String receivers = trimToNull(vo.getReceivers());
        if (channels == null) {
            throw new BusinessException("请至少配置一个通知渠道");
        }
        if (receivers == null) {
            throw new BusinessException("请至少配置一个接收人");
        }

        DqAlertSubscription entity;
        if (vo.getId() != null) {
            entity = dqAlertSubscriptionMapper.selectById(vo.getId());
            if (entity == null) {
                throw new BusinessException("告警订阅不存在: " + vo.getId());
            }
        } else {
            long existing = dqAlertSubscriptionMapper.selectCount(new LambdaQueryWrapper<DqAlertSubscription>()
                    .eq(DqAlertSubscription::getTaskId, vo.getTaskId()));
            if (existing > 0) {
                throw new BusinessException("该任务已存在告警订阅；多条订阅会让同一次异常重复通知，"
                        + "请在已有订阅里追加渠道与接收人");
            }
            entity = new DqAlertSubscription();
        }
        entity.setTaskId(vo.getTaskId());
        entity.setNotifyOn(notifyOn);
        entity.setChannels(channels);
        entity.setReceivers(receivers);
        entity.setEnabled(Boolean.FALSE.equals(vo.getEnabled()) ? 0 : 1);

        if (entity.getId() == null) {
            dqAlertSubscriptionMapper.insert(entity);
        } else {
            dqAlertSubscriptionMapper.updateById(entity);
        }
    }

    /**
     * 校验并归一化触发状态集合。
     *
     * <p>只接受 WARN / ERROR / FAILED 三种 —— PASS 作为触发条件没有意义
     * （那等于每次运行都告警）。</p>
     */
    static String normalizeNotifyOn(String raw) {
        if (raw == null || raw.isBlank()) {
            return QualityCheckStatus.ERROR.getCode() + "," + QualityCheckStatus.FAILED.getCode();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            QualityCheckStatus status = QualityCheckStatus.fromCode(trimmed);
            if (status == null) {
                throw new BusinessException("无法识别的告警触发状态: " + trimmed
                        + "（只能是 WARN / ERROR / FAILED）");
            }
            if (status == QualityCheckStatus.PASS) {
                throw new BusinessException("PASS 不能作为告警触发条件（那等于每次运行都告警）");
            }
            normalized.add(status.getCode());
        }
        if (normalized.isEmpty()) {
            throw new BusinessException("告警触发状态不能为空");
        }
        return String.join(",", normalized);
    }

    static QualityAlertSubscriptionVO toVO(DqAlertSubscription entity, String taskName) {
        QualityAlertSubscriptionVO vo = new QualityAlertSubscriptionVO();
        vo.setId(entity.getId());
        vo.setTaskId(entity.getTaskId());
        vo.setTaskName(taskName);
        vo.setNotifyOn(entity.getNotifyOn());
        vo.setChannels(entity.getChannels());
        vo.setReceivers(entity.getReceivers());
        vo.setEnabled(entity.getEnabled() == null || entity.getEnabled() == 1);
        return vo;
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
