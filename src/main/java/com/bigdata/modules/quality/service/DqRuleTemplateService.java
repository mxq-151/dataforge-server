package com.bigdata.modules.quality.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.quality.dto.QualityTemplateSaveDTO;
import com.bigdata.modules.quality.entity.DqRule;
import com.bigdata.modules.quality.entity.DqRuleTemplate;
import com.bigdata.modules.quality.enums.QualityRuleType;
import com.bigdata.modules.quality.enums.QualityTemplateKind;
import com.bigdata.modules.quality.mapper.DqRuleMapper;
import com.bigdata.modules.quality.mapper.DqRuleTemplateMapper;
import com.bigdata.modules.quality.sql.DqBuiltinTemplates;
import com.bigdata.modules.quality.sql.DqSqlBuilder;
import com.bigdata.modules.quality.vo.QualityRuleTemplateVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 规则模板 Service。
 *
 * <h3>内置模板为什么「目录在库、正文在码」</h3>
 * <p>7 类内置模板必须能在界面上被选中并参与 {@code rule.templateId} 关联，所以
 * dq_rule_template 里必须有这 7 行真实记录（有主键、能被外键引用）。
 * 但模板正文（SQL 模板、占位符说明）决定校验 SQL 的形态，必须与
 * {@code DqSqlBuilder} 的渲染逻辑同生共死，因此正文由
 * {@link DqBuiltinTemplates} 提供，读取时合并进 VO。</p>
 *
 * <p>由此带来的一个副作用：库里的 7 行是「目录」，正文不会漂移；
 * 若某个环境的历史建库脚本漏种了模板行，这里会按 7 类补齐
 * （{@link #ensureBuiltinTemplates()}），避免「模板选不出来 → 规则建不了」的死锁。</p>
 */
@Service
public class DqRuleTemplateService {

    private static final Logger log = LoggerFactory.getLogger(DqRuleTemplateService.class);

    @Autowired
    private DqRuleTemplateMapper dqRuleTemplateMapper;

    @Autowired
    private DqRuleMapper dqRuleMapper;

    @Autowired
    private DqExecutionService dqExecutionService;

    // ==================================================================
    // 内置模板
    // ==================================================================

    /**
     * 内置模板列表 —— 顺序与枚举一致（即提示词里的 1..7 编号顺序）。
     *
     * <p>只返回目录行，正文由 {@link DqBuiltinTemplates} 合并进来。</p>
     */
    public List<QualityRuleTemplateVO> listBuiltin() {
        ensureBuiltinTemplates();
        Map<String, DqRuleTemplate> byCode = dqRuleTemplateMapper.selectList(
                        new LambdaQueryWrapper<DqRuleTemplate>()
                                .eq(DqRuleTemplate::getDeleted, 0)
                                .eq(DqRuleTemplate::getTemplateKind, QualityTemplateKind.BUILTIN.getCode()))
                .stream()
                .collect(Collectors.toMap(DqRuleTemplate::getTemplateCode, t -> t, (a, b) -> a));

        List<QualityRuleTemplateVO> result = new ArrayList<>();
        for (DqBuiltinTemplates.Definition definition : DqBuiltinTemplates.all()) {
            DqRuleTemplate entity = byCode.get(definition.templateCode());
            QualityRuleTemplateVO vo = new QualityRuleTemplateVO();
            if (entity != null) {
                vo.setId(entity.getId());
                vo.setTemplateCode(entity.getTemplateCode());
                vo.setTemplateName(entity.getTemplateName());
                vo.setRuleType(entity.getRuleType());
                vo.setTemplateKind(entity.getTemplateKind());
                vo.setRemark(entity.getRemark());
                vo.setEnabled(entity.getEnabled() == null || entity.getEnabled() == 1);
            } else {
                // 目录行缺失（历史脚本未种）时也把定义返回，保证界面可配置；
                // 未落库的模板无法被 rule.templateId 引用，因此这里不伪造 id。
                vo.setTemplateCode(definition.templateCode());
                vo.setTemplateName(definition.templateName());
                vo.setRuleType(definition.ruleType().getCode());
                vo.setTemplateKind(QualityTemplateKind.BUILTIN.getCode());
                vo.setRemark(definition.ruleType().getDesc());
                vo.setEnabled(true);
            }
            // 正文与参数说明始终以代码里的规范定义为准
            vo.setSqlTemplate(definition.sqlTemplate());
            vo.setParamSchema(definition.paramSchema());
            vo.setFieldTypes(definition.fieldTypes());
            result.add(vo);
        }
        return result;
    }

    /**
     * 确保 7 类内置模板的目录行存在。
     *
     * <p>幂等：已存在的不动（保留人工改过的名称/备注），只补缺的行。
     * 之所以不做成「每次启动都覆盖」，是因为名称与备注属于可运维内容 ——
     * 但 {@code template_code} 与 {@code rule_type} 必须与枚举一致，不一致时报错而不是静默改写。</p>
     */
    public void ensureBuiltinTemplates() {
        List<DqRuleTemplate> existing = dqRuleTemplateMapper.selectList(
                new LambdaQueryWrapper<DqRuleTemplate>()
                        .eq(DqRuleTemplate::getDeleted, 0)
                        .eq(DqRuleTemplate::getTemplateKind, QualityTemplateKind.BUILTIN.getCode()));
        Map<String, DqRuleTemplate> byCode = existing.stream()
                .collect(Collectors.toMap(DqRuleTemplate::getTemplateCode, t -> t, (a, b) -> a));

        for (DqBuiltinTemplates.Definition definition : DqBuiltinTemplates.all()) {
            DqRuleTemplate found = byCode.get(definition.templateCode());
            if (found != null) {
                if (!definition.ruleType().getCode().equals(found.getRuleType())) {
                    throw new BusinessException("内置模板 " + definition.templateCode()
                            + " 的 rule_type 与规范定义不一致（库=" + found.getRuleType()
                            + "，规范=" + definition.ruleType().getCode() + "），"
                            + "该不一致会让覆盖率统计失去口径，请人工确认后再启动");
                }
                continue;
            }
            DqRuleTemplate seed = new DqRuleTemplate();
            seed.setTemplateCode(definition.templateCode());
            seed.setTemplateName(definition.templateName());
            seed.setRuleType(definition.ruleType().getCode());
            seed.setTemplateKind(QualityTemplateKind.BUILTIN.getCode());
            seed.setSqlTemplate(definition.sqlTemplate());
            seed.setParamSchema(definition.paramSchema());
            seed.setFieldTypes(definition.fieldTypes());
            seed.setRemark(definition.ruleType().getDesc());
            seed.setEnabled(1);
            seed.setDeleted(0);
            dqRuleTemplateMapper.insert(seed);
            log.warn("补齐缺失的内置规则模板目录行: {}（说明该环境的建库脚本未种全 7 类）",
                    definition.templateCode());
        }
    }

    // ==================================================================
    // 自定义模板
    // ==================================================================

    public Page<QualityRuleTemplateVO> pageCustom(int current, int size, String ruleType, String keyword) {
        Page<DqRuleTemplate> page = new Page<>(current, size);
        LambdaQueryWrapper<DqRuleTemplate> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DqRuleTemplate::getDeleted, 0)
                .eq(DqRuleTemplate::getTemplateKind, QualityTemplateKind.CUSTOM.getCode());
        if (ruleType != null && !ruleType.isBlank()) {
            wrapper.eq(DqRuleTemplate::getRuleType, ruleType.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(DqRuleTemplate::getTemplateName, kw)
                    .or().like(DqRuleTemplate::getTemplateCode, kw));
        }
        wrapper.orderByDesc(DqRuleTemplate::getId);

        Page<DqRuleTemplate> result = dqRuleTemplateMapper.selectPage(page, wrapper);
        Page<QualityRuleTemplateVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(DqRuleTemplateService::toVO).collect(Collectors.toList()));
        return voPage;
    }

    public DqRuleTemplate getById(Long id) {
        if (id == null) {
            throw new BusinessException("模板 ID 不能为空");
        }
        DqRuleTemplate template = dqRuleTemplateMapper.selectById(id);
        if (template == null || Integer.valueOf(1).equals(template.getDeleted())) {
            throw new BusinessException("规则模板不存在: " + id);
        }
        return template;
    }

    public void create(QualityTemplateSaveDTO dto) {
        validate(dto, true);
        long duplicated = dqRuleTemplateMapper.selectCount(new LambdaQueryWrapper<DqRuleTemplate>()
                .eq(DqRuleTemplate::getTemplateCode, dto.getTemplateCode().trim())
                .eq(DqRuleTemplate::getDeleted, 0));
        if (duplicated > 0) {
            throw new BusinessException("模板编码已存在: " + dto.getTemplateCode());
        }
        DqRuleTemplate entity = new DqRuleTemplate();
        applyDto(entity, dto);
        entity.setDeleted(0);
        dqRuleTemplateMapper.insert(entity);
    }

    public void update(QualityTemplateSaveDTO dto) {
        if (dto.getId() == null) {
            throw new BusinessException("模板 ID 不能为空");
        }
        validate(dto, false);
        DqRuleTemplate existing = getById(dto.getId());
        assertCustom(existing, "修改");
        long duplicated = dqRuleTemplateMapper.selectCount(new LambdaQueryWrapper<DqRuleTemplate>()
                .eq(DqRuleTemplate::getTemplateCode, dto.getTemplateCode().trim())
                .eq(DqRuleTemplate::getDeleted, 0)
                .ne(DqRuleTemplate::getId, dto.getId()));
        if (duplicated > 0) {
            throw new BusinessException("模板编码已存在: " + dto.getTemplateCode());
        }
        applyDto(existing, dto);
        dqRuleTemplateMapper.updateById(existing);
    }

    public void delete(Long id) {
        DqRuleTemplate existing = getById(id);
        assertCustom(existing, "删除");
        long referenced = dqRuleMapper.selectCount(new LambdaQueryWrapper<DqRule>()
                .eq(DqRule::getTemplateId, id)
                .eq(DqRule::getDeleted, 0));
        if (referenced > 0) {
            throw new BusinessException("该模板已被 " + referenced + " 条监控规则引用，"
                    + "删除会导致这些规则无法渲染校验 SQL；请先解除引用");
        }
        existing.setDeleted(1);
        dqRuleTemplateMapper.updateById(existing);
    }

    // ==================================================================
    // SQL 预览
    // ==================================================================

    /**
     * 把模板渲染成方言 SQL 预览，供配置界面核对。
     *
     * <p>预览不连库、不执行，只做「按目标数据源的方言把占位符填好」。
     * 数据源的库名/表名/字段名由入参给出，因此可以在保存规则之前就看出 SQL 长什么样。</p>
     *
     * <p>入参 {@code params} 是自由键值对，这里额外识别三个约定键
     * {@code expectedValue} / {@code warnThreshold} / {@code errorThreshold} —— 它们
     * 在契约里是规则字段而非模板占位符，但预览时需要参与渲染（值域规则的判定谓词就来自期望值）。</p>
     */
    public String previewSql(Long templateId, Long datasourceId, String databaseName, String tableName,
                             String columnName, Map<String, String> params) {
        DqRuleTemplate template = getById(templateId);
        Map<String, String> safeParams = params == null ? Map.of() : new LinkedHashMap<>(params);

        DqRule pseudo = new DqRule();
        pseudo.setRuleType(template.getRuleType());
        pseudo.setTemplateId(template.getId());
        pseudo.setDatasourceId(datasourceId);
        pseudo.setDatabaseName(databaseName);
        pseudo.setTableName(tableName);
        pseudo.setColumnName(columnName);
        pseudo.setPartitionExpr(safeParams.get("partitionExpr"));
        pseudo.setRefDatabaseName(safeParams.get("refDatabaseName"));
        pseudo.setRefTableName(safeParams.get("refTableName"));
        pseudo.setRefColumnName(safeParams.get("refColumnName"));
        pseudo.setExpectedValue(safeParams.get("expectedValue"));
        pseudo.setWarnThreshold(safeParams.get("warnThreshold"));
        pseudo.setErrorThreshold(safeParams.get("errorThreshold"));
        // 其余键才是真正的模板占位符参数
        Map<String, String> templateParams = new LinkedHashMap<>(safeParams);
        templateParams.keySet().removeAll(Set.of(
                "expectedValue", "warnThreshold", "errorThreshold",
                "partitionExpr", "refDatabaseName", "refTableName", "refColumnName"));
        pseudo.setParams(toJson(templateParams));

        return dqExecutionService.compileForPreview(template, pseudo, LocalDate.now()).sql();
    }

    // ==================================================================
    // 映射与校验
    // ==================================================================

    static QualityRuleTemplateVO toVO(DqRuleTemplate entity) {
        QualityRuleTemplateVO vo = new QualityRuleTemplateVO();
        vo.setId(entity.getId());
        vo.setTemplateCode(entity.getTemplateCode());
        vo.setTemplateName(entity.getTemplateName());
        vo.setRuleType(entity.getRuleType());
        vo.setTemplateKind(entity.getTemplateKind());
        vo.setSqlTemplate(entity.getSqlTemplate());
        vo.setFieldTypes(entity.getFieldTypes());
        vo.setParamSchema(entity.getParamSchema());
        vo.setRemark(entity.getRemark());
        vo.setEnabled(entity.getEnabled() == null || entity.getEnabled() == 1);
        return vo;
    }

    private void applyDto(DqRuleTemplate entity, QualityTemplateSaveDTO dto) {
        entity.setTemplateCode(dto.getTemplateCode().trim());
        entity.setTemplateName(dto.getTemplateName().trim());
        entity.setRuleType(dto.getRuleType().trim());
        // 用户创建的模板一律是自定义模板，「内置」语义由平台保留
        entity.setTemplateKind(QualityTemplateKind.CUSTOM.getCode());
        entity.setSqlTemplate(dto.getSqlTemplate());
        entity.setFieldTypes(dto.getFieldTypes());
        entity.setParamSchema(dto.getParamSchema());
        entity.setRemark(dto.getRemark());
        entity.setEnabled(Boolean.FALSE.equals(dto.getEnabled()) ? 0 : 1);
    }

    private void validate(QualityTemplateSaveDTO dto, boolean requireNew) {
        if (dto == null) {
            throw new BusinessException("模板内容不能为空");
        }
        if (requireNew && dto.getId() != null) {
            throw new BusinessException("新增模板不应携带 id");
        }
        if (dto.getTemplateCode() == null || dto.getTemplateCode().isBlank()) {
            throw new BusinessException("模板编码不能为空");
        }
        if (dto.getTemplateName() == null || dto.getTemplateName().isBlank()) {
            throw new BusinessException("模板名称不能为空");
        }
        if (dto.getSqlTemplate() == null || dto.getSqlTemplate().isBlank()) {
            throw new BusinessException("SQL 模板不能为空");
        }
        // 自定义模板仍须归属 7 类之一：实际值怎么算只能由规则类型决定
        QualityRuleType ruleType = QualityRuleType.fromCode(dto.getRuleType());
        if (ruleType == null) {
            throw new BusinessException("模板必须归属 7 类规则类型之一，当前为: " + dto.getRuleType());
        }
        if (!DqSqlBuilder.isSingleSelectStatement(dto.getSqlTemplate())) {
            throw new BusinessException("SQL 模板必须是单条 SELECT/WITH 查询：校验动作只允许读取数据，"
                    + "且不得包含语句分隔符");
        }
    }

    private void assertCustom(DqRuleTemplate template, String action) {
        if (QualityTemplateKind.BUILTIN.getCode().equalsIgnoreCase(template.getTemplateKind())) {
            throw new BusinessException("内置模板由平台维护，不支持" + action
                    + "。如需新口径，请新建自定义模板。");
        }
    }

    private static String toJson(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(entry.getKey())).append("\":\"")
                    .append(escape(entry.getValue())).append('"');
        }
        return sb.append('}').toString();
    }

    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
