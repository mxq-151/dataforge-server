package com.bigdata.modules.metadata.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
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


import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            @RequestParam(defaultValue = "10000") int size,
            @RequestParam(required = true) Long datasourceId,
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

            // 构建每个库的表级权限映射，即使 databaseName 未传入也要做表级过滤
            Map<String, Set<String>> dbTableMap = new HashMap<>();
            if (databaseName != null && datasourceName != null) {
                Set<String> tables = dataAccessPermissionService.getAccessibleTableNames(datasourceName, databaseName);
                dbTableMap.put(databaseName, tables);
            } else if (accessibleDbNames != null && !accessibleDbNames.isEmpty()) {
                for (String dbName : accessibleDbNames) {
                    Set<String> tables = dataAccessPermissionService.getAccessibleTableNames(datasourceName, dbName);
                    dbTableMap.put(dbName, tables);
                }
            }

            final String dsName = datasourceName;
            final Set<String> dbNames = accessibleDbNames;
            final Map<String, Set<String>> tblMap = dbTableMap;

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
                        // 表级权限过滤：null 表示该库下所有表都可访问
                        Set<String> accessibleTables = t.getDatabaseName() != null ? tblMap.get(t.getDatabaseName()) : null;
                        if (accessibleTables != null && !accessibleTables.isEmpty()) {
                            if (t.getTableName() == null || !accessibleTables.contains(t.getTableName())) {
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

    @GetMapping("/table/columns")
    public Result<List<ColumnMetadataVO>> getTableColumns(
            @RequestParam(required = false) DatasourceType storageEngine,
            @RequestParam(required = true)  String databaseName,
            @RequestParam(required = true)  String tableName) {

        dataAccessPermissionService.checkTablePermission(
                null,
                databaseName,
                tableName,
                DataAccessPermissionService.TABLE_OP_SELECT
        );

        List<ColumnMetadata> columns = tableMetadataService.getColumnsByNames(storageEngine, databaseName, tableName);
        List<ColumnMetadataVO> voList = columns.stream().map(this::toColumnMetadataVO).collect(Collectors.toList());

        if (!dataAccessPermissionService.isAdmin()) {
            SysTablePermission mergedPerm = dataAccessPermissionService.getMergedTablePermission(null, databaseName, tableName);
            if (mergedPerm != null && mergedPerm.getDataMaskingRule() != null && !mergedPerm.getDataMaskingRule().isBlank()) {
                try {
                    com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> ruleMap = objectMapper.readValue(mergedPerm.getDataMaskingRule(), java.util.Map.class);
                    for (ColumnMetadataVO vo : voList) {
                        if (vo.getColumnName() != null && ruleMap.containsKey(vo.getColumnName())) {
                            Object ruleValue = ruleMap.get(vo.getColumnName());
                            if (ruleValue instanceof String) {
                                vo.setMaskingType((String) ruleValue);
                            } else if (ruleValue instanceof java.util.Map) {
                                vo.setMaskingType((String) ((java.util.Map<String, Object>) ruleValue).getOrDefault("type", "partial"));
                            }
                        }
                    }
                } catch (Exception e) {
                    // 脱敏规则解析失败时返回原始字段列表
                }
            }

            // 列级权限（指定列）：隐藏未授权列
            java.util.Set<String> allowedColumns = dataAccessPermissionService.getAllowedColumns(null, databaseName, tableName);
            if (allowedColumns != null) {
                java.util.Set<String> allowedLower = new java.util.HashSet<>();
                for (String col : allowedColumns) {
                    if (col != null) {
                        allowedLower.add(col.toLowerCase());
                    }
                }
                voList = voList.stream()
                        .filter(vo -> vo.getColumnName() != null && allowedLower.contains(vo.getColumnName().toLowerCase()))
                        .collect(Collectors.toList());
            }
        }

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
        if (permission != null) {
            permission.setRowFilterSql(dataAccessPermissionService.getMergedRowFilterSql(
                    datasourceName, tableMetadata.getDatabaseName(), tableMetadata.getTableName()));
        }
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
