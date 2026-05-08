package com.bigdata.modules.development.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.development.entity.DevJarDependency;
import com.bigdata.modules.development.service.DevJarDependencyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/devops/dependency")
public class DevJarDependencyController {

    @Autowired
    private DevJarDependencyService devJarDependencyService;

    @GetMapping("/list")
    public Result<Page<DevJarDependency>> getList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String jarName,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String category) {
        Page<DevJarDependency> result = devJarDependencyService.getJarList(current, size, jarName, source, scope, category);
        return Result.success(result);
    }

    @GetMapping("/all")
    public Result<List<DevJarDependency>> getAllJars() {
        List<DevJarDependency> list = devJarDependencyService.getAllJars();
        return Result.success(list);
    }

    @GetMapping("/{id}")
    public Result<DevJarDependency> getById(@PathVariable Long id) {
        DevJarDependency jar = devJarDependencyService.getJarById(id);
        return Result.success(jar);
    }

    @PostMapping
    public Result<Void> save(@RequestBody DevJarDependency jar) {
        devJarDependencyService.saveJar(jar);
        return Result.success();
    }

    @PutMapping
    public Result<Void> update(@RequestBody DevJarDependency jar) {
        devJarDependencyService.updateJar(jar);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        devJarDependencyService.deleteJar(id);
        return Result.success();
    }

    @GetMapping("/sources")
    public Result<List<String>> getJarSources() {
        List<String> sources = devJarDependencyService.getJarSources();
        return Result.success(sources);
    }

    @GetMapping("/scopes")
    public Result<List<String>> getJarScopes() {
        List<String> scopes = devJarDependencyService.getJarScopes();
        return Result.success(scopes);
    }

    @GetMapping("/categories")
    public Result<List<String>> getJarCategories() {
        List<String> categories = devJarDependencyService.getJarCategories();
        return Result.success(categories);
    }
}
