package com.bigdata.modules.metadata.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.devops.service.DatasourceConfigService;
import com.bigdata.modules.metadata.entity.*;
import com.bigdata.modules.metadata.service.*;
import com.bigdata.modules.metadata.vo.TableDetailVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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

    @GetMapping("/tables")
    public Result<Page<TableMetadata>> getTableList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(required = false) String databaseName,
            @RequestParam(required = false) String tableName,
            @RequestParam(required = false) String keyword) {
        Page<TableMetadata> result = tableMetadataService.getTableList(current, size, datasourceId, databaseName, tableName, keyword);
        return Result.success(result);
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
    public Result<TableMetadata> getTableById(@PathVariable Long id) {
        TableMetadata table = tableMetadataService.getTableById(id);
        return Result.success(table);
    }

    @GetMapping("/table/{id}/detail")
    public Result<TableDetailVO> getTableDetailById(@PathVariable Long id) {
        TableDetailVO detail = tableMetadataService.getTableDetailById(id);
        return Result.success(detail);
    }

    @GetMapping("/table/{id}/columns")
    public Result<List<ColumnMetadata>> getTableColumns(@PathVariable Long id) {
        List<ColumnMetadata> columns = tableMetadataService.getColumnsByTableId(id);
        return Result.success(columns);
    }

    @GetMapping("/databases")
    public Result<List<String>> getDatabases(@RequestParam Long datasourceId) {
        List<String> databases = tableMetadataService.getDatabasesByDatasourceId(datasourceId);
        return Result.success(databases);
    }

    @PutMapping("/table/{id}/comment")
    public Result<Void> updateTableComment(@PathVariable Long id, @RequestBody Map<String, String> body) {
        tableMetadataService.updateTableComment(id, body.get("comment"));
        return Result.success();
    }

    @PutMapping("/column/{id}/comment")
    public Result<Void> updateColumnComment(@PathVariable Long id, @RequestBody Map<String, String> body) {
        tableMetadataService.updateColumnComment(id, body.get("comment"));
        return Result.success();
    }

    @PostMapping("/sync/{datasourceId}")
    public Result<Map<String, Object>> syncMetadata(@PathVariable Long datasourceId) {
        Map<String, Object> result = metadataSyncService.syncDatasourceMetadata(datasourceId);
        return Result.success(result);
    }

    @GetMapping("/sync-log/{datasourceId}")
    public Result<MetadataSyncLog> getLatestSyncLog(@PathVariable Long datasourceId) {
        MetadataSyncLog log = metadataSyncLogService.getLatestSyncLog(datasourceId);
        return Result.success(log);
    }

    @PostMapping("/ddl/execute")
    public Result<Map<String, Object>> executeDdl(@RequestBody Map<String, Object> params) {
        Long datasourceId = Long.valueOf(params.get("datasourceId").toString());
        String databaseName = (String) params.getOrDefault("databaseName", "default");
        String sql = (String) params.get("sql");

        DdlExecutionLog ddlLog = ddlParseService.parseAndExecuteDdl(
                datasourceId, databaseName, sql, 1L, "当前用户");

        Map<String, Object> result = Map.of(
                "success", "success".equals(ddlLog.getStatus()),
                "status", ddlLog.getStatus(),
                "sqlType", ddlLog.getSqlType(),
                "objectType", ddlLog.getObjectType(),
                "objectName", ddlLog.getObjectName(),
                "executionTimeMs", ddlLog.getExecutionTimeMs() != null ? ddlLog.getExecutionTimeMs() : 0,
                "errorMessage", ddlLog.getErrorMessage() != null ? ddlLog.getErrorMessage() : "",
                "metadataUpdated", ddlLog.getMetadataUpdated()
        );

        return Result.success(result);
    }

    @Autowired
    private DatasourceConfigService datasourceConfigService;

    @PostMapping("/sql/query")
    public Result<Map<String, Object>> executeSqlQuery(@RequestBody Map<String, Object> params) {
        Long datasourceId = Long.valueOf(params.get("datasourceId").toString());
        String databaseName = (String) params.getOrDefault("databaseName", "default");
        String sql = (String) params.get("sql");
        Integer limit = params.get("limit") != null ? Integer.valueOf(params.get("limit").toString()) : 1000;

        long startTime = System.currentTimeMillis();
        java.sql.Connection connection = null;
        java.sql.Statement stmt = null;
        java.sql.ResultSet rs = null;

        try {
            connection = datasourceConfigService.getConnection(datasourceId, databaseName);
            stmt = connection.createStatement();

            boolean isQuery = stmt.execute(sql);
            List<String> columns = new java.util.ArrayList<>();
            List<List<Object>> rows = new java.util.ArrayList<>();
            int rowCount = 0;

            if (isQuery) {
                rs = stmt.getResultSet();
                java.sql.ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();

                for (int i = 1; i <= columnCount; i++) {
                    columns.add(metaData.getColumnLabel(i));
                }

                while (rs.next() && rowCount < limit) {
                    List<Object> row = new java.util.ArrayList<>();
                    for (int i = 1; i <= columnCount; i++) {
                        Object value = rs.getObject(i);
                        row.add(value);
                    }
                    rows.add(row);
                    rowCount++;
                }
            } else {
                int updateCount = stmt.getUpdateCount();
                columns.add("affected_rows");
                rows.add(List.of(updateCount));
                rowCount = updateCount;
            }

            long executionTimeMs = System.currentTimeMillis() - startTime;

            return Result.success(Map.of(
                    "success", true,
                    "sqlType", detectSqlType(sql),
                    "columns", columns,
                    "rows", rows,
                    "rowCount", rowCount,
                    "executionTimeMs", executionTimeMs
            ));
        } catch (Exception e) {
            long executionTimeMs = System.currentTimeMillis() - startTime;
            return Result.success(Map.of(
                    "success", false,
                    "errorMessage", e.getMessage(),
                    "executionTimeMs", executionTimeMs,
                    "columns", java.util.List.of(),
                    "rows", java.util.List.of(),
                    "rowCount", 0
            ));
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignored) {}
            try { if (stmt != null) stmt.close(); } catch (Exception ignored) {}
            try { if (connection != null) connection.close(); } catch (Exception ignored) {}
        }
    }

    private String detectSqlType(String sql) {
        String upper = sql.toUpperCase().trim();
        if (upper.startsWith("SELECT")) return "SELECT";
        if (upper.startsWith("SHOW")) return "SHOW";
        if (upper.startsWith("DESCRIBE") || upper.startsWith("DESC")) return "DESCRIBE";
        if (upper.startsWith("EXPLAIN")) return "EXPLAIN";
        if (upper.startsWith("CREATE")) return "CREATE";
        if (upper.startsWith("ALTER")) return "ALTER";
        if (upper.startsWith("DROP")) return "DROP";
        if (upper.startsWith("INSERT")) return "INSERT";
        if (upper.startsWith("UPDATE")) return "UPDATE";
        if (upper.startsWith("DELETE")) return "DELETE";
        return "UNKNOWN";
    }

    @GetMapping("/ddl/logs")
    public Result<Page<DdlExecutionLog>> getDdlLogs(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(required = false) String sqlType,
            @RequestParam(required = false) String status) {
        Page<DdlExecutionLog> result = ddlExecutionLogService.getLogList(current, size, datasourceId, sqlType, status);
        return Result.success(result);
    }

    @DeleteMapping("/table/{id}")
    public Result<Void> deleteTable(@PathVariable Long id) {
        tableMetadataService.deleteTable(id);
        return Result.success();
    }
}
