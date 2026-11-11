package com.bigdata.modules.metadata.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.metadata.entity.ColumnMetadata;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.service.TableMetadataService;
import com.bigdata.modules.metadata.vo.AgentTableDetailVO;
import com.bigdata.modules.metadata.vo.ColumnMetadataVO;
import com.bigdata.modules.system.entity.SysTablePermission;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 智能体元数据详情接口。
 *
 * <p>检索入口已迁到 {@code /agent/retrieval/tables}（见 AgentRetrievalController），
 * 详情（字段清单、读写权限、脱敏标记）仍由本接口提供：检索只负责「找到」，
 * 本接口负责「读全」，两段式是智能体上下文可控的前提。</p>
 */
@RestController
@RequestMapping("/metadata/agent")
public class MetadataAgentController {

    @Autowired
    private TableMetadataService tableMetadataService;

    @Autowired
    private DataAccessPermissionService dataAccessPermissionService;

    @GetMapping("/table-detail")
    public Result<AgentTableDetailVO> getTableDetail(
            @RequestParam(required = true) String databaseName,
            @RequestParam(required = true) String tableName) {

        LambdaQueryWrapper<TableMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TableMetadata::getDatabaseName, databaseName)
               .eq(TableMetadata::getTableName, tableName);
        TableMetadata table = tableMetadataService.getOne(wrapper, false);

        if (table == null) {
            return Result.error(404, "表不存在: " + databaseName + "." + tableName);
        }

        return Result.success(buildAgentTableDetailVO(table));
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
        Set<String> allowedColumns = dataAccessPermissionService.getAllowedColumns(null, databaseName, tableName);
        if (allowedColumns != null) {
            Set<String> allowedLower = allowedColumns.stream()
                    .filter(col -> col != null)
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet());
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
