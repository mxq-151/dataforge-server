package com.bigdata.modules.metadata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public class IndicatorDTO {

    private Long id;

    @NotBlank(message = "项目编码不能为空")
    @Size(max = 200, message = "项目编码不能超过200字符")
    private String projectCode;

    @NotBlank(message = "主题不能为空")
    @Size(max = 200, message = "主题不能超过200字符")
    private String topic;

    @NotBlank(message = "指标名称不能为空")
    @Size(max = 200, message = "指标名称不能超过200字符")
    private String name;

    @NotBlank(message = "指标编码不能为空")
    @Size(max = 1024, message = "指标编码不能超过1024字符")
    private String code;

    private String businessDefinition;

    @Size(max = 1024, message = "计算表达式不能超过1024字符")
    private String calcExpr;

    private String defaultFilter;
    private String alertConfig;

    @Size(max = 1024, message = "业务负责人不能超过1024字符")
    private String businessOwner;

    @Size(max = 1024, message = "技术负责人不能超过1024字符")
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

    public String getProjectCode() {
        return projectCode;
    }

    public void setProjectCode(String projectCode) {
        this.projectCode = projectCode;
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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getBusinessDefinition() {
        return businessDefinition;
    }

    public void setBusinessDefinition(String businessDefinition) {
        this.businessDefinition = businessDefinition;
    }

    public String getCalcExpr() {
        return calcExpr;
    }

    public void setCalcExpr(String calcExpr) {
        this.calcExpr = calcExpr;
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
