package com.bigdata.modules.quality.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;
import java.time.LocalDateTime;

/**
 * dq_alert_log
 *
 * @author dataforge
 */
@TableName("dq_alert_log")
public class DqAlertLog extends BaseEntity {

    /** 运行实例 ID */
    private Long runId;

    /** 质检任务 ID */
    private Long taskId;

    /** 规则 ID */
    private Long ruleId;

    /** 规则名（结果自包含） */
    private String ruleName;

    /** 告警渠道 site / webhook / email */
    private String channel;

    /** 接收人 */
    private String receiver;

    /** 发送状态 SUCCESS / FAILED */
    private String status;

    /** 失败原因 */
    private String failReason;

    /** 发送时间 */
    private LocalDateTime sendTime;

    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getReceiver() { return receiver; }
    public void setReceiver(String receiver) { this.receiver = receiver; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public LocalDateTime getSendTime() { return sendTime; }
    public void setSendTime(LocalDateTime sendTime) { this.sendTime = sendTime; }
}
