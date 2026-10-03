package com.bigdata.modules.development.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 程序运行参数表。
 * <p>
 * 主键为联合主键 (project_id, file_path)，无自增 id 列。
 * upsert 由数据库原生 SQL 实现（见 mapper/{mysql,mariadb,postgresql,h2}/ProgramParamsMapper.xml）。
 */
@TableName("dev_program_params")
public class ProgramParams implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long projectId;

    private String filePath;

    private String engine;

    private String datasources;

    private String jarDependencyCatalogs;

    private String runParams;

    private String computeResources;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

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

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
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

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
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
