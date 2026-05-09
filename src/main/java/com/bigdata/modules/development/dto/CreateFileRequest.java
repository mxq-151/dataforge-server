package com.bigdata.modules.development.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

public class CreateFileRequest {

    @NotBlank(message = "文件名称不能为空")
    private String name;

    private String language;

    private String content;

    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    @NotNull(message = "父目录不能为空")
    private String parentPath;

    private String remark;

    private String message;

    private List<String> datasources;

    private List<String> jarDependencyCatalogs;

    private String engine;

    private Map<String, Object> runParams;

    private Map<String, Object> computeResources;

    private String filePath;

    public CreateFileRequest() {
    }

    public List<String> getJarDependencyCatalogs()
    {
        return this.jarDependencyCatalogs;
    }

    public void setJarDependencyCatalogs(List<String> jarDependencyCatalogs)
    {
        this.jarDependencyCatalogs=jarDependencyCatalogs;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getParentPath() {
        return parentPath;
    }

    public void setParentPath(String parentPath) {
        this.parentPath = parentPath;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public void setEngine(String engine)
    {
        this.engine=engine;
    }

    public String getEngine()
    {
        return this.engine;
    }

    public void setMessage(String message)
    {
        this.message=message;
    }

    public String getMessage()
    {
        return this.message;
    }

    public List<String> getDatasources()
    {
        return this.datasources;
    }

    public void setDatasources(List<String> datasources)
    {
        this.datasources=datasources;
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

    public void setFilePath(String filePath)
    {
        this.filePath = filePath;
    }

    public String getFilePath()
    {
        return this.filePath;
    }

    public String getInputParamsJson() {
        if (this.runParams == null || this.runParams.isEmpty()) {
            return null;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.writeValueAsString(this.runParams);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize runParams to JSON", e);
        }
    }

    public String getComputeResourcesJson() {
        if (this.computeResources == null || this.computeResources.isEmpty()) {
            return null;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.writeValueAsString(this.computeResources);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize computeResources to JSON", e);
        }
    }
}
