package com.bigdata.modules.metadata.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.StorageEngineType;
import com.bigdata.modules.devops.mapper.DatasourceConfigMapper;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.service.TableMetadataService;
import com.bigdata.modules.metadata.vo.ColumnMetadataVO;
import com.bigdata.modules.metadata.vo.TableMetadataVO;
import com.bigdata.modules.system.entity.SysTablePermission;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/metadata")
public class MetadataController {

    @Autowired
    private TableMetadataService tableMetadataService;

    @Autowired
    private DataAccessPermissionService dataAccessPermissionService;

    @Autowired
    private DatasourceConfigMapper datasourceConfigMapper;

    @GetMapping("/tables")
    public Result<Page<TableMetadataVO>> getTableList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(required = false) String databaseName,
            @RequestParam(required = false) String tableName,
            @RequestParam(required = false) String keyword) {
        Page<TableMetadata> page = tableMetadataService.getTableList(current, size, datasourceId, databaseName, tableName, keyword);

        if (!dataAccessPermissionService.isAdmin()) {
            String datasourceName = null;
            if (datasourceId != null) {
                DatasourceConfig ds = datasourceConfigMapper.selectById(datasourceId);
                if (ds != null) {
                    datasourceName = ds.getDatasourceName();
                }
            }

            Set<String> accessibleDbNames = dataAccessPermissionService.getAccessibleDatabaseNames(datasourceName);
            Set<String> accessibleTableNames = null;

            if (databaseName != null && datasourceName != null) {
                accessibleTableNames = dataAccessPermissionService.getAccessibleTableNames(datasourceName, databaseName);
            }

            final String dsName = datasourceName;
            final Set<String> dbNames = accessibleDbNames;
            final Set<String> tblNames = accessibleTableNames;

            List<TableMetadata> filtered = page.getRecords().stream()
                    .filter(t -> {
                        if (dsName != null && !dsName.isEmpty()) {
                            if (t.getDatasourceId() != null) {
                                DatasourceConfig ds = datasourceConfigMapper.selectById(t.getDatasourceId());
                                if (ds != null && !ds.getDatasourceName().equals(dsName)) {
                                    return false;
                                }
                            }
                        }
                        if (dbNames != null && !dbNames.isEmpty()) {
                            if (t.getDatabaseName() == null || !dbNames.contains(t.getDatabaseName())) {
                                return false;
                            }
                        }
                        if (tblNames != null && !tblNames.isEmpty()) {
                            if (t.getTableName() == null || !tblNames.contains(t.getTableName())) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .collect(Collectors.toList());
            page.setRecords(filtered);
            page.setTotal(filtered.size());
        }

        Page<TableMetadataVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toTableMetadataVO).collect(Collectors.toList()));
        return Result.success(voPage);
    }

    @GetMapping("/tables/all")
    public Result<Page<TableMetadataVO>> getAllTables(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10000") int size,
            @RequestParam(required = true) String databaseName,
            @RequestParam(required = false) String tableName,
            @RequestParam(required = false) String keyword) {
        Page<TableMetadata> page = tableMetadataService.getTableList(current, size, null, databaseName, tableName, keyword);
        Page<TableMetadataVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toTableMetadataVO).collect(Collectors.toList()));
        return Result.success(voPage);
    }

    @GetMapping("/table/columns")
    public Result<List<ColumnMetadataVO>> getTableColumns(
            @RequestParam StorageEngineType storageEngine,
            @RequestParam String databaseName,
            @RequestParam String tableName) {

        dataAccessPermissionService.checkTablePermission(
                null,
                databaseName,
                tableName,
                DataAccessPermissionService.TABLE_OP_SELECT
        );

        List<ColumnMetadata> columns = tableMetadataService.getColumnsByNames(storageEngine, databaseName, tableName);
        List<ColumnMetadataVO> voList = columns.stream().map(this::toColumnMetadataVO).collect(Collectors.toList());
        return Result.success(voList);
    }

    @GetMapping("/table/{id}/permission")
    public Result<SysTablePermission> getTablePermission(@PathVariable Long id) {
        TableMetadata tableMetadata = tableMetadataService.getById(id);
        if (tableMetadata == null) {
            return Result.error(404, "表不存在");
        }

        String datasourceName = null;
        if (tableMetadata.getDatasourceId() != null) {
            DatasourceConfig ds = datasourceConfigMapper.selectById(tableMetadata.getDatasourceId());
            if (ds != null) {
                datasourceName = ds.getDatasourceName();
            }
        }

        SysTablePermission permission = dataAccessPermissionService.getMergedTablePermission(
                datasourceName,
                tableMetadata.getDatabaseName(),
                tableMetadata.getTableName()
        );
        return Result.success(permission);
    }

    private TableMetadataVO toTableMetadataVO(TableMetadata entity) {
        TableMetadataVO vo = new TableMetadataVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    private ColumnMetadataVO toColumnMetadataVO(ColumnMetadata entity) {
        ColumnMetadataVO vo = new ColumnMetadataVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
