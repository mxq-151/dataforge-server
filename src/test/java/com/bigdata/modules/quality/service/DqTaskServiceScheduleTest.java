package com.bigdata.modules.quality.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.quality.entity.DqRule;
import com.bigdata.modules.quality.entity.DqTask;
import com.bigdata.modules.quality.entity.DqTaskRuleRel;
import com.bigdata.modules.quality.enums.QualityTriggerType;
import com.bigdata.modules.quality.mapper.DqTaskMapper;
import com.bigdata.modules.quality.mapper.DqTaskRuleRelMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 调度触发质检任务（{@code POST /quality/task/{id}/run-by-schedule}）。
 *
 * <p>这是 DolphinScheduler 节点回调平台的入口。锁两件事：</p>
 * <ol>
 *   <li><b>触发来源要记对</b>：SCHEDULE + 调度实例号 + 业务批次，否则运行记录里分不清
 *       是"人点的"还是"调度跑的"，也跳不回对应调度实例；</li>
 *   <li><b>只接受调度触发的任务</b>：任务上的 triggerType 就是用来区分谁来触发的，
 *       配置成手动的任务被调度悄悄驱动等于配置形同虚设。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("调度触发质检任务")
class DqTaskServiceScheduleTest {

    @Mock
    private DqTaskMapper dqTaskMapper;
    @Mock
    private DqTaskRuleRelMapper dqTaskRuleRelMapper;
    @Mock
    private DqRuleService dqRuleService;
    @Mock
    private DqRunService dqRunService;

    private DqTaskService service;

    @BeforeEach
    void setUp() {
        service = new DqTaskService();
        ReflectionTestUtils.setField(service, "dqTaskMapper", dqTaskMapper);
        ReflectionTestUtils.setField(service, "dqTaskRuleRelMapper", dqTaskRuleRelMapper);
        ReflectionTestUtils.setField(service, "dqRuleService", dqRuleService);
        ReflectionTestUtils.setField(service, "dqRunService", dqRunService);

        DqTask task = new DqTask();
        task.setId(9L);
        task.setTaskName("广告域质检");
        task.setTriggerType(QualityTriggerType.SCHEDULE.getCode());
        task.setEnabled(1);
        when(dqTaskMapper.selectById(9L)).thenReturn(task);

        DqTaskRuleRel rel = new DqTaskRuleRel();
        rel.setTaskId(9L);
        rel.setRuleId(21L);
        when(dqTaskRuleRelMapper.selectList(any())).thenReturn(List.of(rel));

        DqRule rule = new DqRule();
        rule.setId(21L);
        rule.setEnabled(1);
        when(dqRuleService.listByIds(anyList())).thenReturn(List.of(rule));

        when(dqRunService.startRun(any(), anyList(), any(), any(), any())).thenReturn(77L);
    }

    @Test
    @DisplayName("调度触发：以 SCHEDULE 类型执行，并带上调度实例号与批次")
    void scheduleTriggerCarriesInstanceAndBatch() {
        Long runId = service.runBySchedule(9L, 12345L, "20261006");

        assertEquals(77L, runId);
        verify(dqRunService).startRun(any(DqTask.class), anyList(),
                org.mockito.ArgumentMatchers.eq(QualityTriggerType.SCHEDULE),
                org.mockito.ArgumentMatchers.eq(12345L),
                org.mockito.ArgumentMatchers.eq("20261006"));
    }

    @Test
    @DisplayName("配置为手动触发的任务被调度触发时明确拒绝，并指出该改哪里")
    void manualTaskIsRejected() {
        DqTask manual = new DqTask();
        manual.setId(9L);
        manual.setTaskName("广告域质检");
        manual.setTriggerType(QualityTriggerType.MANUAL.getCode());
        manual.setEnabled(1);
        when(dqTaskMapper.selectById(9L)).thenReturn(manual);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.runBySchedule(9L, 1L, "20261006"));
        assertTrue(ex.getMessage().contains("调度触发"));
        verify(dqRunService, never()).startRun(any(), anyList(), any(), any(), any());
    }

    @Test
    @DisplayName("停用的任务不执行")
    void disabledTaskIsRejected() {
        DqTask disabled = new DqTask();
        disabled.setId(9L);
        disabled.setTaskName("广告域质检");
        disabled.setTriggerType(QualityTriggerType.SCHEDULE.getCode());
        disabled.setEnabled(0);
        when(dqTaskMapper.selectById(9L)).thenReturn(disabled);

        assertThrows(BusinessException.class, () -> service.runBySchedule(9L, 1L, "20261006"));
        verify(dqRunService, never()).startRun(any(), anyList(), any(), any(), any());
    }

    @Test
    @DisplayName("任务下没有可执行规则时不执行")
    void emptyRulesIsRejected() {
        when(dqTaskRuleRelMapper.selectList(any())).thenReturn(List.of());
        // anyList() 也匹配空列表，所以这里必须显式覆盖 setUp 里的默认桩
        when(dqRuleService.listByIds(anyList())).thenReturn(List.of());

        assertThrows(BusinessException.class, () -> service.runBySchedule(9L, 1L, "20261006"));
        verify(dqRunService, never()).startRun(any(), anyList(), any(), any(), any());
    }

    @Test
    @DisplayName("调度实例号与批次可省略（仅在调度器拿不到时）")
    void scheduleIdsAreOptional() {
        service.runBySchedule(9L, null, null);

        verify(dqRunService).startRun(any(DqTask.class), anyList(),
                org.mockito.ArgumentMatchers.eq(QualityTriggerType.SCHEDULE),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull());
    }
}
