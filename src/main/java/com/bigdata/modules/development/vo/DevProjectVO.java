package com.bigdata.modules.development.vo;

import java.io.Serializable;
import java.time.LocalDateTime;

public class DevProjectVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String projectName;
    private String projectCode;
    private String description;
    private Long ownerId;
    private String ownerName;
    private Long defaultDatasourceId;
    private Integer status;
    private Integer scriptCount;
    private Integer scheduledCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getProjectCode() {
        return projectCode;
    }

    public void setProjectCode(String projectCode) {
        this.projectCode = projectCode;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public Long getDefaultDatasourceId() {
        return defaultDatasourceId;
    }

    public void setDefaultDatasourceId(Long defaultDatasourceId) {
        this.defaultDatasourceId = defaultDatasourceId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getScriptCount() {
        return scriptCount;
    }

    public void setScriptCount(Integer scriptCount) {
        this.scriptCount = scriptCount;
    }

    public Integer getScheduledCount() {
        return scheduledCount;
    }

    public void setScheduledCount(Integer scheduledCount) {
        this.scheduledCount = scheduledCount;
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
