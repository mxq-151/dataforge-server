package com.bigdata.modules.development.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.modules.development.entity.DevProject;
import com.bigdata.modules.development.mapper.DevProjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

@Service
public class DevProjectService {

    private static final Logger log = LoggerFactory.getLogger(DevProjectService.class);

    @Autowired
    private  DevProjectMapper devProjectMapper;

 

    public Page<DevProject> getProjectList(int current, int size, String projectName, String projectCode, Integer status) {
        Page<DevProject> page = new Page<>(current, size);
        LambdaQueryWrapper<DevProject> wrapper = new LambdaQueryWrapper<>();

        if (projectName != null && !projectName.isEmpty()) {
            wrapper.like(DevProject::getProjectName, projectName);
        }
        if (projectCode != null && !projectCode.isEmpty()) {
            wrapper.like(DevProject::getProjectCode, projectCode);
        }
        if (status != null) {
            wrapper.eq(DevProject::getStatus, status);
        }

        wrapper.orderByDesc(DevProject::getCreateTime);

        return devProjectMapper.selectPage(page, wrapper);
    }

    public DevProject getProjectById(Long id) {
        return devProjectMapper.selectById(id);
    }

    @Transactional
    public void saveProject(DevProject project) throws IOException {
        devProjectMapper.insert(project);
        log.info("项目创建成功并初始化Git仓库: projectId={}, projectName={}",
                project.getId(), project.getProjectName());
    }

    @Transactional
    public void updateProject(DevProject project) {
        devProjectMapper.updateById(project);
    }

    @Transactional
    public void deleteProject(Long id) throws IOException {
        devProjectMapper.deleteById(id);
        log.info("项目已删除: projectId={}", id);
    }
}
