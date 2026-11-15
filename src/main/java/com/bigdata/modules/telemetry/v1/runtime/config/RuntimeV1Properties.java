package com.bigdata.modules.telemetry.v1.runtime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * v1 运行时端点配置（{@code telemetry.v1.runtime.*}）。
 *
 * <p>{@code history-capacity} 是 compare 进程内环形缓冲的容量，默认 10；
 * 缓冲不持久化，进程重启即丢，多实例部署各自独立、不可互比。</p>
 */
@Component
@ConfigurationProperties(prefix = "telemetry.v1.runtime")
public class RuntimeV1Properties {

    private int historyCapacity = 10;
    private int maxComponents = 100;
    private int maxVertices = 20;
    private int checkpointHistory = 5;
    private int defaultWindowSeconds = 60;

    public int getHistoryCapacity() {
        return historyCapacity;
    }

    public void setHistoryCapacity(int historyCapacity) {
        this.historyCapacity = historyCapacity;
    }

    public int getMaxComponents() {
        return maxComponents;
    }

    public void setMaxComponents(int maxComponents) {
        this.maxComponents = maxComponents;
    }

    public int getMaxVertices() {
        return maxVertices;
    }

    public void setMaxVertices(int maxVertices) {
        this.maxVertices = maxVertices;
    }

    public int getCheckpointHistory() {
        return checkpointHistory;
    }

    public void setCheckpointHistory(int checkpointHistory) {
        this.checkpointHistory = checkpointHistory;
    }

    public int getDefaultWindowSeconds() {
        return defaultWindowSeconds;
    }

    public void setDefaultWindowSeconds(int defaultWindowSeconds) {
        this.defaultWindowSeconds = defaultWindowSeconds;
    }
}
