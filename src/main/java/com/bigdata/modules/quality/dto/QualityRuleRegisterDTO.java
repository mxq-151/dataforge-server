package com.bigdata.modules.quality.dto;

/**
 * 规则登记入参。
 *
 * <p>在 {@link QualityRuleSaveDTO} 的全部字段之上，只多一个可选的 {@code taskId} ——
 * 用来把登记进来的规则**同时挂到某个质检任务**上。</p>
 *
 * <p>为什么需要这一步：登记只让规则「在平台存在」，规则不挂任务时，平台不会执行它、
 * 按项目维度的统计看不到它、按任务配置的告警订阅也订不到它。让程序登记完顺手挂上，
 * 省掉一次人工在界面上补挂的操作（漏挂是静默失效，最难发现）。</p>
 *
 * <p>{@code taskId} 为空表示只登记、不挂任务，行为与不加这个字段时完全一致。</p>
 *
 * @author dataforge
 */
public class QualityRuleRegisterDTO extends QualityRuleSaveDTO {

    /** 可选：登记后把规则挂到这个质检任务上（幂等，已挂则跳过） */
    private Long taskId;

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
}
