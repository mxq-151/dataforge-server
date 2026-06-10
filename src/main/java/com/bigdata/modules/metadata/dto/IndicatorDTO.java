package com.bigdata.modules.metadata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public class IndicatorDTO {

    private Long id;

    @NotBlank(message = "项目名称不能为空")
    @Size(max = 200, message = "项目名称不能超过200字符")
    private String projectName;

    @NotBlank(message = "主题不能为空")
    @Size(max = 200, message = "主题不能超过200字符")
    private String topic;

    @NotBlank(message = "指标名称不能为空")
    @Size(max = 200, message = "指标名称不能超过200字符")
    private String name;

    @Size(max = 100, message = "别名不能超过100字符")
    private String alias;

    private String businessDefinition;
    private String defaultFilter;
    private String alertConfig;

    @Size(max = 100, message = "业务负责人不能超过100字符")
    private String businessOwner;

    @Size(max = 100, message = "技术负责人不能超过100字符")
    private String techOwner;

    private Integer refreshInterval;

    @Size(max = 10, message = "刷新单位不能超过10字符")
    private String refreshUnit;

    @Size(max = 10, message = "刷新描述不能超过10字符")
    private String refreshDesc;

    private String visibility;

    private List<IndicatorFieldDTO> fields;

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

    public List<IndicatorFieldDTO> getFields() {
        return fields;
    }

    public void setFields(List<IndicatorFieldDTO> fields) {
        this.fields = fields;
    }
}
