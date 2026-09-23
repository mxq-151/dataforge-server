package com.bigdata.modules.quality.vo;


/**
 * QualityAlertSubscriptionVO
 *
 * <p>字段名与前端 src/types/quality.ts 的接口逐字对应。</p>
 *
 * @author dataforge
 */
public class QualityAlertSubscriptionVO {

    /** 订阅 ID */
    private Long id;

    /** 质检任务 ID */
    private Long taskId;

    /** 任务名 */
    private String taskName;

    /** 触发告警的结果状态集合，逗号分隔 */
    private String notifyOn;

    /** 渠道集合 */
    private String channels;

    /** 接收人集合 */
    private String receivers;

    /** 是否启用 */
    private Boolean enabled;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
    public String getNotifyOn() { return notifyOn; }
    public void setNotifyOn(String notifyOn) { this.notifyOn = notifyOn; }
    public String getChannels() { return channels; }
    public void setChannels(String channels) { this.channels = channels; }
    public String getReceivers() { return receivers; }
    public void setReceivers(String receivers) { this.receivers = receivers; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
