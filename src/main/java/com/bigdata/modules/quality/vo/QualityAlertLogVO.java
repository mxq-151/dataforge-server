package com.bigdata.modules.quality.vo;

import java.time.LocalDateTime;

/**
 * 告警记录（站内告警箱）。
 *
 * <p>对应 {@code dq_alert_log} 一行：某个任务的某条规则命中订阅条件时产生的一条通知记录。
 * 「站内记录」渠道（INBOX）落表即送达，因此这张表本身就是可用的告警箱 —— 之前缺的只是查询入口。</p>
 *
 * <p>{@code status} 是<b>投递状态</b>（SENT / PENDING / FAILED），衡量"通知发出去了没有"；
 * {@code checkStatus} 是<b>触发状态</b>（WARN / ERROR / FAILED），衡量"因为什么触发"。
 * 后者不在告警表里，是按 {@code runId + ruleId} 回到校验结果表取出来的 ——
 * 没有它，告警箱只能看到"某条规则告警了"，看不到严重程度。</p>
 */
public class QualityAlertLogVO {

    private Long id;

    private Long runId;

    private Long taskId;

    /** 任务名（由 taskId 反查，便于列表直读） */
    private String taskName;

    private Long ruleId;

    private String ruleName;

    /** 渠道编码：INBOX / EMAIL / WEBHOOK */
    private String channel;

    private String receiver;

    /** 投递状态：SENT 已送达 / PENDING 待发 / FAILED 投递失败 */
    private String status;

    /** 投递失败或未投递的原因；已送达时为空 */
    private String failReason;

    /** 实际送达时间；待发记录为空（带时间会误导排查） */
    private LocalDateTime sendTime;

    /** 触发本次告警的校验结果状态：WARN / ERROR / FAILED */
    private String checkStatus;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
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
    public String getCheckStatus() { return checkStatus; }
    public void setCheckStatus(String checkStatus) { this.checkStatus = checkStatus; }
}
