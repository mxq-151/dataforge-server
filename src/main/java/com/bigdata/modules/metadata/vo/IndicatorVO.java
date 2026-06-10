package com.bigdata.modules.metadata.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

public class IndicatorVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String projectName;
    private String topic;
    private String name;
    private String alias;
    private String businessDefinition;
    private String defaultFilter;
    private String alertConfig;
    private String businessOwner;
    private String techOwner;
    private Integer refreshInterval;
    private String refreshUnit;
    private String refreshDesc;
    private String visibility;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<IndicatorFieldVO> fields;

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

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public String getBusinessDefinition() {
        return businessDefinition;
    }

    public void setBusinessDefinition(String businessDefinition) {
        this.businessDefinition = businessDefinition;
    }

    public String getDefaultFilter() {
        return defaultFilter;
    }

    public void setDefaultFilter(String defaultFilter) {
        this.defaultFilter = defaultFilter;
    }

    public String getAlertConfig() {
        return alertConfig;
    }

    public void setAlertConfig(String alertConfig) {
        this.alertConfig = alertConfig;
    }

    public String getBusinessOwner() {
        return businessOwner;
    }

    public void setBusinessOwner(String businessOwner) {
        this.businessOwner = businessOwner;
    }

    public String getTechOwner() {
        return techOwner;
    }

    public void setTechOwner(String techOwner) {
        this.techOwner = techOwner;
    }

    public Integer getRefreshInterval() {
        return refreshInterval;
    }

    public void setRefreshInterval(Integer refreshInterval) {
        this.refreshInterval = refreshInterval;
    }

    public String getRefreshUnit() {
        return refreshUnit;
    }

    public void setRefreshUnit(String refreshUnit) {
        this.refreshUnit = refreshUnit;
    }

    public String getRefreshDesc() {
        return refreshDesc;
    }

    public void setRefreshDesc(String refreshDesc) {
        this.refreshDesc = refreshDesc;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<IndicatorFieldVO> getFields() {
        return fields;
    }

    public void setFields(List<IndicatorFieldVO> fields) {
        this.fields = fields;
    }
}
