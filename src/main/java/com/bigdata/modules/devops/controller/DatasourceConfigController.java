package com.bigdata.modules.devops.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.devops.dto.DatasourceConfigDTO;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.devops.service.DatasourceConfigService;
import com.bigdata.modules.devops.vo.ConnectionTestResult;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/devops/datasource")
public class DatasourceConfigController {

    @Autowired
    private DatasourceConfigService datasourceConfigService;

    @GetMapping("/list")
    public Result<Page<DatasourceConfig>> getList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String datasourceName,
            @RequestParam(required = false) DatasourceType datasourceType) {
        Page<DatasourceConfig> result = datasourceConfigService.getDatasourceList(current, size, datasourceName, datasourceType);
        return Result.success(result);
    }

    @GetMapping("/all")
    public Result<List<DatasourceConfig>> getAllDatasources() {
        List<DatasourceConfig> list = datasourceConfigService.getAllDatasources();
        return Result.success(list);
    }

    @GetMapping("/{id}")
    public Result<DatasourceConfig> getById(@PathVariable Long id) {
        DatasourceConfig config = datasourceConfigService.getDatasourceById(id);
        return Result.success(config);
    }

    @PostMapping
    public Result<Void> save(@Valid @RequestBody DatasourceConfigDTO dto) {
        DatasourceConfig config = new DatasourceConfig();
        BeanUtils.copyProperties(dto, config);
        datasourceConfigService.saveDatasource(config);
        return Result.success();
    }

    @PutMapping
    public Result<Void> update(@Valid @RequestBody DatasourceConfigDTO dto) {
        DatasourceConfig config = new DatasourceConfig();
        BeanUtils.copyProperties(dto, config);
        datasourceConfigService.updateDatasource(config);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        datasourceConfigService.deleteDatasource(id);
        return Result.success();
    }

    @PostMapping("/{id}/test")
    public Result<ConnectionTestResult> testConnection(@PathVariable Long id) {
        ConnectionTestResult result = datasourceConfigService.testConnection(id);
        return Result.success(result);
    }

    @PostMapping("/test")
    public Result<ConnectionTestResult> testConnectionByConfig(@Valid @RequestBody DatasourceConfigDTO dto) {
        ConnectionTestResult result = datasourceConfigService.testConnectionByConfig(dto);
        return Result.success(result);
    }

    @GetMapping("/types")
    public Result<List<DatasourceType>> getDatasourceTypes() {
        return Result.success(DatasourceType.allTypes());
    }
}
