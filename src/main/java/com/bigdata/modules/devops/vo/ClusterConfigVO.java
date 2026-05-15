package com.bigdata.modules.devops.vo;

import java.io.Serializable;
import java.time.LocalDateTime;

public class ClusterConfigVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String clusterName;
    private String clusterType;
    private String resourceManagerUrl;
    private String k8sApiServer;
    private String grafanaUrl;
    private String dolphinSchedulerUrl;
    private Integer status;
    private String description;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getClusterName() {
        return clusterName;
    }

    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }

    public String getClusterType() {
        return clusterType;
    }

    public void setClusterType(String clusterType) {
        this.clusterType = clusterType;
    }

    public String getResourceManagerUrl() {
        return resourceManagerUrl;
    }

    public void setResourceManagerUrl(String resourceManagerUrl) {
        this.resourceManagerUrl = resourceManagerUrl;
    }

    public String getK8sApiServer() {
        return k8sApiServer;
    }

    public void setK8sApiServer(String k8sApiServer) {
        this.k8sApiServer = k8sApiServer;
    }

    public String getGrafanaUrl() {
        return grafanaUrl;
    }

    public void setGrafanaUrl(String grafanaUrl) {
        this.grafanaUrl = grafanaUrl;
    }

    public String getDolphinSchedulerUrl() {
        return dolphinSchedulerUrl;
    }

    public void setDolphinSchedulerUrl(String dolphinSchedulerUrl) {
        this.dolphinSchedulerUrl = dolphinSchedulerUrl;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
