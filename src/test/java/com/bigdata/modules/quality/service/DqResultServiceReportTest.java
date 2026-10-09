package com.bigdata.modules.quality.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.security.CustomUserDetails;
import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.modules.quality.dto.QualityResultReportDTO;
import com.bigdata.modules.quality.entity.DqRule;
import com.bigdata.modules.quality.entity.DqRuleResult;
import com.bigdata.modules.quality.mapper.DqRuleMapper;
import com.bigdata.modules.quality.mapper.DqRuleResultMapper;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 规则级结果回传（{@code POST /quality/run/report}）落库语义。
 *
 * <p>质检任务 / 运行实例 / 告警订阅三条链路已删除后，回传仍然保留 —— 它是
 * 「结果留痕」（dq_rule_result）的唯一写入方，而「行数波动 / 行数环比」的试跑基线与
 * 资产目录质量摘要都读这张表。本测试锁三件事：</p>
 * <ol>
 *   <li><b>规则关联</b>：显式 {@code ruleId} 优先，否则按「规则名 + 库 + 表」三段反查，
 *       查不到落 {@code null} 且不报错（回传失败不得改变校验任务本身的成败判定）；</li>
 *   <li><b>表级读权限 fail-close</b>：回传是写入行为，能写自己读不到的表的结果就等于可以伪造它；</li>
 *   <li><b>结果行自包含</b>：批次、计数、实际执行的 SQL 全部落库，不再依赖任何实例表。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("结果回传：rule_id 反查 / 权限 fail-close / 结果留痕字段")
class DqResultServiceReportTest {

    @Mock
    private DqRuleResultMapper dqRuleResultMapper;
    @Mock
    private DqRuleMapper dqRuleMapper;
    @Mock
    private DataAccessPermissionService permissionService;

    private DqResultService service;

    @BeforeEach
    void setUp() {
        service = new DqResultService();
        ReflectionTestUtils.setField(service, "dqRuleResultMapper", dqRuleResultMapper);
        ReflectionTestUtils.setField(service, "dqRuleMapper", dqRuleMapper);
        ReflectionTestUtils.setField(service, "permissionService", permissionService);

        // 默认按管理员放行：权限本身另有专项用例，其余用例不该被它绊住
        when(permissionService.isAdmin()).thenReturn(true);
    }

    private QualityResultReportDTO baseDto() {
        QualityResultReportDTO dto = new QualityResultReportDTO();
        dto.setDatasourceName("hive-prod");
        dto.setDatabaseName("test_db");
        dto.setTableName("dwd_event_f");
        dto.setBatchNo("20261006");
        dto.setRuleName("R_AD_001 事件主键唯一");
        dto.setRuleType("PRIMARY_KEY_UNIQUE");
        dto.setCheckObject("test_db.dwd_event_f.event_id");
        dto.setThreshold("重复=0");
        dto.setActualValue("3");
        dto.setCheckRecordCount(1000L);
        dto.setFailRecordCount(3L);
        dto.setExecSql("SELECT COUNT(*) FROM test_db.dwd_event_f");
        dto.setPassed(false);
        return dto;
    }

    private DqRuleResult capturedRow() {
        ArgumentCaptor<DqRuleResult> captor = ArgumentCaptor.forClass(DqRuleResult.class);
        verify(dqRuleResultMapper).insert(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("结果行内容")
    class RowContent {

        @Test
        @DisplayName("未通过：状态落 ERROR、严重级别落 ERROR，批次/计数/执行 SQL 全部留痕")
        void writesUnpassedRow() {
            when(dqRuleMapper.selectList(any())).thenReturn(List.of());

            service.reportResult(baseDto());

            DqRuleResult row = capturedRow();
            assertEquals("20261006", row.getBatchNo());
            assertEquals("R_AD_001 事件主键唯一", row.getRuleName());
            assertEquals("PRIMARY_KEY_UNIQUE", row.getRuleType());
            assertEquals("ERROR", row.getStatus());
            assertEquals("ERROR", row.getSeverity());
            assertEquals("3", row.getMetricValue(), "原始度量值要留痕，环比基线靠它回推");
            assertEquals(1000L, row.getCheckRecordCount());
            assertEquals(3L, row.getFailRecordCount());
            assertEquals("SELECT COUNT(*) FROM test_db.dwd_event_f", row.getExecSql(),
                    "模板会改版，实际执行的 SQL 必须留痕才能事后核对口径");
        }

        @Test
        @DisplayName("通过：状态落 PASS 且不带严重级别")
        void writesPassedRow() {
            when(dqRuleMapper.selectList(any())).thenReturn(List.of());
            QualityResultReportDTO dto = baseDto();
            dto.setPassed(true);

            service.reportResult(dto);

            DqRuleResult row = capturedRow();
            assertEquals("PASS", row.getStatus());
            assertNull(row.getSeverity());
        }

        @Test
        @DisplayName("缺批次时兜底为今天，不因缺字段整条回传失败")
        void batchNoFallsBackToToday() {
            when(dqRuleMapper.selectList(any())).thenReturn(List.of());
            QualityResultReportDTO dto = baseDto();
            dto.setBatchNo("  ");

            service.reportResult(dto);

            assertEquals(DqBatchSupport.todayBatchNo(), capturedRow().getBatchNo());
        }

        @Test
        @DisplayName("缺规则名时拒绝 —— 规则名是定位这条结果的最小信息")
        void rejectsWithoutRuleName() {
            QualityResultReportDTO dto = baseDto();
            dto.setRuleName(null);

            assertThrows(BusinessException.class, () -> service.reportResult(dto));
            verify(dqRuleResultMapper, never()).insert(any(DqRuleResult.class));
        }
    }

    @Nested
    @DisplayName("规则关联（rule_id）")
    class RuleResolution {

        @Test
        @DisplayName("显式 ruleId 优先，不再按名字反查")
        void explicitRuleIdWins() {
            QualityResultReportDTO dto = baseDto();
            dto.setRuleId(42L);

            service.reportResult(dto);

            assertEquals(42L, capturedRow().getRuleId());
            verify(dqRuleMapper, never()).selectList(any());
        }

        @Test
        @DisplayName("未传 ruleId 时按「规则名 + 库 + 表」反查回填")
        void resolvesByTriple() {
            DqRule rule = new DqRule();
            rule.setId(21L);
            when(dqRuleMapper.selectList(any())).thenReturn(List.of(rule));

            service.reportResult(baseDto());

            assertEquals(21L, capturedRow().getRuleId());
        }

        @Test
        @DisplayName("反查不到时 ruleId 落 null 且不报错（不阻塞程序回传）")
        void unresolvedIsNotAnError() {
            when(dqRuleMapper.selectList(any())).thenReturn(List.of());

            service.reportResult(baseDto());

            assertNull(capturedRow().getRuleId());
            assertEquals("R_AD_001 事件主键唯一", capturedRow().getRuleName(), "名字仍要落库，规则被删后仍可追溯");
        }
    }

    @Nested
    @DisplayName("表级读权限（fail-close）")
    class Permission {

        /**
         * 必须先把登录态造出来：{@code SecurityUtils.getCurrentUserId()} 返回 null 时，
         * 判定会停在"未登录"分支上 —— 那样测的其实是未登录，而不是"已登录但没有表权限"。
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
            service.reportResult(baseDto());

            assertEquals("R_AD_001 事件主键唯一", capturedRow().getRuleName());
        }

        @Test
        @DisplayName("已登录但无表权限时拒绝落库，且不写任何结果行")
        void nonAdminWithoutPermissionIsRejected() {
            loginAs(5L);
            when(permissionService.isAdmin()).thenReturn(false);
            when(permissionService.hasTablePermission(any(), any(), any(), any())).thenReturn(false);

            BusinessException ex = assertThrows(BusinessException.class, () -> service.reportResult(baseDto()));

            assertTrue(ex.getMessage().contains("无权读取表"), "错误信息要指认目标表，便于排查: " + ex.getMessage());
            verify(dqRuleResultMapper, never()).insert(any(DqRuleResult.class));
        }

        @Test
        @DisplayName("有表权限时放行")
        void permittedUserPasses() {
            loginAs(5L);
            when(permissionService.isAdmin()).thenReturn(false);
            when(permissionService.hasTablePermission(any(), any(), any(), any())).thenReturn(true);

            service.reportResult(baseDto());

            assertEquals("R_AD_001 事件主键唯一", capturedRow().getRuleName());
        }

        @Test
        @DisplayName("缺表名时无从判定目标，不做权限校验（否则规则级上报会被整批拒绝）")
        void missingTableSkipsPermissionCheck() {
            loginAs(5L);
            when(permissionService.isAdmin()).thenReturn(false);
            QualityResultReportDTO dto = baseDto();
            dto.setTableName(null);

            service.reportResult(dto);

            verify(permissionService, never()).hasTablePermission(any(), any(), any(), any());
        }
    }
}
