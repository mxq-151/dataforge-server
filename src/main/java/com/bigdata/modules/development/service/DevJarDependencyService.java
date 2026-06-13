package com.bigdata.modules.development.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.development.config.EngineProperties;
import com.bigdata.modules.development.entity.DevJarDependency;
import com.bigdata.modules.development.mapper.DevJarDependencyMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;

@Service
public class DevJarDependencyService {

    @Autowired
    private DevJarDependencyMapper devJarDependencyMapper;

    @Autowired
    private EngineProperties engineProperties;

    public Page<DevJarDependency> getJarList(int current, int size, String jarName, String source, String scope, String category, String programType) {
        Page<DevJarDependency> page = new Page<>(current, size);
        LambdaQueryWrapper<DevJarDependency> wrapper = new LambdaQueryWrapper<>();
        if (jarName != null && !jarName.isEmpty()) {
            wrapper.like(DevJarDependency::getJarName, jarName);
        }
        if (source != null && !source.isEmpty()) {
            wrapper.eq(DevJarDependency::getSource, source);
        }
        if (scope != null && !scope.isEmpty()) {
            wrapper.eq(DevJarDependency::getScope, scope);
        }
        if (category != null && !category.isEmpty()) {
            wrapper.eq(DevJarDependency::getCategory, category);
        }
        if (programType != null && !programType.isEmpty()) {
            wrapper.eq(DevJarDependency::getProgramType, programType);
        }
        wrapper.orderByDesc(DevJarDependency::getCreateTime);
        return devJarDependencyMapper.selectPage(page, wrapper);
    }

    public List<DevJarDependency> getJarsBySource(String source) {
        LambdaQueryWrapper<DevJarDependency> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DevJarDependency::getSource, source)
               .eq(DevJarDependency::getStatus, 1)
               .orderByAsc(DevJarDependency::getJarName);
        return devJarDependencyMapper.selectList(wrapper);
    }

    public void saveJar(DevJarDependency jar) {
        String basePath = engineProperties.getJar().getBasePath();
        if (basePath == null || basePath.trim().isEmpty()) {
            throw new BusinessException("未配置依赖包保存的基础路径(engine.jar.base-path)");
        }
        String jarPath = jar.getJarPath();
        if (jarPath == null || jarPath.trim().isEmpty()) {
            throw new BusinessException("依赖包路径(jarPath)不能为空");
        }
        if (jar.getSource().equals("custom")){
            String fullPath = basePath.trim().endsWith("/") ? basePath + jarPath : basePath + "/" + jarPath;
            File jarFile = new File(fullPath);
            if (!jarFile.exists()) {
                throw new BusinessException("依赖包文件不存在: " + fullPath);
            }
        }
        
        jar.setStatus(1);
        devJarDependencyMapper.insert(jar);
    }

    public void updateJar(DevJarDependency jar) {
        devJarDependencyMapper.updateById(jar);
    }

    public void deleteJar(Long id) {
        DevJarDependency jar = devJarDependencyMapper.selectById(id);
        if (jar == null) {
            throw new BusinessException("依赖包配置不存在");
        }
        devJarDependencyMapper.deleteById(id);
    }

    public List<String> getJarSources() {
        return List.of("system", "custom");
    }

    public List<String> getJarScopes() {
        return List.of("compile", "runtime", "provided", "test");
    }

    public List<String> getJarCategories() {
        LambdaQueryWrapper<DevJarDependency> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(DevJarDependency::getCategory)
               .isNotNull(DevJarDependency::getCategory)
               .ne(DevJarDependency::getCategory, "")
               .groupBy(DevJarDependency::getCategory);
        List<DevJarDependency> list = devJarDependencyMapper.selectList(wrapper);
        return list.stream().map(DevJarDependency::getCategory).toList();
    }

    public List<String> getProgramTypes() {
        return List.of("flink", "spark","common");
    }
}
