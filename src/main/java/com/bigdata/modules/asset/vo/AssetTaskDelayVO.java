package com.bigdata.modules.asset.vo;

import java.io.Serializable;
import java.util.List;

/**
 * 任务延迟率（延迟 = 实际开始晚于计划开始）。
 *
 * <p>字段名与前端 {@code AssetTaskDelayVO} 逐字对应。</p>
 *
 * <h3>byQueue 为什么通常是空数组</h3>
 * <p>按队列拆分需要 YARN 的队列维度。本平台没有 YARN，因此 {@code byQueue} 恒为空数组；
 * 前端的队列表在该情况下不渲染，整体延迟率仍然可用（它只依赖调度系统的计划/实际开始时间）。</p>
 *
 * @author dataforge
 */
public class AssetTaskDelayVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 统计窗口内任务实例总数 */
    private Integer totalTasks;

    /** 延迟任务数（实际开始晚于计划开始） */
    private Integer delayedTasks;

    /** 延迟率 0~1 小数 */
    private Double delayRate;

    /** 统计窗口天数（回显用） */
    private Integer windowDays;

    /** 按队列拆分；无 YARN 数据源时为空数组 */
    private List<QueueStat> byQueue;

    /** 队列维度统计项 */
    public static class QueueStat implements Serializable {

        private static final long serialVersionUID = 1L;

        private String queueName;
        private Integer totalTasks;
        private Integer delayedTasks;
        private Double delayRate;

        public String getQueueName() { return queueName; }
        public void setQueueName(String queueName) { this.queueName = queueName; }
        public Integer getTotalTasks() { return totalTasks; }
        public void setTotalTasks(Integer totalTasks) { this.totalTasks = totalTasks; }
        public Integer getDelayedTasks() { return delayedTasks; }
        public void setDelayedTasks(Integer delayedTasks) { this.delayedTasks = delayedTasks; }
        public Double getDelayRate() { return delayRate; }
        public void setDelayRate(Double delayRate) { this.delayRate = delayRate; }
    }

    public Integer getTotalTasks() { return totalTasks; }
    public void setTotalTasks(Integer totalTasks) { this.totalTasks = totalTasks; }
    public Integer getDelayedTasks() { return delayedTasks; }
    public void setDelayedTasks(Integer delayedTasks) { this.delayedTasks = delayedTasks; }
    public Double getDelayRate() { return delayRate; }
    public void setDelayRate(Double delayRate) { this.delayRate = delayRate; }
    public Integer getWindowDays() { return windowDays; }
    public void setWindowDays(Integer windowDays) { this.windowDays = windowDays; }
    public List<QueueStat> getByQueue() { return byQueue; }
    public void setByQueue(List<QueueStat> byQueue) { this.byQueue = byQueue; }
}
