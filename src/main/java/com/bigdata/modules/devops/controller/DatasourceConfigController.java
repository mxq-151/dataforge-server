package com.bigdata.modules.devops.controller;

import com.bigdata.common.annotation.AuditLog;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.devops.dto.DatasourceConfigDTO;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.ConnectionType;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.devops.service.DatasourceConfigService;
import com.bigdata.modules.devops.vo.ConnectionTestResult;
import com.bigdata.modules.devops.vo.DatasourceConfigVO;
import com.bigdata.modules.development.service.execution.ExecutionStrategyFactory;
import com.bigdata.modules.metadata.service.MetadataSyncService;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;


import java.util.List;

import java.util.stream.Collectors;

@RestController
@RequestMapping("/devops/datasource")
public class DatasourceConfigController {

    @Autowired
    private DatasourceConfigService datasourceConfigService;

    @Autowired
    private MetadataSyncService metadataSyncService;

    @Autowired
    private ExecutionStrategyFactory executionStrategyFactory;



    @GetMapping("/list")
    public Result<Page<DatasourceConfigVO>> getList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "100") int size,
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
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @AuditLog(module = "数据源管理", operation = "创建数据源", targetType = "Datasource")
    public Result<Void> save(@Valid @RequestBody DatasourceConfigDTO dto) {
        DatasourceConfig config = new DatasourceConfig();
        BeanUtils.copyProperties(dto, config);
        datasourceConfigService.saveDatasource(config);
        return Result.success();
    }

    @PutMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @AuditLog(module = "数据源管理", operation = "更新数据源", targetType = "Datasource")
    public Result<Void> update(@Valid @RequestBody DatasourceConfigDTO dto) {
        DatasourceConfig config = new DatasourceConfig();
        BeanUtils.copyProperties(dto, config);
        datasourceConfigService.updateDatasource(config);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @AuditLog(module = "数据源管理", operation = "删除数据源", targetType = "Datasource")
    public Result<Void> delete(@PathVariable Long id) {
        datasourceConfigService.deleteDatasource(id);
        return Result.success();
    }

    @PostMapping("/{id}/sync-metadata")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Result<Void> syncMetadata(@PathVariable Long id) {
        boolean success = metadataSyncService.syncAllMetadataByDatasourceId(id);
        if (success) {
            return Result.success();
        } else {
            return Result.error(500, "元数据同步失败");
        }
    }

    @PostMapping("/test")
    public Result<ConnectionTestResult> testConnection(@Valid @RequestBody DatasourceConfigDTO dto,
                                                        @RequestParam(required = false) Long id) {
        ConnectionTestResult result = datasourceConfigService.testConnectionByConfig(dto, id);
        return Result.success(result);
    }

    @GetMapping("/types")
    public Result<List<DatasourceType>> getDatasourceTypes() {
        return Result.success(DatasourceType.allTypes());
    }

    @GetMapping("/connection-types")
    public Result<List<ConnectionType>> getConnectionTypes() {
        return Result.success(ConnectionType.allTypes());
    }

    @PostMapping("/databases")
    public Result<List<String>> getDatabases(@Valid @RequestBody DatasourceConfigDTO dto) {
        DatasourceConfig config =new DatasourceConfig();
        BeanUtils.copyProperties(dto, config);
        List<String> databases = executionStrategyFactory.getStrategy(config).listDatabases(config);
        return Result.success(databases);
    }


    private DatasourceConfigVO toVO(DatasourceConfig entity) {
        DatasourceConfigVO vo = new DatasourceConfigVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
