package com.bigdata.modules.development.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

public class CodeExecutionRequest {

    @NotBlank(message = "代码内容不能为空")
    private String codeContent;

    @NotBlank(message = "代码类型不能为空")
    private String codeType;

    @NotBlank(message = "引擎类型不能为空")
    private String engineType;

    private List<String> datasources;

    private List<String> jarDependencyCatalogs;

    private Map<String, Object> runParams;

    private Map<String, Object> computeResources;

    private String remark;

    public String getCodeContent() {
        return codeContent;
    }

    public void setCodeContent(String codeContent) {
        this.codeContent = codeContent;
    }

    public String getCodeType() {
        return codeType;
    }

    public void setCodeType(String codeType) {
        this.codeType = codeType;
    }

    public String getEngineType() {
        return engineType;
    }

    public void setEngineType(String engineType) {
        this.engineType = engineType;
    }

    public List<String> getDatasources() {
        return datasources;
    }

    public void setDatasources(List<String> datasources) {
        this.datasources = datasources;
    }

    public List<String> getJarDependencyCatalogs() {
        return jarDependencyCatalogs;
    }

    public void setJarDependencyCatalogs(List<String> jarDependencyCatalogs) {
        this.jarDependencyCatalogs = jarDependencyCatalogs;
    }

    public Map<String, Object> getRunParams() {
        return runParams;
    }

    public void setRunParams(Map<String, Object> runParams) {
        this.runParams = runParams;
    }

    public Map<String, Object> getComputeResources() {
        return computeResources;
    }

    public void setComputeResources(Map<String, Object> computeResources) {
        this.computeResources = computeResources;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
