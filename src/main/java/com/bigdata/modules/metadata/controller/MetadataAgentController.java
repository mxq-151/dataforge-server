package com.bigdata.modules.metadata.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.Indicator;
import com.bigdata.modules.metadata.entity.IndicatorField;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.service.IndicatorService;
import com.bigdata.modules.metadata.service.TableMetadataService;
import com.bigdata.modules.metadata.vo.AgentTableDetailVO;
import com.bigdata.modules.metadata.vo.ColumnMetadataVO;
import com.bigdata.modules.metadata.vo.IndicatorFieldVO;
import com.bigdata.modules.metadata.vo.IndicatorVO;
import com.bigdata.modules.metadata.vo.TableMetadataVO;
import com.bigdata.modules.system.entity.SysTablePermission;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/metadata/agent")
public class MetadataAgentController {

    @Autowired
    private TableMetadataService tableMetadataService;

    @Autowired
    private IndicatorService indicatorService;

    @Autowired
    private DataAccessPermissionService dataAccessPermissionService;

    @GetMapping("/tables")
    public Result<List<TableMetadataVO>> getTables(
            @RequestParam(required = true) String databaseName,
            @RequestParam(required = false) String keyword) {

        LambdaQueryWrapper<TableMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TableMetadata::getDeleted, 0)
               .eq(TableMetadata::getDatabaseName, databaseName);

        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(TableMetadata::getTableName, keyword)
                    .or().like(TableMetadata::getTableComment, keyword));
        }

        wrapper.orderByAsc(TableMetadata::getTableName);

        List<TableMetadata> tables = tableMetadataService.list(wrapper);
        List<TableMetadataVO> result = tables.stream().map(t -> {
            TableMetadataVO vo = new TableMetadataVO();
            BeanUtils.copyProperties(t, vo);
            return vo;
        }).collect(Collectors.toList());

        return Result.success(result);
    }

    @GetMapping("/table-detail")
    public Result<AgentTableDetailVO> getTableDetail(
            @RequestParam(required = true) String databaseName,
            @RequestParam(required = true) String tableName) {

        LambdaQueryWrapper<TableMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TableMetadata::getDeleted, 0)
               .eq(TableMetadata::getDatabaseName, databaseName)
               .eq(TableMetadata::getTableName, tableName);
        TableMetadata table = tableMetadataService.getOne(wrapper, false);

        if (table == null) {
            return Result.error(404, "表不存在: " + databaseName + "." + tableName);
        }

        AgentTableDetailVO vo = buildAgentTableDetailVO(table);

        return Result.success(vo);
    }

    @GetMapping("/search")
    public Result<List<AgentTableDetailVO>> searchTables(
            @RequestParam String keyword,
            @RequestParam(required = false) String databaseName,
            @RequestParam(defaultValue = "2000") int limit) {

        LambdaQueryWrapper<TableMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TableMetadata::getDeleted, 0)
               .and(w -> w.like(TableMetadata::getTableName, keyword)
                       .or().like(TableMetadata::getTableComment, keyword));

        if (databaseName != null && !databaseName.isEmpty()) {
            wrapper.eq(TableMetadata::getDatabaseName, databaseName);
        }

        wrapper.orderByDesc(TableMetadata::getSyncTime)
               .last("LIMIT " + limit);

        List<TableMetadata> tables = tableMetadataService.list(wrapper);

        List<AgentTableDetailVO> result = tables.stream()
                .map(this::buildAgentTableDetailVO)
                .collect(Collectors.toList());

        return Result.success(result);
    }

    @GetMapping("/indicators")
    public Result<List<IndicatorVO>> getIndicators(
            @RequestParam(required = false) String keyword) {

        Set<String> accessibleProjectCodes = dataAccessPermissionService.getAccessibleProjectCodes();

        // 非管理员且无可访问项目，返回空列表
        if (accessibleProjectCodes != null && accessibleProjectCodes.isEmpty()) {
            return Result.success(List.of());
        }

        // 管理员时accessibleProjectCodes为null，不传projectCodes过滤；非管理员按可访问项目编码过滤
        List<Indicator> indicators = indicatorService.getIndicatorsByFilter(accessibleProjectCodes, keyword);

        List<IndicatorVO> result = indicators.stream()
                .map(ind -> {
                    IndicatorVO vo = new IndicatorVO();
                    BeanUtils.copyProperties(ind, vo);
                    List<IndicatorField> fields = indicatorService.getFieldsByIndicatorId(ind.getId());
                    vo.setFields(fields.stream().map(f -> {
                        IndicatorFieldVO fvo = new IndicatorFieldVO();
                        BeanUtils.copyProperties(f, fvo);
                        return fvo;
                    }).collect(Collectors.toList()));
                    return vo;
                })
                .collect(Collectors.toList());

        return Result.success(result);
    }

    private AgentTableDetailVO buildAgentTableDetailVO(TableMetadata table) {
        AgentTableDetailVO vo = new AgentTableDetailVO();
        vo.setId(table.getId());
        vo.setDatabaseName(table.getDatabaseName());
        vo.setTableName(table.getTableName());
        vo.setTableComment(table.getTableComment());
        vo.setTableType(table.getTableType());
        vo.setStorageFormat(table.getStorageFormat());
        vo.setRowCount(table.getRowCount());

        // 标注表级读写权限
        boolean canRead = dataAccessPermissionService.hasTablePermission(
                null, table.getDatabaseName(), table.getTableName(),
                DataAccessPermissionService.TABLE_OP_SELECT);
        boolean canWrite = dataAccessPermissionService.hasTablePermission(
                null, table.getDatabaseName(), table.getTableName(),
                DataAccessPermissionService.TABLE_OP_INSERT)
                || dataAccessPermissionService.hasTablePermission(
                null, table.getDatabaseName(), table.getTableName(),
                DataAccessPermissionService.TABLE_OP_UPDATE);
        vo.setCanRead(canRead);
        vo.setCanWrite(canWrite);

        // 查询字段并应用脱敏标记
        List<ColumnMetadata> columns = tableMetadataService.getColumnsByTableId(table.getId());
        List<ColumnMetadataVO> columnVOs = columns.stream().map(col -> {
            ColumnMetadataVO colVo = new ColumnMetadataVO();
            BeanUtils.copyProperties(col, colVo);
            return colVo;
        }).collect(Collectors.toList());

        applyMaskingRules(columnVOs, table.getDatabaseName(), table.getTableName(), canRead, canWrite);
        vo.setColumns(columnVOs);

        return vo;
    }

    private void applyMaskingRules(List<ColumnMetadataVO> columnVOs, String databaseName,
                                   String tableName, boolean tableCanRead, boolean tableCanWrite) {
        if (dataAccessPermissionService.isAdmin()) {
            columnVOs.forEach(col -> {
                col.setCanRead(true);
                col.setCanWrite(true);
            });
            return;
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
            columnVOs.removeIf(vo -> vo.getColumnName() == null
                    || !allowedLower.contains(vo.getColumnName().toLowerCase()));
            if (columnVOs.isEmpty()) {
                return;
            }
        }

        SysTablePermission mergedPerm = dataAccessPermissionService.getMergedTablePermission(
                null, databaseName, tableName);

        for (ColumnMetadataVO vo : columnVOs) {
            // 默认继承表级权限
            vo.setCanRead(tableCanRead);
            vo.setCanWrite(tableCanWrite);
        }

        if (mergedPerm == null || mergedPerm.getDataMaskingRule() == null
                || mergedPerm.getDataMaskingRule().isBlank()) {
            return;
        }

        try {
            com.fasterxml.jackson.databind.ObjectMapper objectMapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> ruleMap = objectMapper.readValue(
                    mergedPerm.getDataMaskingRule(), Map.class);

            for (ColumnMetadataVO vo : columnVOs) {
                if (vo.getColumnName() != null && ruleMap.containsKey(vo.getColumnName())) {
                    Object ruleValue = ruleMap.get(vo.getColumnName());
                    String maskingType;
                    if (ruleValue instanceof String) {
                        maskingType = (String) ruleValue;
                    } else if (ruleValue instanceof Map) {
                        maskingType = (String) ((Map<String, Object>) ruleValue).getOrDefault("type", "partial");
                    } else {
                        continue;
                    }
                    vo.setMaskingType(maskingType);
                    // full脱敏表示完全不可读，partial等表示可读但受限制
                    if ("full".equalsIgnoreCase(maskingType)) {
                        vo.setCanRead(false);
                    }
                    // 有脱敏规则的字段不允许写入
                    vo.setCanWrite(false);
                }
            }
        } catch (Exception e) {
            // 脱敏规则解析失败时返回原始字段列表
        }
    }
}
