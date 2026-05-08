package com.bigdata.modules.development.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class CreateFolderRequest {

    @NotBlank(message = "文件夹名称不能为空")
    private String name;

    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    private String parentPath;

    private String description;

    public CreateFolderRequest() {
    }

    public CreateFolderRequest(String name, Long projectId, String parentPath, String description) {
        this.name = name;
        this.projectId = projectId;
        this.parentPath = parentPath;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
