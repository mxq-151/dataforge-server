package com.bigdata.modules.development.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.development.dto.DevJarDependencyDTO;
import com.bigdata.modules.development.entity.DevJarDependency;
import com.bigdata.modules.development.service.DevJarDependencyService;
import com.bigdata.modules.development.vo.DevJarDependencyVO;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/devops/dependency")
public class DevJarDependencyController {

    @Autowired
    private DevJarDependencyService devJarDependencyService;

    @GetMapping("/list")
    public Result<Page<DevJarDependencyVO>> getList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String jarName,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String category) {
        Page<DevJarDependency> page = devJarDependencyService.getJarList(current, size, jarName, source, scope, category);
        Page<DevJarDependencyVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVO).collect(Collectors.toList()));
        return Result.success(voPage);
    }

    @PostMapping
    public Result<Void> save(@Valid @RequestBody DevJarDependencyDTO dto) {
        DevJarDependency jar = new DevJarDependency();
        BeanUtils.copyProperties(dto, jar);
        devJarDependencyService.saveJar(jar);
        return Result.success();
    }

    @PutMapping
    public Result<Void> update(@Valid @RequestBody DevJarDependencyDTO dto) {
        DevJarDependency jar = new DevJarDependency();
        BeanUtils.copyProperties(dto, jar);
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

    private DevJarDependencyVO toVO(DevJarDependency entity) {
        DevJarDependencyVO vo = new DevJarDependencyVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
