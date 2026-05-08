package com.bigdata.modules.devops.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.devops.entity.StorageConfig;
import com.bigdata.modules.devops.service.StorageConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/devops/storage")
public class StorageConfigController {

    @Autowired
    private StorageConfigService storageConfigService;


    @GetMapping("/list")
    public Result<Page<StorageConfig>> getList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String storageName,
            @RequestParam(required = false) String storageType) {
        Page<StorageConfig> result = storageConfigService.getStorageList(current, size, storageName, storageType);
        return Result.success(result);
    }

    @GetMapping("/all")
    public Result<List<StorageConfig>> getAllStorages() {
        List<StorageConfig> list = storageConfigService.getAllStorages();
        return Result.success(list);
    }

    @GetMapping("/{id}")
    public Result<StorageConfig> getById(@PathVariable Long id) {
        StorageConfig config = storageConfigService.getStorageById(id);
        return Result.success(config);
    }

    @PostMapping
    public Result<Void> save(@RequestBody StorageConfig config) {
        storageConfigService.saveStorage(config);
        return Result.success();
    }

    @PutMapping
    public Result<Void> update(@RequestBody StorageConfig config) {
        storageConfigService.updateStorage(config);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        storageConfigService.deleteStorage(id);
        return Result.success();
    }

    @PostMapping("/{id}/test")
    public Result<Map<String, Object>> testConnection(@PathVariable Long id) {
        Map<String, Object> result = storageConfigService.testConnection(id);
        return Result.success(result);
    }

    @GetMapping("/types")
    public Result<List<String>> getStorageTypes() {
        List<String> types = storageConfigService.getStorageTypes();
        return Result.success(types);
    }
}
