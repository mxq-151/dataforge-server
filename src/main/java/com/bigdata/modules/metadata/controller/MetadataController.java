package com.bigdata.modules.metadata.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.devops.service.DatasourceConfigService;
import com.bigdata.modules.metadata.dto.DdlExecuteDTO;
import com.bigdata.modules.metadata.dto.SqlQueryDTO;
import com.bigdata.modules.metadata.dto.UpdateCommentDTO;
import com.bigdata.modules.metadata.entity.*;
import com.bigdata.modules.metadata.service.*;
import com.bigdata.modules.metadata.vo.*;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/metadata")
public class MetadataController {

    @Autowired
    private TableMetadataService tableMetadataService;

    @Autowired
    private MetadataSyncService metadataSyncService;

    @Autowired
    private DdlParseService ddlParseService;

    @Autowired
    private DdlExecutionLogService ddlExecutionLogService;

    @Autowired
    private MetadataSyncLogService metadataSyncLogService;

    @Autowired
    private DatasourceConfigService datasourceConfigService;

    @GetMapping("/tables")
    public Result<Page<TableMetadataVO>> getTableList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(required = false) String databaseName,
            @RequestParam(required = false) String tableName,
            @RequestParam(required = false) String keyword) {
        Page<TableMetadata> page = tableMetadataService.getTableList(current, size, datasourceId, databaseName, tableName, keyword);
        Page<TableMetadataVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toTableMetadataVO).collect(Collectors.toList()));
        return Result.success(voPage);
    }

    @GetMapping("/tables/detail")
    public Result<Page<TableDetailVO>> getTableDetailList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(required = false) String databaseName,
            @RequestParam(required = false) String tableName,
            @RequestParam(required = false) String keyword) {
        Page<TableDetailVO> result = tableMetadataService.getTableDetailList(current, size, datasourceId, databaseName, tableName, keyword);
        return Result.success(result);
    }

    @GetMapping("/table/{id}")
    public Result<TableMetadataVO> getTableById(@PathVariable Long id) {
        TableMetadata table = tableMetadataService.getTableById(id);
        return Result.success(toTableMetadataVO(table));
    }

    @GetMapping("/table/{id}/detail")
    public Result<TableDetailVO> getTableDetailById(@PathVariable Long id) {
        TableDetailVO detail = tableMetadataService.getTableDetailById(id);
        return Result.success(detail);
    }

    @GetMapping("/table/{id}/columns")
    public Result<List<ColumnMetadataVO>> getTableColumns(@PathVariable Long id) {
        List<ColumnMetadata> columns = tableMetadataService.getColumnsByTableId(id);
        List<ColumnMetadataVO> voList = columns.stream().map(this::toColumnMetadataVO).collect(Collectors.toList());
        return Result.success(voList);
    }

    @GetMapping("/databases")
    public Result<List<String>> getDatabases(@RequestParam Long datasourceId) {
        List<String> databases = tableMetadataService.getDatabasesByDatasourceId(datasourceId);
        return Result.success(databases);
    }

    @PutMapping("/table/{id}/comment")
    public Result<Void> updateTableComment(@PathVariable Long id, @Valid @RequestBody UpdateCommentDTO dto) {
        tableMetadataService.updateTableComment(id, dto.getComment());
        return Result.success();
    }

    @PutMapping("/column/{id}/comment")
    public Result<Void> updateColumnComment(@PathVariable Long id, @Valid @RequestBody UpdateCommentDTO dto) {
        tableMetadataService.updateColumnComment(id, dto.getComment());
        return Result.success();
    }

    @PostMapping("/sync/{datasourceId}")
    public Result<MetadataSyncResultVO> syncMetadata(@PathVariable Long datasourceId) {
        MetadataSyncResultVO result = metadataSyncService.syncDatasourceMetadata(datasourceId);
        return Result.success(result);
    }

    @GetMapping("/sync-log/{datasourceId}")
    public Result<MetadataSyncLogVO> getLatestSyncLog(@PathVariable Long datasourceId) {
        MetadataSyncLog log = metadataSyncLogService.getLatestSyncLog(datasourceId);
        return Result.success(toMetadataSyncLogVO(log));
    }

    @PostMapping("/ddl/execute")
    public Result<DdlExecuteResultVO> executeDdl(@Valid @RequestBody DdlExecuteDTO dto) {
        DdlExecutionLog ddlLog = ddlParseService.parseAndExecuteDdl(
                dto.getDatasourceId(), dto.getDatabaseName(), dto.getSql(), 1L, "当前用户");

        DdlExecuteResultVO vo = new DdlExecuteResultVO();
        vo.setSuccess("success".equals(ddlLog.getStatus()));
        vo.setStatus(ddlLog.getStatus());
        vo.setSqlType(ddlLog.getSqlType());
        vo.setObjectType(ddlLog.getObjectType());
        vo.setObjectName(ddlLog.getObjectName());
        vo.setExecutionTimeMs(ddlLog.getExecutionTimeMs() != null ? ddlLog.getExecutionTimeMs() : 0);
        vo.setErrorMessage(ddlLog.getErrorMessage() != null ? ddlLog.getErrorMessage() : "");
        vo.setMetadataUpdated(ddlLog.getMetadataUpdated());

        return Result.success(vo);
    }

    @PostMapping("/sql/query")
    public Result<SqlQueryResultVO> executeSqlQuery(@Valid @RequestBody SqlQueryDTO dto) {
        SqlQueryResultVO result = datasourceConfigService.executeSqlQuery(
                dto.getDatasourceId(), dto.getDatabaseName(), dto.getSql(), dto.getLimit());
        return Result.success(result);
    }

    @GetMapping("/ddl/logs")
    public Result<Page<DdlExecutionLogVO>> getDdlLogs(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(required = false) String sqlType,
            @RequestParam(required = false) String status) {
        Page<DdlExecutionLog> page = ddlExecutionLogService.getLogList(current, size, datasourceId, sqlType, status);
        Page<DdlExecutionLogVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toDdlExecutionLogVO).collect(Collectors.toList()));
        return Result.success(voPage);
    }

    @DeleteMapping("/table/{id}")
    public Result<Void> deleteTable(@PathVariable Long id) {
        tableMetadataService.deleteTable(id);
        return Result.success();
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

    private DdlExecutionLogVO toDdlExecutionLogVO(DdlExecutionLog entity) {
        DdlExecutionLogVO vo = new DdlExecutionLogVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    private MetadataSyncLogVO toMetadataSyncLogVO(MetadataSyncLog entity) {
        MetadataSyncLogVO vo = new MetadataSyncLogVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
