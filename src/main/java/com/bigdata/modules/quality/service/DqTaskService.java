package com.bigdata.modules.quality.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.quality.dto.QualityTaskSaveDTO;
import com.bigdata.modules.quality.entity.DqRule;
import com.bigdata.modules.quality.entity.DqTask;
import com.bigdata.modules.quality.entity.DqTaskRuleRel;
import com.bigdata.modules.quality.enums.QualityDisposeMode;
import com.bigdata.modules.quality.enums.QualityTriggerType;
import com.bigdata.modules.quality.mapper.DqTaskMapper;
import com.bigdata.modules.quality.mapper.DqTaskRuleRelMapper;
import com.bigdata.modules.quality.vo.QualityTaskVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 质检任务 Service —— 「一组规则 + 触发方式 + 处置策略」的执行计划。
 *
 * <p>任务与规则是<b>多对多</b>（dq_task_rule_rel）：同一条规则可以挂到多个任务上
 * （例如「每日质检」与「发布前质检」共用同一批规则），因此删除规则时要先检查是否被引用，
 * 删除任务则连带清理关联关系。</p>
 */
@Service
public class DqTaskService {

    private static final int TASK_STATUS_ENABLED = 1;

    @Autowired
    private DqTaskMapper dqTaskMapper;

    @Autowired
    private DqTaskRuleRelMapper dqTaskRuleRelMapper;

    @Autowired
    private DqRuleService dqRuleService;

    @Autowired
    private DqRunService dqRunService;

    // ==================================================================
    // 查询
    // ==================================================================

    public Page<QualityTaskVO> page(int current, int size, String keyword, String triggerType, Boolean enabled) {
        LambdaQueryWrapper<DqTask> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(DqTask::getTaskName, kw)
                    .or().like(DqTask::getOwnerName, kw)
                    .or().like(DqTask::getTableName, kw));
        }
        if (triggerType != null && !triggerType.isBlank()) {
            wrapper.eq(DqTask::getTriggerType, triggerType.trim());
        }
        if (enabled != null) {
            wrapper.eq(DqTask::getEnabled, enabled ? TASK_STATUS_ENABLED : 0);
        }
        wrapper.orderByDesc(DqTask::getId);

        Page<DqTask> result = dqTaskMapper.selectPage(new Page<>(current, size), wrapper);
        Page<QualityTaskVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(DqTaskService::toVO).collect(Collectors.toList()));
        return voPage;
    }

    public DqTask getById(Long id) {
        if (id == null) {
            throw new BusinessException("任务 ID 不能为空");
        }
        DqTask task = dqTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException("质检任务不存在: " + id);
        }
        return task;
    }

    // ==================================================================
    // 写操作
    // ==================================================================

    @Transactional
    public Long create(QualityTaskSaveDTO dto) {
        DqTask task = new DqTask();
        applyDto(task, dto, true);
        dqTaskMapper.insert(task);
        replaceRuleBindings(task.getId(), dto.getRuleIds());
        return task.getId();
    }

    @Transactional
    public void update(QualityTaskSaveDTO dto) {
        if (dto.getId() == null) {
            throw new BusinessException("任务 ID 不能为空");
        }
        DqTask existing = getById(dto.getId());
        applyDto(existing, dto, false);
        dqTaskMapper.updateById(existing);
        if (dto.getRuleIds() != null) {
            replaceRuleBindings(existing.getId(), dto.getRuleIds());
        }
    }

    @Transactional
    public void delete(Long id) {
        getById(id);
        dqTaskMapper.deleteById(id);
        // 关联关系一并物理删除：留着会让「规则是否被任务引用」的判断出错
        dqTaskRuleRelMapper.delete(new LambdaQueryWrapper<DqTaskRuleRel>()
                .eq(DqTaskRuleRel::getTaskId, id));
    }

    /** 启停任务：停用后调度侧不再触发，但历史运行记录保留 */
    public void setEnabled(Long id, boolean enabled) {
        DqTask existing = getById(id);
        DqTask update = new DqTask();
        update.setId(existing.getId());
        update.setEnabled(enabled ? TASK_STATUS_ENABLED : 0);
        dqTaskMapper.updateById(update);
    }

    /**
     * 手动触发一次执行。
     *
     * @return 运行实例 id，供前端随后轮询运行结果
     */
    public Long run(Long taskId) {
        DqTask task = getById(taskId);
        if (task.getEnabled() != null && task.getEnabled() != TASK_STATUS_ENABLED) {
            throw new BusinessException("任务「" + task.getTaskName() + "」已停用，请先启用后再执行");
        }
        List<DqRule> rules = resolveRules(taskId);
        if (rules.isEmpty()) {
            throw new BusinessException("任务「" + task.getTaskName() + "」下没有可执行的规则，请先挂载监控规则");
        }
        return dqRunService.startRun(task, rules, QualityTriggerType.MANUAL, null, null);
    }

    /** 解析任务挂载的规则（仅启用中的规则参与执行） */
    public List<DqRule> resolveRules(Long taskId) {
        List<DqTaskRuleRel> rels = dqTaskRuleRelMapper.selectList(new LambdaQueryWrapper<DqTaskRuleRel>()
                .eq(DqTaskRuleRel::getTaskId, taskId)
                .orderByAsc(DqTaskRuleRel::getId));
        List<Long> ruleIds = rels.stream().map(DqTaskRuleRel::getRuleId).collect(Collectors.toList());
        return dqRuleService.listByIds(ruleIds).stream()
                .filter(rule -> rule.getEnabled() == null || rule.getEnabled() == TASK_STATUS_ENABLED)
                .collect(Collectors.toList());
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /** 整体替换任务的规则绑定（差集增删，避免整表删除再重建导致关联 id 抖动） */
    private void replaceRuleBindings(Long taskId, List<Long> ruleIds) {
        Set<Long> target = ruleIds == null ? new HashSet<>()
                : ruleIds.stream().filter(java.util.Objects::nonNull).collect(Collectors.toCollection(HashSet::new));

        List<DqTaskRuleRel> current = dqTaskRuleRelMapper.selectList(new LambdaQueryWrapper<DqTaskRuleRel>()
                .eq(DqTaskRuleRel::getTaskId, taskId));
        Set<Long> existing = current.stream().map(DqTaskRuleRel::getRuleId).collect(Collectors.toSet());

        for (DqTaskRuleRel rel : current) {
            if (!target.contains(rel.getRuleId())) {
                dqTaskRuleRelMapper.deleteById(rel.getId());
            }
        }
        for (Long ruleId : target) {
            if (existing.contains(ruleId)) {
                continue;
            }
            // 规则必须真实存在，否则任务会在执行时才报「规则不存在」，报错点离配置点太远
            dqRuleService.getById(ruleId);
            DqTaskRuleRel rel = new DqTaskRuleRel();
            rel.setTaskId(taskId);
            rel.setRuleId(ruleId);
            dqTaskRuleRelMapper.insert(rel);
        }
    }

    private void applyDto(DqTask task, QualityTaskSaveDTO dto, boolean isNew) {
        if (dto == null) {
            throw new BusinessException("任务内容不能为空");
        }
        if (isNew && dto.getId() != null) {
            throw new BusinessException("新增任务不应携带 id");
        }
        if (dto.getTaskName() == null || dto.getTaskName().isBlank()) {
            throw new BusinessException("任务名称不能为空");
        }
        QualityTriggerType triggerType = QualityTriggerType.fromCode(dto.getTriggerType());
        if (triggerType == null) {
            throw new BusinessException("触发方式只能是 SCHEDULE（调度触发）或 MANUAL（手动触发）");
        }
        if (triggerType == QualityTriggerType.SCHEDULE
                && (dto.getProjectCode() == null || dto.getProjectCode().isBlank())) {
            throw new BusinessException("调度触发的任务必须指定所在调度项目编码");
        }

        task.setTaskName(dto.getTaskName().trim());
        task.setOwnerName(trimToNull(dto.getOwnerName()));
        task.setDescription(trimToNull(dto.getDescription()));
        task.setDatasourceId(dto.getDatasourceId());
        task.setDatasourceName(trimToNull(dto.getDatasourceName()));
        task.setDatabaseName(trimToNull(dto.getDatabaseName()));
        task.setTableName(trimToNull(dto.getTableName()));
        task.setTriggerType(triggerType.getCode());
        task.setProjectCode(trimToNull(dto.getProjectCode()));
        task.setWorkflowCode(dto.getWorkflowCode());
        task.setWorkflowName(trimToNull(dto.getWorkflowName()));
        task.setNodeName(trimToNull(dto.getNodeName()));

        QualityDisposeMode policy = QualityDisposeMode.fromCode(dto.getDisposePolicy());
        task.setDisposePolicy(policy == null ? QualityDisposeMode.ALERT.getCode() : policy.getCode());
        task.setEnabled(Boolean.FALSE.equals(dto.getEnabled()) ? 0 : TASK_STATUS_ENABLED);
    }

    static QualityTaskVO toVO(DqTask task) {
        QualityTaskVO vo = new QualityTaskVO();
        vo.setId(task.getId());
        vo.setTaskName(task.getTaskName());
        vo.setOwnerName(task.getOwnerName());
        vo.setDescription(task.getDescription());
        vo.setDatasourceId(task.getDatasourceId());
        vo.setDatasourceName(task.getDatasourceName());
        vo.setDatabaseName(task.getDatabaseName());
        vo.setTableName(task.getTableName());
        vo.setTriggerType(task.getTriggerType());
        vo.setProjectCode(task.getProjectCode());
        vo.setWorkflowCode(task.getWorkflowCode());
        vo.setWorkflowName(task.getWorkflowName());
        vo.setNodeName(task.getNodeName());
        vo.setDisposePolicy(task.getDisposePolicy());
        vo.setEnabled(task.getEnabled() == null || task.getEnabled() == TASK_STATUS_ENABLED);
        vo.setCreateTime(task.getCreateTime());
        vo.setUpdateTime(task.getUpdateTime());
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
