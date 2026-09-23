package com.bigdata.modules.quality.dto;

import com.bigdata.modules.quality.dto.QualityBatchTargetDTO;
import com.bigdata.modules.quality.dto.QualityRuleSaveDTO;
import java.util.List;

/**
 * QualityBatchByTemplateDTO
 *
 * <p>字段名与前端 src/api/quality.ts 的入参类型逐字对应。</p>
 *
 * @author dataforge
 */
public class QualityBatchByTemplateDTO {

    /** 模板 ID */
    private Long templateId;

    /** 批量目标表/字段 */
    private List<QualityBatchTargetDTO> targets;

    /** 批量生成规则的默认属性 */
    private QualityRuleSaveDTO defaults;

    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public List<QualityBatchTargetDTO> getTargets() { return targets; }
    public void setTargets(List<QualityBatchTargetDTO> targets) { this.targets = targets; }
    public QualityRuleSaveDTO getDefaults() { return defaults; }
    public void setDefaults(QualityRuleSaveDTO defaults) { this.defaults = defaults; }
}
