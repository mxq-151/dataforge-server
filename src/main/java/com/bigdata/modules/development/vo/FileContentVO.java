package com.bigdata.modules.development.vo;

import java.util.List;
import java.util.Map;

public class FileContentVO {

    private String name;
    private String language;
    private String content;
    private Long projectId;
    private String parentPath;
    private String remark;
    private String engine;
    private List<String> datasources;
    private List<String> jarDependencyCatalogs;
    private Map<String, Object> runParams;
    private Map<String, Object> computeResources;
    private String filePath;
    private Integer size;

    public FileContentVO() {
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

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
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

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Integer getSize() {
        return size;
    }

    public void setSize(Integer size) {
        this.size = size;
    }
}
