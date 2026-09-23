package com.bigdata.modules.quality.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;

/**
 * dq_task_rule_rel
 *
 * @author dataforge
 */
@TableName("dq_task_rule_rel")
public class DqTaskRuleRel extends BaseEntity {

    /** 质检任务 ID */
    private Long taskId;

    /** 监控规则 ID */
    private Long ruleId;

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
}
