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
import com.bigdata.modules.devops.vo.DatasourceTypeMetaVO;
import com.bigdata.modules.development.service.execution.ExecutionStrategyFactory;
import com.bigdata.modules.metadata.service.MetadataSyncService;
import com.bigdata.modules.metadata.service.TableStorageSizeRefreshService;
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
    private com.bigdata.modules.metadata.service.MetadataSyncAlertService metadataSyncAlertService;

    @Autowired
    private ExecutionStrategyFactory executionStrategyFactory;

    @Autowired
    private TableStorageSizeRefreshService tableStorageSizeRefreshService;



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
            // 风险项 R1：全量同步失败同样升级为可观测告警（审计日志 + 告警级日志），不再只体现在返回值里
            DatasourceConfig config = datasourceConfigService.getDatasourceById(id);
            metadataSyncAlertService.alertFullSyncFailed(id,
                    config != null ? config.getDatasourceType() : null, 0,
                    "数据源全量元数据同步失败（部分表同步失败或连接失败）");
            return Result.error(500, "元数据同步失败");
        }
    }

    @PostMapping("/test")
    public Result<ConnectionTestResult> testConnection(@Valid @RequestBody DatasourceConfigDTO dto,
                                                        @RequestParam(required = false) Long id) {
        ConnectionTestResult result = datasourceConfigService.testConnectionByConfig(dto, id);
        return Result.success(result);
    }

    /**
     * 手动刷新单个数据源的表存储大小。
     *
     * <p>与每日 02:15 的定时刷新任务同一口径（{@link TableStorageSizeRefreshService}）：
     * 幂等覆盖写，只更新已纳管表的 size_bytes，取不到大小的表保持原值；
     * 表多/连接慢的数据源耗时会较长，前端需放宽超时。</p>
     *
     * @return 实际更新的表数
     */
    @PostMapping("/{id}/refresh-table-size")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Result<Integer> refreshTableSize(@PathVariable Long id) {
        DatasourceConfig config = datasourceConfigService.getDatasourceById(id);
        if (config == null) {
            return Result.error(404, "数据源不存在");
        }
        return Result.success(tableStorageSizeRefreshService.refreshDatasource(config));
    }

    /**
     * 手动刷新全部数据源的表存储大小，返回刷新摘要（逐数据源隔离失败，单个失败不影响其余）。
     */
    @PostMapping("/refresh-table-size")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Result<TableStorageSizeRefreshService.RefreshResult> refreshAllTableSize() {
        return Result.success(tableStorageSizeRefreshService.refreshAll());
    }

    @GetMapping("/types")
    public Result<List<DatasourceTypeMetaVO>> getDatasourceTypes() {
        return Result.success(DatasourceType.allTypes().stream()
                .map(DatasourceTypeMetaVO::from)
                .collect(Collectors.toList()));
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
