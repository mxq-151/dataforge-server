package com.bigdata.modules.development.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;

@TableName("dev_file_meta")
public class DevFileMeta extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String filePath;

    private String datasources;

    private String jarDependencyCatalogs;

    private String remark;

    private String engine;

    private String runParams;

    private String computeResources;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getDatasources() {
        return datasources;
    }

    public void setDatasources(String datasources) {
        this.datasources = datasources;
    }

    public String getJarDependencyCatalogs() {
        return jarDependencyCatalogs;
    }

    public void setJarDependencyCatalogs(String jarDependencyCatalogs) {
        this.jarDependencyCatalogs = jarDependencyCatalogs;
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

    public String getRunParams() {
        return runParams;
    }

    public void setRunParams(String runParams) {
        this.runParams = runParams;
    }

    public String getComputeResources() {
        return computeResources;
    }

    public void setComputeResources(String computeResources) {
        this.computeResources = computeResources;
    }
}
