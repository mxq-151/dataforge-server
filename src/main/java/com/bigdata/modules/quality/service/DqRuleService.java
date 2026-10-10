package com.bigdata.modules.quality.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.quality.dto.QualityBatchByTemplateDTO;
import com.bigdata.modules.quality.dto.QualityBatchTargetDTO;
import com.bigdata.modules.quality.dto.QualityRuleSaveDTO;
import com.bigdata.modules.quality.dto.QualityTestRunDTO;
import com.bigdata.modules.quality.entity.DqRule;
import com.bigdata.modules.quality.entity.DqRuleTemplate;
import com.bigdata.modules.quality.enums.QualityCompareMode;
import com.bigdata.modules.quality.enums.QualityDisposeMode;
import com.bigdata.modules.quality.enums.QualityRuleType;
import com.bigdata.modules.quality.enums.QualityTemplateKind;
import com.bigdata.modules.quality.mapper.DqRuleMapper;
import com.bigdata.modules.quality.mapper.DqRuleTemplateMapper;
import com.bigdata.modules.quality.vo.QualityRuleRegisterVO;
import com.bigdata.modules.quality.vo.QualityRuleResultVO;
import com.bigdata.modules.quality.vo.QualityRuleVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 监控规则 Service —— 规则的增删改查、试跑、按模板批量创建。
 *
 * <p>「试跑」与「正式执行」共用 {@link DqExecutionService}：试跑<b>不落</b>
 * 运行实例与规则结果（契约要求「不落运行实例，只回结果」），因此试跑不会污染
 * 大盘通过率，也不会进入历史基线 —— 这一点很关键：否则调阈值时反复试跑会把
 * 行数基线拉偏。</p>
 */
@Service
public class DqRuleService {

    @Autowired
    private DqRuleMapper dqRuleMapper;

    @Autowired
    private DqRuleTemplateMapper dqRuleTemplateMapper;

    @Autowired
    private DqExecutionService dqExecutionService;

    // ==================================================================
    // 查询
    // ==================================================================

    public Page<QualityRuleVO> page(int current, int size, Long datasourceId, String databaseName,
                                    String tableName, String columnName, String ruleType, Boolean enabled,
                                    String ruleName) {
        LambdaQueryWrapper<DqRule> wrapper = new LambdaQueryWrapper<>();
        if (datasourceId != null) {
            wrapper.eq(DqRule::getDatasourceId, datasourceId);
        }
        if (databaseName != null && !databaseName.isBlank()) {
            wrapper.eq(DqRule::getDatabaseName, databaseName.trim());
        }
        if (tableName != null && !tableName.isBlank()) {
            wrapper.eq(DqRule::getTableName, tableName.trim());
        }
        if (columnName != null && !columnName.isBlank()) {
            wrapper.eq(DqRule::getColumnName, columnName.trim());
        }
        if (ruleType != null && !ruleType.isBlank()) {
            wrapper.eq(DqRule::getRuleType, ruleType.trim());
        }
        // 规则名精确匹配：登记前查「这条规则在不在平台」用得上（模糊检索会一次捞回一堆）
        if (ruleName != null && !ruleName.isBlank()) {
            wrapper.eq(DqRule::getRuleName, ruleName.trim());
        }
        if (enabled != null) {
            wrapper.eq(DqRule::getEnabled, enabled ? 1 : 0);
        }
        wrapper.orderByDesc(DqRule::getId);

        Page<DqRule> result = dqRuleMapper.selectPage(new Page<>(current, size), wrapper);
        Page<QualityRuleVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(DqRuleService::toVO).collect(Collectors.toList()));
        return voPage;
    }

    public DqRule getById(Long id) {
        if (id == null) {
            throw new BusinessException("规则 ID 不能为空");
        }
        DqRule rule = dqRuleMapper.selectById(id);
        if (rule == null) {
            throw new BusinessException("监控规则不存在: " + id);
        }
        return rule;
    }

    // ==================================================================
    // 写操作
    // ==================================================================

    public Long create(QualityRuleSaveDTO dto) {
        DqRule rule = new DqRule();
        applyDto(rule, dto, true);
        validateRenderable(rule);
        dqRuleMapper.insert(rule);
        return rule.getId();
    }

    /**
     * 规则登记（幂等）。
     *
     * <p>解决的是「关联靠规则名字符串」的根问题：设计文档定名 → 平台登记 → S3 实现 → 回传结果，
     * 中间任何一环改名都会断链。登记一次拿到 {@code ruleId} 后，后续实现与回传都用 ID 关联，
     * 名字退化为展示字段。</p>
     *
     * <h3>与 create 的区别</h3>
     * <ul>
     *   <li>幂等：判定键是「规则名 + 库 + 表」，与 {@link DqResultService} 的结果回传反查口径
     *       <b>故意保持一致</b> —— 否则会出现「登记到 A、回传匹配到 B」这种最难查的错配。</li>
     *   <li>已存在<b>不覆盖</b>：平台里的阈值可能已被人手工调过，登记动作不该把它冲掉。
     *       需要同步改动请走 {@code PUT /quality/rule} 或界面编辑。</li>
     *   <li>校验与模板绑定完全复用 create 的路径（7 类规则类型、字段级必须给字段、跨表必须给对照表、
     *       模板按规则类型自动绑定），不另起一套。</li>
     * </ul>
     *
     * @return 登记回执；dto 缺少判定键（规则名 / 库 / 表）时抛业务异常
     */
    public QualityRuleRegisterVO register(QualityRuleSaveDTO dto) {
        if (dto == null) {
            throw new BusinessException("登记内容不能为空");
        }
        if (dto.getRuleName() == null || dto.getRuleName().isBlank()) {
            throw new BusinessException("登记必须提供规则名");
        }
        if (dto.getTableName() == null || dto.getTableName().isBlank()) {
            throw new BusinessException("登记必须提供监控表名");
        }

        DqRule existing = findByIdentity(dto.getRuleName(), dto.getDatabaseName(), dto.getTableName());
        DqRule rule;
        boolean created;
        if (existing != null) {
            rule = existing;
            created = false;
        } else {
            rule = new DqRule();
            applyDto(rule, dto, true);
            validateRenderable(rule);
            dqRuleMapper.insert(rule);
            created = true;
        }

        return toRegisterVO(rule, created);
    }

    /**
     * 按「规则名 + 库 + 表」定位规则。
     *
     * <p>三段限定的理由与回传反查相同：规则名只在单个设计文档内唯一，跨库表可能重名。
     * 返回 {@code null} 表示未登记，由调用方决定是创建还是放弃。</p>
     */
    private DqRule findByIdentity(String ruleName, String databaseName, String tableName) {
        LambdaQueryWrapper<DqRule> wrapper = new LambdaQueryWrapper<DqRule>()
                .eq(DqRule::getRuleName, ruleName.trim());
        if (databaseName != null && !databaseName.isBlank()) {
            wrapper.eq(DqRule::getDatabaseName, databaseName.trim());
        }
        if (tableName != null && !tableName.isBlank()) {
            wrapper.eq(DqRule::getTableName, tableName.trim());
        }
        wrapper.orderByDesc(DqRule::getId);
        List<DqRule> found = dqRuleMapper.selectList(wrapper.last("LIMIT 1"));
        return found.isEmpty() ? null : found.get(0);
    }

    private static QualityRuleRegisterVO toRegisterVO(DqRule rule, boolean created) {
        QualityRuleRegisterVO vo = new QualityRuleRegisterVO();
        vo.setRuleId(rule.getId());
        vo.setRuleName(rule.getRuleName());
        vo.setRuleType(rule.getRuleType());
        vo.setTemplateCode(rule.getTemplateCode());
        vo.setCreated(created);
        return vo;
    }

    public void update(QualityRuleSaveDTO dto) {
        if (dto.getId() == null) {
            throw new BusinessException("规则 ID 不能为空");
        }
        DqRule existing = getById(dto.getId());
        applyDto(existing, dto, false);
        validateRenderable(existing);
        dqRuleMapper.updateById(existing);
    }

    @Transactional
    public void delete(Long id) {
        DqRule existing = getById(id);
        dqRuleMapper.deleteById(existing.getId());
    }

    /**
     * 按模板批量建规则（对齐 DataWorks「按模板批量配置」）。
     *
     * <p>已存在「同表 + 同字段 + 同规则类型」的规则会被跳过而不是报错 ——
     * 批量配置的典型用法是在既有表集合上补齐规则，遇到已配的表应当继续往下走。</p>
     *
     * @return {@code {created, skipped}}
     */
    @Transactional
    public Map<String, Integer> batchCreateByTemplate(QualityBatchByTemplateDTO dto) {
        if (dto.getTemplateId() == null) {
            throw new BusinessException("模板 ID 不能为空");
        }
        if (dto.getTargets() == null || dto.getTargets().isEmpty()) {
            throw new BusinessException("请至少选择一个目标表");
        }
        DqRuleTemplate template = dqRuleTemplateMapper.selectById(dto.getTemplateId());
        if (template == null) {
            throw new BusinessException("规则模板不存在: " + dto.getTemplateId());
        }
        QualityRuleType ruleType = QualityRuleType.fromCode(template.getRuleType());
        if (ruleType == null) {
            throw new BusinessException("模板归属的规则类型非法: " + template.getRuleType());
        }
        QualityRuleSaveDTO defaults = dto.getDefaults() == null ? new QualityRuleSaveDTO() : dto.getDefaults();

        int created = 0;
        int skipped = 0;
        for (QualityBatchTargetDTO target : dto.getTargets()) {
            if (target == null || target.getTableName() == null || target.getTableName().isBlank()) {
                skipped++;
                continue;
            }
            String columnName = ruleType.isColumnScope() ? target.getColumnName() : null;
            if (ruleType.isColumnScope() && (columnName == null || columnName.isBlank())) {
                // 字段级规则缺字段无法渲染 SQL，直接跳过而不是造一条永远失败的空规则
                skipped++;
                continue;
            }
            if (exists(target, columnName, ruleType.getCode())) {
                skipped++;
                continue;
            }

            DqRule rule = new DqRule();
            rule.setRuleType(ruleType.getCode());
            rule.setTemplateId(template.getId());
            rule.setTemplateCode(template.getTemplateCode());
            rule.setDatasourceId(target.getDatasourceId() != null
                    ? target.getDatasourceId() : defaults.getDatasourceId());
            rule.setDatasourceName(defaults.getDatasourceName());
            rule.setDatabaseName(target.getDatabaseName() != null
                    ? target.getDatabaseName() : defaults.getDatabaseName());
            rule.setTableName(target.getTableName());
            rule.setColumnName(columnName);
            rule.setRuleName(buildBatchRuleName(defaults.getRuleName(), template.getTemplateName(), target));
            rule.setPartitionExpr(defaults.getPartitionExpr());
            rule.setRefDatasourceId(defaults.getRefDatasourceId());
            rule.setRefDatabaseName(defaults.getRefDatabaseName());
            rule.setRefTableName(defaults.getRefTableName());
            rule.setRefColumnName(defaults.getRefColumnName());
            rule.setCompareMode(normalizeCompareMode(defaults.getCompareMode()));
            rule.setExpectedValue(defaults.getExpectedValue() != null
                    ? defaults.getExpectedValue() : defaultExpectedValue(ruleType));
            rule.setWarnThreshold(defaults.getWarnThreshold());
            rule.setErrorThreshold(defaults.getErrorThreshold());
            rule.setDisposeMode(normalizeDisposeMode(defaults.getDisposeMode()));
            rule.setOwnerName(defaults.getOwnerName());
            rule.setEnabled(Boolean.FALSE.equals(defaults.getEnabled()) ? 0 : 1);

            validateRenderable(rule);
            dqRuleMapper.insert(rule);
            created++;
        }
        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("created", created);
        summary.put("skipped", skipped);
        return summary;
    }

    // ==================================================================
    // 试跑
    // ==================================================================

    /**
     * 规则试跑：编译 + 真实下发，但<b>不落</b>任何结果。
     *
     * <p>可以传 ruleId（跑已保存的规则），也可以只传 rule（跑尚未保存的草稿）——
     * 后者是保存前验证阈值是否合理的核心路径。</p>
     */
    public QualityRuleResultVO testRun(QualityTestRunDTO dto) {
        if (dto == null) {
            throw new BusinessException("试跑参数不能为空");
        }
        LocalDate batchDate = DqBatchSupport.parseBatchDate(dto.getBatchNo());

        DqRule rule;
        if (dto.getRuleId() != null) {
            rule = getById(dto.getRuleId());
        } else if (dto.getRule() != null) {
            rule = new DqRule();
            applyDto(rule, dto.getRule(), false);
            if (rule.getRuleName() == null || rule.getRuleName().isBlank()) {
                rule.setRuleName("（未保存的试跑规则）");
            }
        } else {
            throw new BusinessException("试跑需要提供 ruleId 或 rule 内容");
        }

        // 试跑不写历史，因此行数类规则拿不到基线；这里显式取历史基线，
        // 让「试跑」与「正式执行」的判定口径保持一致，避免试跑全绿、正式跑全红。
        BigDecimal baseline = rule.getId() == null ? null : dqExecutionService.resolveBaseline(rule);
        DqExecutionService.Outcome outcome = dqExecutionService.execute(rule, batchDate, baseline);

        QualityRuleResultVO vo = new QualityRuleResultVO();
        vo.setRuleId(rule.getId());
        vo.setRuleName(rule.getRuleName());
        vo.setRuleType(rule.getRuleType());
        vo.setCheckObject(outcome.compiled().checkObject());
        vo.setThreshold(outcome.evaluation().threshold());
        vo.setActualValue(outcome.evaluation().actualValue());
        vo.setStatus(outcome.evaluation().status() == null ? null : outcome.evaluation().status().getCode());
        vo.setSeverity(outcome.evaluation().severity());
        vo.setExecSql(outcome.compiled().sql());
        vo.setErrorMsg(outcome.errorMessage());
        vo.setCostMs(outcome.costMs());
        return vo;
    }

    // ==================================================================
    // 映射与校验
    // ==================================================================

    static QualityRuleVO toVO(DqRule rule) {
        QualityRuleVO vo = new QualityRuleVO();
        vo.setId(rule.getId());
        vo.setRuleName(rule.getRuleName());
        vo.setRuleType(rule.getRuleType());
        vo.setTemplateId(rule.getTemplateId());
        vo.setTemplateCode(rule.getTemplateCode());
        vo.setDatasourceId(rule.getDatasourceId());
        vo.setDatasourceName(rule.getDatasourceName());
        vo.setDatabaseName(rule.getDatabaseName());
        vo.setTableName(rule.getTableName());
        vo.setColumnName(rule.getColumnName());
        vo.setPartitionExpr(rule.getPartitionExpr());
        vo.setRefDatasourceId(rule.getRefDatasourceId());
        vo.setRefDatabaseName(rule.getRefDatabaseName());
        vo.setRefTableName(rule.getRefTableName());
        vo.setRefColumnName(rule.getRefColumnName());
        vo.setCompareMode(rule.getCompareMode());
        vo.setExpectedValue(rule.getExpectedValue());
        vo.setWarnThreshold(rule.getWarnThreshold());
        vo.setErrorThreshold(rule.getErrorThreshold());
        vo.setDisposeMode(rule.getDisposeMode());
        vo.setOwnerName(rule.getOwnerName());
        vo.setEnabled(rule.getEnabled() == null || rule.getEnabled() == 1);
        vo.setCreateTime(rule.getCreateTime());
        vo.setUpdateTime(rule.getUpdateTime());
        return vo;
    }

    private void applyDto(DqRule rule, QualityRuleSaveDTO dto, boolean isNew) {
        if (dto == null) {
            throw new BusinessException("规则内容不能为空");
        }
        if (isNew && dto.getId() != null) {
            throw new BusinessException("新增规则不应携带 id");
        }
        if (dto.getRuleName() == null || dto.getRuleName().isBlank()) {
            throw new BusinessException("规则名称不能为空");
        }
        QualityRuleType ruleType = QualityRuleType.fromCode(dto.getRuleType());
        if (ruleType == null) {
            // S3 侧质检程序是按设计文档的「中文规则类型名」登记规则的（照抄提示词里的稽核规则清单），
            // 与界面传编码并存：编码解析不到时按 label 再解析一次（fromLabel 即为此保留）。
            ruleType = QualityRuleType.fromLabel(dto.getRuleType());
        }
        if (ruleType == null) {
            throw new BusinessException("无法识别的规则类型: " + dto.getRuleType()
                    + "（必须是 7 类之一，禁止自造同义词）");
        }
        if (dto.getTableName() == null || dto.getTableName().isBlank()) {
            throw new BusinessException("监控表名不能为空");
        }
        if (ruleType.isColumnScope() && (dto.getColumnName() == null || dto.getColumnName().isBlank())) {
            throw new BusinessException("「" + ruleType.getLabel() + "」是字段级规则，必须指定字段");
        }
        if (ruleType.isPairScope() && (dto.getRefTableName() == null || dto.getRefTableName().isBlank())) {
            throw new BusinessException("「" + ruleType.getLabel() + "」是跨表规则，必须指定对照表");
        }

        rule.setRuleName(dto.getRuleName().trim());
        rule.setRuleType(ruleType.getCode());
        rule.setDatasourceId(dto.getDatasourceId());
        rule.setDatasourceName(dto.getDatasourceName());
        rule.setDatabaseName(trimToNull(dto.getDatabaseName()));
        rule.setTableName(dto.getTableName().trim());
        rule.setColumnName(trimToNull(dto.getColumnName()));
        rule.setPartitionExpr(trimToNull(dto.getPartitionExpr()));
        rule.setRefDatasourceId(dto.getRefDatasourceId());
        rule.setRefDatabaseName(trimToNull(dto.getRefDatabaseName()));
        rule.setRefTableName(trimToNull(dto.getRefTableName()));
        rule.setRefColumnName(trimToNull(dto.getRefColumnName()));
        rule.setCompareMode(normalizeCompareMode(dto.getCompareMode()));
        rule.setExpectedValue(dto.getExpectedValue() != null
                ? dto.getExpectedValue() : defaultExpectedValue(ruleType));
        rule.setWarnThreshold(trimToNull(dto.getWarnThreshold()));
        rule.setErrorThreshold(trimToNull(dto.getErrorThreshold()));
        rule.setDisposeMode(normalizeDisposeMode(dto.getDisposeMode()));
        rule.setOwnerName(trimToNull(dto.getOwnerName()));
        rule.setEnabled(Boolean.FALSE.equals(dto.getEnabled()) ? 0 : 1);
        rule.setParams(dto.getParams());

        bindTemplate(rule, dto, ruleType);
    }

    /**
     * 绑定模板。
     *
     * <p>两种来源：显式传 templateId（界面上选了某个自定义模板），
     * 或只给了 ruleType（界面按规则类型建规则）—— 后者自动绑定到同名的内置模板，
     * 这样 {@code rule.templateCode} 永远有值，覆盖率统计才有口径。</p>
     */
    private void bindTemplate(DqRule rule, QualityRuleSaveDTO dto, QualityRuleType ruleType) {
        if (dto.getTemplateId() != null) {
            DqRuleTemplate template = dqRuleTemplateMapper.selectById(dto.getTemplateId());
            if (template == null) {
                throw new BusinessException("规则模板不存在: " + dto.getTemplateId());
            }
            if (!ruleType.getCode().equals(template.getRuleType())) {
                throw new BusinessException("所选模板归属的规则类型是「" + template.getRuleType()
                        + "」，与规则的「" + ruleType.getCode() + "」不一致");
            }
            rule.setTemplateId(template.getId());
            rule.setTemplateCode(template.getTemplateCode());
            return;
        }
        DqRuleTemplate builtin = findBuiltin(ruleType.getCode());
        if (builtin != null) {
            rule.setTemplateId(builtin.getId());
            rule.setTemplateCode(builtin.getTemplateCode());
        } else {
            rule.setTemplateId(null);
            rule.setTemplateCode(dto.getTemplateCode() != null
                    ? dto.getTemplateCode() : ruleType.getCode());
        }
    }

    private DqRuleTemplate findBuiltin(String templateCode) {
        return dqRuleTemplateMapper.selectOne(new LambdaQueryWrapper<DqRuleTemplate>()
                .eq(DqRuleTemplate::getTemplateCode, templateCode)
                .eq(DqRuleTemplate::getTemplateKind, QualityTemplateKind.BUILTIN.getCode())
                .last("LIMIT 1"));
    }

    /**
     * 保存前先编译一次校验 SQL。
     *
     * <p>目的是把「配置错误」挡在保存这一步：一条渲染不出 SQL 的规则只会在每次调度时
     * 静默失败，等到有人去看运行记录才发现，代价比保存时报错大得多。
     * 未选数据源时跳过（此时还无法确定方言）。</p>
     */
    private void validateRenderable(DqRule rule) {
        if (rule.getDatasourceId() == null
                && (rule.getDatasourceName() == null || rule.getDatasourceName().isBlank())) {
            return;
        }
        dqExecutionService.compile(rule, LocalDate.now());
    }

    private boolean exists(QualityBatchTargetDTO target, String columnName, String ruleType) {
        LambdaQueryWrapper<DqRule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DqRule::getRuleType, ruleType)
                .eq(DqRule::getTableName, target.getTableName());
        if (target.getDatabaseName() != null && !target.getDatabaseName().isBlank()) {
            wrapper.eq(DqRule::getDatabaseName, target.getDatabaseName());
        }
        if (columnName == null) {
            wrapper.isNull(DqRule::getColumnName).or().eq(DqRule::getColumnName, "");
        } else {
            wrapper.eq(DqRule::getColumnName, columnName);
        }
        return dqRuleMapper.selectCount(wrapper) > 0;
    }

    private static String buildBatchRuleName(String template, String templateName, QualityBatchTargetDTO target) {
        String base = template != null && !template.isBlank() ? template : templateName;
        String scope = target.getColumnName() != null && !target.getColumnName().isBlank()
                ? target.getTableName() + "." + target.getColumnName()
                : target.getTableName();
        return base + " - " + scope;
    }

    /** 计数类规则的默认期望值是 0（不允许出现异常条数） */
    private static String defaultExpectedValue(QualityRuleType ruleType) {
        return switch (ruleType) {
            case PRIMARY_KEY_UNIQUE, NOT_NULL, VALUE_RANGE -> "0";
            default -> null;
        };
    }

    private static String normalizeCompareMode(String raw) {
        QualityCompareMode mode = QualityCompareMode.fromCode(raw);
        return mode == null ? QualityCompareMode.LTE.getCode() : mode.getCode();
    }

    private static String normalizeDisposeMode(String raw) {
        QualityDisposeMode mode = QualityDisposeMode.fromCode(raw);
        if (mode != null) {
            return mode.getCode();
        }
        // S3 侧程序是按设计文档的处置方式文案传的（"阻断下游+告警" / "告警"），
        // 与界面传编码并存：含"阻断"即 BLOCK，其余落 ALERT ——
        // 一律退化成 ALERT 会把「阻断下游」的语义悄悄丢掉。
        return raw != null && raw.contains("阻断")
                ? QualityDisposeMode.BLOCK.getCode() : QualityDisposeMode.ALERT.getCode();
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
