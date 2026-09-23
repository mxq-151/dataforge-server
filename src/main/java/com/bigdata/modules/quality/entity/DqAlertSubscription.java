package com.bigdata.modules.quality.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;

/**
 * dq_alert_subscription
 *
 * @author dataforge
 */
@TableName("dq_alert_subscription")
public class DqAlertSubscription extends BaseEntity {

    /** 质检任务 ID */
    private Long taskId;

    /** 触发告警的结果状态集合，逗号分隔，如 ERROR,FAILED */
    private String notifyOn;

    /** 渠道集合（逗号分隔） */
    private String channels;

    /** 接收人集合（逗号分隔） */
    private String receivers;

    /** 是否启用 */
    private Integer enabled;

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getNotifyOn() { return notifyOn; }
    public void setNotifyOn(String notifyOn) { this.notifyOn = notifyOn; }
    public String getChannels() { return channels; }
    public void setChannels(String channels) { this.channels = channels; }
    public String getReceivers() { return receivers; }
    public void setReceivers(String receivers) { this.receivers = receivers; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
}
