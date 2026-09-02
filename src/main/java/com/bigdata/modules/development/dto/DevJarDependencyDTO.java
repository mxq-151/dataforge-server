package com.bigdata.modules.development.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class DevJarDependencyDTO {

    private Long id;

    @NotBlank(message = "JAR名称不能为空")
    @Size(max = 200, message = "JAR名称不能超过200字符")
    private String jarName;

    @NotBlank(message = "groupId不能为空")
    private String groupId;

    @NotBlank(message = "artifactId不能为空")
    private String artifactId;

    @NotBlank(message = "version不能为空")
    private String version;

    @NotBlank(message = "作用域(scope)不能为空")
    @Size(max = 20, message = "作用域不能超过20字符")
    private String scope;

    @NotBlank(message = "来源(source)不能为空")
    @Size(max = 20, message = "来源不能超过20字符")
    private String source;

    private String jarPath;

    private String repositoryUrl;

    private Long fileSize;

    private String checksum;


    private Integer status;

    @Size(max = 500, message = "描述不能超过500字符")
    private String description;

    private String category;

    @NotBlank(message = "程序类型不能为空")
    @Size(max = 20, message = "程序类型不能超过20字符")
    private String programType;

    @NotNull(message = "自动选择(autoChoose)不能为空")
    private Integer autoChoose;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getJarName() {
        return jarName;
    }

    public void setJarName(String jarName) {
        this.jarName = jarName;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getJarPath() {
        return jarPath;
    }

    public void setJarPath(String jarPath) {
        this.jarPath = jarPath;
    }

    public String getRepositoryUrl() {
        return repositoryUrl;
    }

    public void setRepositoryUrl(String repositoryUrl) {
        this.repositoryUrl = repositoryUrl;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getProgramType() {
        return programType;
    }

    public void setProgramType(String programType) {
        this.programType = programType;
    }

    public Integer getAutoChoose() {
        return autoChoose;
    }

    public void setAutoChoose(Integer autoChoose) {
        this.autoChoose = autoChoose;
    }
}
