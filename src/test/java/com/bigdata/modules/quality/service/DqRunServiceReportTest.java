package com.bigdata.modules.quality.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.security.CustomUserDetails;
import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.modules.quality.dto.QualityRunReportDTO;
import com.bigdata.modules.quality.entity.DqAlertLog;
import com.bigdata.modules.quality.entity.DqAlertSubscription;
import com.bigdata.modules.quality.entity.DqRule;
import com.bigdata.modules.quality.entity.DqRuleResult;
import com.bigdata.modules.quality.entity.DqRunInstance;
import com.bigdata.modules.quality.entity.DqTask;
import com.bigdata.modules.quality.entity.DqTaskRuleRel;
import com.bigdata.modules.quality.mapper.DqAlertLogMapper;
import com.bigdata.modules.quality.mapper.DqAlertSubscriptionMapper;
import com.bigdata.modules.quality.mapper.DqRuleMapper;
import com.bigdata.modules.quality.mapper.DqRuleResultMapper;
import com.bigdata.modules.quality.mapper.DqRunInstanceMapper;
import com.bigdata.modules.quality.mapper.DqTaskMapper;
import com.bigdata.modules.quality.mapper.DqTaskRuleRelMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 质检结果回传（{@code POST /quality/run/report}）落库语义。
 *
 * <p>锁四件事：</p>
 * <ol>
 *   <li><b>规则关联</b>：显式 {@code ruleId} 优先，否则按「规则名 + 库 + 表」反查，查不到落 null 不报错
 *       —— 环比基线是按 {@code rule_id} 取的，断链后「行数波动 / 行数环比」永远取不到程序回传的数据；</li>
 *   <li><b>表级读权限 fail-close</b>：回传是写入行为，能写自己读不到的表的结果就等于可以伪造它；</li>
 *   <li><b>任务归属</b>：由规则反查任务，唯一绑定才认，多个绑定不猜（猜错会让告警发给错的人）；</li>
 *   <li><b>告警登记</b>：程序回传路径此前完全不登记告警，补上；渠道语义 INBOX 落表即送达。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("质检结果回传：rule_id / exec_sql / 权限 / 任务归属 / 告警")
class DqRunServiceReportTest {

    @Mock
    private DqRunInstanceMapper dqRunInstanceMapper;
    @Mock
    private DqRuleResultMapper dqRuleResultMapper;
    @Mock
    private DqRuleMapper dqRuleMapper;
    @Mock
    private DqTaskMapper dqTaskMapper;
    @Mock
    private DqTaskRuleRelMapper dqTaskRuleRelMapper;
    @Mock
    private DqAlertSubscriptionMapper dqAlertSubscriptionMapper;
    @Mock
    private DqAlertLogMapper dqAlertLogMapper;
    @Mock
    private DqAlertSender dqAlertSender;
    @Mock
    private DataAccessPermissionService permissionService;

    private DqRunService service;

    @BeforeEach
    void setUp() {
        service = new DqRunService();
        ReflectionTestUtils.setField(service, "dqRunInstanceMapper", dqRunInstanceMapper);
        ReflectionTestUtils.setField(service, "dqRuleResultMapper", dqRuleResultMapper);
        ReflectionTestUtils.setField(service, "dqRuleMapper", dqRuleMapper);
        ReflectionTestUtils.setField(service, "dqTaskMapper", dqTaskMapper);
        ReflectionTestUtils.setField(service, "dqTaskRuleRelMapper", dqTaskRuleRelMapper);
        ReflectionTestUtils.setField(service, "dqAlertSubscriptionMapper", dqAlertSubscriptionMapper);
        ReflectionTestUtils.setField(service, "dqAlertLogMapper", dqAlertLogMapper);
        ReflectionTestUtils.setField(service, "dqAlertSender", dqAlertSender);
        ReflectionTestUtils.setField(service, "permissionService", permissionService);

        // 默认按管理员放行：权限本身另有专项用例，其余用例不该被它绊住
        when(permissionService.isAdmin()).thenReturn(true);

        // 回传时先找可复用的 RUNNING 实例；这里让主键回填，模拟 insert 成功
        doAnswer(inv -> {
            ((DqRunInstance) inv.getArgument(0)).setId(99L);
            return 1;
        }).when(dqRunInstanceMapper).insert(any(DqRunInstance.class));
    }

    private QualityRunReportDTO baseDto() {
        QualityRunReportDTO dto = new QualityRunReportDTO();
        dto.setDatasourceName("hive-prod");
        dto.setDatabaseName("test_db");
        dto.setTableName("dwd_event_f");
        dto.setBatchNo("20261006");
        dto.setRuleName("R_AD_001 事件主键唯一");
        dto.setRuleType("PRIMARY_KEY_UNIQUE");
        dto.setCheckObject("test_db.dwd_event_f.event_id");
        dto.setThreshold("重复=0");
        dto.setActualValue("重复 3 条");
        dto.setPassed(false);
        return dto;
    }

    private DqRuleResult capturedRow() {
        ArgumentCaptor<DqRuleResult> captor = ArgumentCaptor.forClass(DqRuleResult.class);
        verify(dqRuleResultMapper).insert(captor.capture());
        return captor.getValue();
    }

    private void bindSingleTask(Long ruleId, Long taskId) {
        DqTaskRuleRel rel = new DqTaskRuleRel();
        rel.setTaskId(taskId);
        rel.setRuleId(ruleId);
        when(dqTaskRuleRelMapper.selectList(any())).thenReturn(List.of(rel));
        DqTask task = new DqTask();
        task.setId(taskId);
        task.setTaskName("广告域质检");
        when(dqTaskMapper.selectById(taskId)).thenReturn(task);
    }

    @Nested
    @DisplayName("规则关联")
    class RuleResolution {

        @Test
        @DisplayName("显式传入 ruleId 时直接采用，不再反查")
        void explicitRuleIdWins() {
            QualityRunReportDTO dto = baseDto();
            dto.setRuleId(7L);

            service.reportRun(dto);

            assertEquals(7L, capturedRow().getRuleId());
            verify(dqRuleMapper, never()).selectList(any());
        }

        @Test
        @DisplayName("未传 ruleId 时按「规则名 + 库 + 表」反查回填")
        void fallsBackToNameLookup() {
            DqRule rule = new DqRule();
            rule.setId(21L);
            rule.setRuleName("R_AD_001 事件主键唯一");
            when(dqRuleMapper.selectList(any())).thenReturn(List.of(rule));

            service.reportRun(baseDto());

            assertEquals(21L, capturedRow().getRuleId());
        }

        @Test
        @DisplayName("查不到规则时 ruleId 落空，但回传本身仍然成功")
        void unmatchedRuleKeepsReportUsable() {
            service.reportRun(baseDto());

            DqRuleResult row = capturedRow();
            assertNull(row.getRuleId(), "规则未登记时不该伪造 rule_id");
            assertEquals("R_AD_001 事件主键唯一", row.getRuleName(), "规则名仍须落库，结果自包含");
            assertEquals("ERROR", row.getStatus());
        }
    }

    @Nested
    @DisplayName("表级读权限（fail-close）")
    class TablePermission {

        /**
         * 造一个登录态。
         *
         * <p>{@code hasTableReadAccess} 的第一道闸是「有没有登录」。不设置 SecurityContext 时
         * {@code SecurityUtils.getCurrentUserId()} 返回 null，判定会停在"未登录"分支上 ——
         * 那样测的其实是未登录，而不是"已登录但没有表权限"。所以两条非管理员用例都要先造出登录态。</p>
         */
        private void loginAs(Long userId) {
            CustomUserDetails details = new CustomUserDetails();
            details.setUserId(userId);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(details, null, List.of()));
        }

        @AfterEach
        void clearContext() {
            SecurityContextHolder.clearContext();
        }

        @Test
        @DisplayName("管理员放行")
        void adminPasses() {
            service.reportRun(baseDto());

            assertEquals("R_AD_001 事件主键唯一", capturedRow().getRuleName());
        }

        @Test
        @DisplayName("已登录但无表权限时拒绝落库，且不写任何结果行")
        void nonAdminWithoutPermissionIsRejected() {
            loginAs(5L);
            when(permissionService.isAdmin()).thenReturn(false);
            when(permissionService.hasTablePermission(any(), any(), any(), any())).thenReturn(false);

            BusinessException ex = assertThrows(BusinessException.class, () -> service.reportRun(baseDto()));
            assertTrue(ex.getMessage().contains("test_db.dwd_event_f"), "报错要点明是哪张表");
            verify(dqRuleResultMapper, never()).insert(any(DqRuleResult.class));
            verify(dqRunInstanceMapper, never()).insert(any(DqRunInstance.class));
        }

        @Test
        @DisplayName("已登录且有表权限时放行")
        void nonAdminWithPermissionPasses() {
            loginAs(5L);
            when(permissionService.isAdmin()).thenReturn(false);
            when(permissionService.hasTablePermission(any(), any(), any(), any())).thenReturn(true);

            service.reportRun(baseDto());

            assertEquals("R_AD_001 事件主键唯一", capturedRow().getRuleName());
            verify(permissionService).hasTablePermission("hive-prod", "test_db", "dwd_event_f",
                    DataAccessPermissionService.TABLE_OP_SELECT);
        }
    }

    @Nested
    @DisplayName("任务归属与告警")
    class TaskBinding {

        @Test
        @DisplayName("规则唯一绑定一个任务时，运行实例归到该任务并登记告警")
        void uniqueBindingIsAdopted() {
            QualityRunReportDTO dto = baseDto();
            dto.setRuleId(21L);
            bindSingleTask(21L, 9L);

            DqAlertSubscription subscription = new DqAlertSubscription();
            subscription.setTaskId(9L);
            subscription.setNotifyOn("ERROR,FAILED");
            subscription.setChannels("INBOX");
            subscription.setReceivers("zhangsan");
            subscription.setEnabled(1);
            when(dqAlertSubscriptionMapper.selectList(any())).thenReturn(List.of(subscription));

            service.reportRun(dto);

            ArgumentCaptor<DqRunInstance> instanceCaptor = ArgumentCaptor.forClass(DqRunInstance.class);
            verify(dqRunInstanceMapper).insert(instanceCaptor.capture());
            DqRunInstance instance = instanceCaptor.getValue();
            assertEquals(9L, instance.getTaskId(), "能定位到任务就该归到任务，而不是把规则名当任务名");
            assertEquals("广告域质检", instance.getTaskName());

            // 程序回传路径也要登记告警：此前只有平台执行路径会
            ArgumentCaptor<DqAlertLog> alertCaptor = ArgumentCaptor.forClass(DqAlertLog.class);
            verify(dqAlertLogMapper).insert(alertCaptor.capture());
            DqAlertLog alert = alertCaptor.getValue();
            assertEquals("INBOX", alert.getChannel());
            assertEquals(DqAlertSender.STATUS_SENT, alert.getStatus(), "站内记录落表即送达，不该停留在 PENDING");
            assertEquals(9L, alert.getTaskId());
            assertEquals("R_AD_001 事件主键唯一", alert.getRuleName());
        }

        @Test
        @DisplayName("规则挂在多个任务上时不猜：taskId 留空、退回规则名、不登记告警")
        void ambiguousBindingIsNotGuessed() {
            QualityRunReportDTO dto = baseDto();
            dto.setRuleId(21L);
            DqTaskRuleRel rel1 = new DqTaskRuleRel();
            rel1.setTaskId(9L);
            DqTaskRuleRel rel2 = new DqTaskRuleRel();
            rel2.setTaskId(10L);
            when(dqTaskRuleRelMapper.selectList(any())).thenReturn(List.of(rel1, rel2));

            service.reportRun(dto);

            ArgumentCaptor<DqRunInstance> captor = ArgumentCaptor.forClass(DqRunInstance.class);
            verify(dqRunInstanceMapper).insert(captor.capture());
            assertNull(captor.getValue().getTaskId(), "多个任务绑定没有唯一答案，宁可留空也不猜");
            assertEquals("R_AD_001 事件主键唯一", captor.getValue().getTaskName());
            verify(dqAlertLogMapper, never()).insert(any(DqAlertLog.class));
        }

        @Test
        @DisplayName("规则未登记时也不登记告警，但结果照常落库")
        void noRuleNoAlert() {
            service.reportRun(baseDto());

            assertEquals("R_AD_001 事件主键唯一", capturedRow().getRuleName());
            verify(dqAlertLogMapper, never()).insert(any(DqAlertLog.class));
        }
    }

    @Nested
    @DisplayName("告警渠道语义")
    class AlertChannels {

        @Test
        @DisplayName("INBOX 落表即送达；WEBHOOK 成功记 SENT、失败记 FAILED 与原因；EMAIL 未配置保持 PENDING")
        void channelStatusSemantics() {
            QualityRunReportDTO dto = baseDto();
            dto.setRuleId(21L);
            bindSingleTask(21L, 9L);

            DqAlertSubscription subscription = new DqAlertSubscription();
            subscription.setTaskId(9L);
            subscription.setNotifyOn("ERROR,FAILED");
            subscription.setChannels("INBOX,WEBHOOK,EMAIL");
            subscription.setReceivers("zhangsan,lisi");
            subscription.setEnabled(1);
            when(dqAlertSubscriptionMapper.selectList(any())).thenReturn(List.of(subscription));
            when(dqAlertSender.sendWebhook(any(), any())).thenReturn("Webhook 返回 HTTP 500");

            service.reportRun(dto);

            ArgumentCaptor<DqAlertLog> captor = ArgumentCaptor.forClass(DqAlertLog.class);
            verify(dqAlertLogMapper, org.mockito.Mockito.times(3)).insert(captor.capture());
            List<DqAlertLog> alerts = captor.getAllValues();

            DqAlertLog inbox = alerts.stream().filter(a -> "INBOX".equals(a.getChannel())).findFirst().orElseThrow();
            assertEquals(DqAlertSender.STATUS_SENT, inbox.getStatus());
            assertNull(inbox.getFailReason());

            DqAlertLog webhook = alerts.stream().filter(a -> "WEBHOOK".equals(a.getChannel())).findFirst().orElseThrow();
            assertEquals(DqAlertSender.STATUS_FAILED, webhook.getStatus());
            assertTrue(webhook.getFailReason().contains("HTTP 500"));

            DqAlertLog email = alerts.stream().filter(a -> "EMAIL".equals(a.getChannel())).findFirst().orElseThrow();
            assertEquals(DqAlertSender.STATUS_PENDING, email.getStatus());
            assertTrue(email.getFailReason().contains("邮件通道未配置"),
                    "要让人看出是「没配」而不是「发失败」");
        }
    }

    @Nested
    @DisplayName("结果内容")
    class ResultContent {

        @Test
        @DisplayName("exec_sql 与 actualValue 一并落库（metric_value 供环比基线复用）")
        void execSqlIsPersisted() {
            QualityRunReportDTO dto = baseDto();
            dto.setExecSql("SELECT event_id FROM test_db.dwd_event_f GROUP BY event_id HAVING COUNT(1) > 1");

            service.reportRun(dto);

            DqRuleResult row = capturedRow();
            assertEquals("SELECT event_id FROM test_db.dwd_event_f GROUP BY event_id HAVING COUNT(1) > 1",
                    row.getExecSql());
            assertEquals("重复 3 条", row.getMetricValue());
        }

        @Test
        @DisplayName("回传未带 exec_sql 时该列为空，不影响落库")
        void execSqlOptional() {
            service.reportRun(baseDto());

            DqRuleResult row = capturedRow();
            assertNull(row.getExecSql());
            assertEquals("R_AD_001 事件主键唯一", row.getRuleName());
        }

        @Test
        @DisplayName("同一批次同库同表复用已存在的运行实例，不新建")
        void batchNoAndInstanceReuse() {
            DqRunInstance running = new DqRunInstance();
            running.setId(55L);
            when(dqRunInstanceMapper.selectList(any())).thenReturn(List.of(running));

            QualityRunReportDTO dto = baseDto();
            dto.setBatchNo(null);
            service.reportRun(dto);

            verify(dqRunInstanceMapper, never()).insert(any(DqRunInstance.class));
            assertEquals(55L, capturedRow().getRunId());
        }
    }
}
