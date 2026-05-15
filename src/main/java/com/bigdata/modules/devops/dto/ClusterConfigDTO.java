package com.bigdata.modules.devops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ClusterConfigDTO {

    private Long id;

    @NotBlank(message = "集群名称不能为空")
    @Size(max = 100, message = "集群名称不能超过100字符")
    private String clusterName;

    @NotBlank(message = "集群类型不能为空")
    private String clusterType;

    private String resourceManagerUrl;
    private String k8sApiServer;
    private String grafanaUrl;
    private String dolphinSchedulerUrl;
    private Integer status;

    @Size(max = 500, message = "描述不能超过500字符")
    private String description;

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
}
