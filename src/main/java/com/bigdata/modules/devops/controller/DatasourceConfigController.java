package com.bigdata.modules.devops.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.devops.dto.DatasourceConfigDTO;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.devops.service.DatasourceConfigService;
import com.bigdata.modules.devops.vo.ConnectionTestResult;
import com.bigdata.modules.devops.vo.DatasourceConfigVO;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/devops/datasource")
public class DatasourceConfigController {

    @Autowired
    private DatasourceConfigService datasourceConfigService;

    @GetMapping("/list")
    public Result<Page<DatasourceConfigVO>> getList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String datasourceName,
            @RequestParam(required = false) DatasourceType datasourceType) {
        Page<DatasourceConfig> page = datasourceConfigService.getDatasourceList(current, size, datasourceName, datasourceType);
        Page<DatasourceConfigVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVO).collect(Collectors.toList()));
        return Result.success(voPage);
    }

    @GetMapping("/all")
    public Result<List<DatasourceConfigVO>> getAllDatasources() {
        List<DatasourceConfig> list = datasourceConfigService.getAllDatasources();
        List<DatasourceConfigVO> voList = list.stream().map(this::toVO).collect(Collectors.toList());
        return Result.success(voList);
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

    private DatasourceConfigVO toVO(DatasourceConfig entity) {
        DatasourceConfigVO vo = new DatasourceConfigVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
