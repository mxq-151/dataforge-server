package com.bigdata.modules.devops.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.devops.dto.StorageConfigDTO;
import com.bigdata.modules.devops.entity.StorageConfig;
import com.bigdata.modules.devops.service.StorageConfigService;
import com.bigdata.modules.devops.vo.StorageConfigVO;
import com.bigdata.modules.devops.vo.StorageConnectionTestVO;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/devops/storage")
public class StorageConfigController {

    @Autowired
    private StorageConfigService storageConfigService;

    @GetMapping("/list")
    public Result<Page<StorageConfigVO>> getList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String storageName,
            @RequestParam(required = false) String storageType) {
        Page<StorageConfig> page = storageConfigService.getStorageList(current, size, storageName, storageType);
        Page<StorageConfigVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVO).collect(Collectors.toList()));
        return Result.success(voPage);
    }

    @GetMapping("/all")
    public Result<List<StorageConfigVO>> getAllStorages() {
        List<StorageConfig> list = storageConfigService.getAllStorages();
        List<StorageConfigVO> voList = list.stream().map(this::toVO).collect(Collectors.toList());
        return Result.success(voList);
    }

    @GetMapping("/{id}")
    public Result<StorageConfigVO> getById(@PathVariable Long id) {
        StorageConfig config = storageConfigService.getStorageById(id);
        return Result.success(toVO(config));
    }

    @PostMapping
    public Result<Void> save(@Valid @RequestBody StorageConfigDTO dto) {
        StorageConfig config = new StorageConfig();
        BeanUtils.copyProperties(dto, config);
        storageConfigService.saveStorage(config);
        return Result.success();
    }

    @PutMapping
    public Result<Void> update(@Valid @RequestBody StorageConfigDTO dto) {
        StorageConfig config = new StorageConfig();
        BeanUtils.copyProperties(dto, config);
        storageConfigService.updateStorage(config);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        storageConfigService.deleteStorage(id);
        return Result.success();
    }

    @PostMapping("/{id}/test")
    public Result<StorageConnectionTestVO> testConnection(@PathVariable Long id) {
        StorageConnectionTestVO result = storageConfigService.testConnection(id);
        return Result.success(result);
    }

    @GetMapping("/types")
    public Result<List<String>> getStorageTypes() {
        List<String> types = storageConfigService.getStorageTypes();
        return Result.success(types);
    }

    private StorageConfigVO toVO(StorageConfig entity) {
        StorageConfigVO vo = new StorageConfigVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
