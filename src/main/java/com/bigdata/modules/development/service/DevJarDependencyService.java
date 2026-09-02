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
        if (jar.getSource().equalsIgnoreCase("custom") && (jarPath == null || jarPath.trim().isEmpty()))  {
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

    /**
     * 将 JAR 目录名（catalog）解析为完整的文件路径 {@code basePath/{jarPath}}。
     * <p>
     * 目录名可能是 JAR 名称、artifactId 或 jarPath，这里统一按 jarName/jarPath 精确匹配，
     * 解析后拼接配置文件中的 jar.base-path，得到 Spark/Flink 提交时 --jars 使用的完整路径。
     */
    public List<String> resolveJarFullPaths(List<String> catalogs) {
        List<String> result = new java.util.ArrayList<>();
        if (catalogs == null || catalogs.isEmpty()) {
            return result;
        }
        String basePath = engineProperties.getJar().getBasePath();
        for (String catalog : catalogs) {
            if (catalog == null || catalog.trim().isEmpty()) {
                continue;
            }
            String fullPath = resolveToFullPath(catalog.trim(), basePath);
            if (fullPath != null) {
                result.add(fullPath);
            }
        }
        return result;
    }

    private String resolveToFullPath(String catalog, String basePath) {
        // 已含基础路径的绝对/相对完整路径，直接返回
        if (catalog.startsWith("/") || catalog.contains("://")) {
            return catalog;
        }
        // 精确匹配 jarName 或 jarPath
        LambdaQueryWrapper<DevJarDependency> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(q -> q.eq(DevJarDependency::getJarName, catalog)
                         .or().eq(DevJarDependency::getJarPath, catalog))
               .eq(DevJarDependency::getStatus, 1)
               .last("LIMIT 1");
        DevJarDependency dep = devJarDependencyMapper.selectOne(wrapper);
        String jarPath = dep != null ? dep.getJarPath() : catalog;
        return basePath.trim().endsWith("/") ? basePath + jarPath : basePath + "/" + jarPath;
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
