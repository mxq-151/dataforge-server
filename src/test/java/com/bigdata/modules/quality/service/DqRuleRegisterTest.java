package com.bigdata.modules.quality.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.quality.dto.QualityRuleSaveDTO;
import com.bigdata.modules.quality.entity.DqRule;
import com.bigdata.modules.quality.entity.DqRuleTemplate;
import com.bigdata.modules.quality.mapper.DqRuleMapper;
import com.bigdata.modules.quality.mapper.DqRuleTemplateMapper;
import com.bigdata.modules.quality.vo.QualityRuleRegisterVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 规则登记（{@code POST /quality/rule/register}）的幂等语义。
 *
 * <p>登记存在的意义是把关联键从规则名字符串换成 ID，所以本测试锁的第一件事是
 * <b>判定键与回传反查口径一致</b>（规则名 + 库 + 表）；第二件事是<b>已存在不覆盖</b> ——
 * 平台里的阈值可能已被人手工调过，登记动作不该把它冲掉。</p>
 *
 * <p>原先还有一组「登记时顺带挂到质检任务（taskId）」的用例 —— 质检任务链路已整体下线，
 * 该能力连同 dq_task_rule_rel 一并移除，入参也退回到 {@link QualityRuleSaveDTO}。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("规则登记：幂等判定与模板自动绑定")
class DqRuleRegisterTest {

    @Mock
    private DqRuleMapper dqRuleMapper;
    @Mock
    private DqRuleTemplateMapper dqRuleTemplateMapper;
    /**
     * 保存前会先编译一次校验 SQL（把配置错误挡在登记这一步）。
     * 这里只要一个非空 mock 即可 —— validateRenderable 只看"编译是否抛异常"，不用其返回值。
     */
    @Mock
    private DqExecutionService dqExecutionService;

    private DqRuleService service;

    @BeforeEach
    void setUp() {
        service = new DqRuleService();
        ReflectionTestUtils.setField(service, "dqRuleMapper", dqRuleMapper);
        ReflectionTestUtils.setField(service, "dqRuleTemplateMapper", dqRuleTemplateMapper);
        ReflectionTestUtils.setField(service, "dqExecutionService", dqExecutionService);

        // insert 时回填主键，模拟自增
        doAnswer(inv -> {
            ((DqRule) inv.getArgument(0)).setId(21L);
            return 1;
        }).when(dqRuleMapper).insert(any(DqRule.class));

        // 内置模板：规则类型 → 模板自动绑定（DqRuleService.bindTemplate 的既有能力）
        DqRuleTemplate builtin = new DqRuleTemplate();
        builtin.setId(11L);
        builtin.setTemplateCode("PRIMARY_KEY_UNIQUE");
        builtin.setRuleType("PRIMARY_KEY_UNIQUE");
        builtin.setTemplateKind("builtin");
        when(dqRuleTemplateMapper.selectOne(any())).thenReturn(builtin);
    }

    /** 未登记状态的入参：字段级规则，给了字段，规则类型合法 */
    private QualityRuleSaveDTO newRuleDto() {
        QualityRuleSaveDTO dto = new QualityRuleSaveDTO();
        dto.setRuleName("R_AD_001 事件主键唯一");
        dto.setRuleType("PRIMARY_KEY_UNIQUE");
        dto.setDatasourceName("hive-prod");
        dto.setDatabaseName("test_db");
        dto.setTableName("dwd_event_f");
        dto.setColumnName("event_id");
        dto.setExpectedValue("重复=0");
        dto.setDisposeMode("BLOCK");
        return dto;
    }

    @Nested
    @DisplayName("幂等判定")
    class Idempotency {

        @Test
        @DisplayName("未登记时创建，回执带 ruleId 与自动绑定的模板编码")
        void createsWhenAbsent() {
            when(dqRuleMapper.selectList(any())).thenReturn(List.of());

            QualityRuleRegisterVO vo = service.register(newRuleDto());

            assertEquals(21L, vo.getRuleId());
            assertEquals("R_AD_001 事件主键唯一", vo.getRuleName());
            assertEquals("PRIMARY_KEY_UNIQUE", vo.getRuleType());
            assertEquals("PRIMARY_KEY_UNIQUE", vo.getTemplateCode(), "模板由规则类型唯一决定，登记时自动绑定");
            assertTrue(vo.getCreated());
            verify(dqRuleMapper).insert(any(DqRule.class));
        }

        @Test
        @DisplayName("已登记时复用既有规则：不新建、不覆盖（阈值可能已被人手工调过）")
        void reusesWhenPresent() {
            DqRule existing = new DqRule();
            existing.setId(7L);
            existing.setRuleName("R_AD_001 事件主键唯一");
            existing.setRuleType("PRIMARY_KEY_UNIQUE");
            existing.setTemplateCode("PRIMARY_KEY_UNIQUE");
            existing.setExpectedValue("重复=0");
            when(dqRuleMapper.selectList(any())).thenReturn(List.of(existing));

            QualityRuleRegisterVO vo = service.register(newRuleDto());

            assertEquals(7L, vo.getRuleId(), "重复登记必须返回同一条规则，否则每天跑一次会造一堆重名规则");
            assertFalse(vo.getCreated());
            verify(dqRuleMapper, never()).insert(any(DqRule.class));
            verify(dqRuleMapper, never()).updateById(any(DqRule.class));
        }
    }

    @Nested
    @DisplayName("入参校验")
    class Validation {

        @Test
        @DisplayName("缺规则名或表名时拒绝 —— 它们是判定键，缺了会登记出孤儿规则")
        void requiresIdentityFields() {
            QualityRuleSaveDTO noName = newRuleDto();
            noName.setRuleName("  ");
            assertThrows(BusinessException.class, () -> service.register(noName));

            QualityRuleSaveDTO noTable = newRuleDto();
            noTable.setTableName(null);
            assertThrows(BusinessException.class, () -> service.register(noTable));

            assertThrows(BusinessException.class, () -> service.register(null));
        }

        @Test
        @DisplayName("规则类型必须归属 7 类之一（复用 create 的校验，不另起一套）")
        void rejectsUnknownRuleType() {
            QualityRuleSaveDTO dto = newRuleDto();
            dto.setRuleType("CUSTOM_SOMETHING");
            when(dqRuleMapper.selectList(any())).thenReturn(List.of());

            BusinessException ex = assertThrows(BusinessException.class, () -> service.register(dto));
            assertTrue(ex.getMessage().contains("规则类型") || ex.getMessage().contains("7 类"));
            verify(dqRuleMapper, never()).insert(any(DqRule.class));
        }

        @Test
        @DisplayName("字段级规则未指定字段时拒绝")
        void rejectsColumnScopeWithoutColumn() {
            QualityRuleSaveDTO dto = newRuleDto();
            dto.setColumnName(null);
            when(dqRuleMapper.selectList(any())).thenReturn(List.of());

            assertThrows(BusinessException.class, () -> service.register(dto));
            verify(dqRuleMapper, never()).insert(any(DqRule.class));
        }
    }
}
