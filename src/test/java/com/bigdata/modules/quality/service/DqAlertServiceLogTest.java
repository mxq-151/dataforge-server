package com.bigdata.modules.quality.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.modules.quality.entity.DqAlertLog;
import com.bigdata.modules.quality.entity.DqRuleResult;
import com.bigdata.modules.quality.entity.DqTask;
import com.bigdata.modules.quality.mapper.DqAlertLogMapper;
import com.bigdata.modules.quality.mapper.DqRuleResultMapper;
import com.bigdata.modules.quality.mapper.DqTaskMapper;
import com.bigdata.modules.quality.vo.QualityAlertLogVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 站内告警箱取数。
 *
 * <p>最值得锁的是<b>触发状态的解析</b>：告警表里没有"因为什么告警"这一列，
 * 服务端按 {@code runId + ruleId} 回校验结果表取。程序回传的告警可能没有 ruleId
 * （规则未登记时），这时要退回按规则名匹配 —— 否则界面只能显示"某条规则告警了"，
 * 看不出严重程度。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("站内告警箱取数：触发状态解析与任务名回填")
class DqAlertServiceLogTest {

    @Mock
    private DqAlertLogMapper dqAlertLogMapper;
    @Mock
    private DqRuleResultMapper dqRuleResultMapper;
    @Mock
    private DqTaskMapper dqTaskMapper;

    private DqAlertService service;

    @BeforeEach
    void setUp() {
        service = new DqAlertService();
        ReflectionTestUtils.setField(service, "dqAlertLogMapper", dqAlertLogMapper);
        ReflectionTestUtils.setField(service, "dqRuleResultMapper", dqRuleResultMapper);
        ReflectionTestUtils.setField(service, "dqTaskMapper", dqTaskMapper);
    }

    private DqAlertLog alert(Long id, Long runId, Long ruleId, String ruleName) {
        DqAlertLog log = new DqAlertLog();
        log.setId(id);
        log.setRunId(runId);
        log.setTaskId(9L);
        log.setRuleId(ruleId);
        log.setRuleName(ruleName);
        log.setChannel("INBOX");
        log.setReceiver("zhangsan");
        log.setStatus("SENT");
        log.setSendTime(LocalDateTime.of(2026, 10, 7, 8, 0));
        return log;
    }

    private void stubPage(DqAlertLog... rows) {
        Page<DqAlertLog> page = new Page<>(1, 20);
        page.setRecords(List.of(rows));
        when(dqAlertLogMapper.selectPage(any(), any())).thenReturn(page);
    }

    @Test
    @DisplayName("按 runId + ruleId 取触发状态，并回填任务名")
    void resolvesCheckStatusByRuleId() {
        stubPage(alert(1L, 100L, 21L, "R_AD_001 事件主键唯一"));
        DqRuleResult result = new DqRuleResult();
        result.setRunId(100L);
        result.setRuleId(21L);
        result.setRuleName("R_AD_001 事件主键唯一");
        result.setStatus("ERROR");
        when(dqRuleResultMapper.selectList(any())).thenReturn(List.of(result));
        DqTask task = new DqTask();
        task.setId(9L);
        task.setTaskName("广告域质检");
        when(dqTaskMapper.selectBatchIds(any())).thenReturn(List.of(task));

        QualityAlertLogVO vo = service.pageLogs(1, 20, null, null, null).getRecords().get(0);

        assertEquals("ERROR", vo.getCheckStatus());
        assertEquals("广告域质检", vo.getTaskName());
        assertEquals("SENT", vo.getStatus());
    }

    @Test
    @DisplayName("告警没有 ruleId（规则未登记）时退回按规则名匹配")
    void fallsBackToRuleName() {
        stubPage(alert(2L, 100L, null, "R_AD_002 金额非空"));
        DqRuleResult result = new DqRuleResult();
        result.setRunId(100L);
        result.setRuleId(22L);
        result.setRuleName("R_AD_002 金额非空");
        result.setStatus("WARN");
        when(dqRuleResultMapper.selectList(any())).thenReturn(List.of(result));

        QualityAlertLogVO vo = service.pageLogs(1, 20, null, null, null).getRecords().get(0);

        assertEquals("WARN", vo.getCheckStatus(), "靠规则名也要能取到触发状态");
    }

    @Test
    @DisplayName("校验结果已被清理时触发状态为空，但记录本身照常展示")
    void missingResultKeepsRow() {
        stubPage(alert(3L, 100L, 99L, "R_AD_099 已删除的规则"));
        when(dqRuleResultMapper.selectList(any())).thenReturn(List.of());
        when(dqTaskMapper.selectBatchIds(any())).thenReturn(List.of());

        QualityAlertLogVO vo = service.pageLogs(1, 20, null, null, null).getRecords().get(0);

        assertNull(vo.getCheckStatus());
        assertEquals("R_AD_099 已删除的规则", vo.getRuleName(), "结果自包含，规则删了也还能看到当时告警过谁");
        assertNull(vo.getTaskName());
    }
}
