package com.bigdata.modules.quality.enums;

import java.util.Arrays;

/**
 * 数据质量规则类型 —— 全链路统一术语，固定 7 类。
 *
 * <p>⚠ 契约：本枚举的 code / label / desc 必须与前端
 * {@code dataforge/src/types/quality.ts} 的 QUALITY_RULE_TYPES **逐字一致**，
 * 且 dq_rule_template 的内置模板种子由同一份文案生成（template_code == rule_type）。
 * 任何口径变更必须三处同时演进，禁止自造同义词。</p>
 *
 * <p>顺序即提示词中的编号，不得调整。</p>
 *
 * @author dataforge
 */
public enum QualityRuleType {

    /** 主键/唯一键重复检测（字段级） */
    PRIMARY_KEY_UNIQUE("主键唯一性", "主键/唯一键重复检测", RuleScope.COLUMN),

    /** 关键字段空值与空串检测（字段级） */
    NOT_NULL("非空", "关键字段空值与空串检测", RuleScope.COLUMN),

    /** 枚举字典取值与数值区间合法性（字段级） */
    VALUE_RANGE("值域", "枚举字典取值与数值区间合法性", RuleScope.COLUMN),

    /** 当日行数相对历史基线的涨跌幅（表级） */
    ROW_COUNT_FLUCTUATION("行数波动", "当日行数相对历史基线的涨跌幅", RuleScope.TABLE),

    /** 当期行数与上期行数的对比（表级） */
    ROW_COUNT_MOM("行数环比", "当期行数与上期行数的对比", RuleScope.TABLE),

    /** 上下游同口径记录数/金额一致性（跨表两表比对） */
    UPSTREAM_DOWNSTREAM_RECON("上下游对账", "上下游同口径记录数/金额一致性", RuleScope.PAIR),

    /** 同一指标在不同表、不同层之间的计算结果一致性（跨表两表比对） */
    METRIC_CONSISTENCY("口径一致性", "同一指标在不同表、不同层之间的计算结果一致性", RuleScope.PAIR);

    /** 校验对象范围：字段级 / 表级 / 两张表比对 */
    public enum RuleScope {
        COLUMN, TABLE, PAIR
    }

    private final String label;
    private final String desc;
    private final RuleScope scope;

    QualityRuleType(String label, String desc, RuleScope scope) {
        this.label = label;
        this.desc = desc;
        this.scope = scope;
    }

    /** 稳定编码，落库用；即 enum name()，不随文案调整 */
    public String getCode() {
        return name();
    }

    public String getLabel() {
        return label;
    }

    public String getDesc() {
        return desc;
    }

    public RuleScope getScope() {
        return scope;
    }

    /** 是否字段级规则（配置界面需要选字段） */
    public boolean isColumnScope() {
        return scope == RuleScope.COLUMN;
    }

    /** 是否跨表规则（配置界面需要选对照表） */
    public boolean isPairScope() {
        return scope == RuleScope.PAIR;
    }

    /** 按编码解析；未知编码返回 null（调用方需自行兜底并拒绝） */
    public static QualityRuleType fromCode(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        return Arrays.stream(values())
                .filter(t -> t.name().equals(trimmed))
                .findFirst()
                .orElse(null);
    }

    /**
     * 按规则类型名（中文 label）反查。
     * <p>用于把智能体在设计文档里写的规则类型名映射为落库编码 ——
     * 「S2 稽核规则清单一键落库」的解析依据。</p>
     */
    public static QualityRuleType fromLabel(String label) {
        if (label == null) {
            return null;
        }
        String trimmed = label.trim();
        return Arrays.stream(values())
                .filter(t -> t.label.equals(trimmed))
                .findFirst()
                .orElse(null);
    }
}
