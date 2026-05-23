package com.bigdata.modules.development.service.execution;

import com.bigdata.modules.devops.enums.StorageEngineType;
import com.bigdata.modules.devops.service.DatasourceConfigService;
import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.modules.metadata.service.MetadataSyncService;
import com.bigdata.modules.system.entity.SysRole;
import com.bigdata.modules.system.mapper.SysRoleMapper;
import com.bigdata.modules.system.mapper.SysUserRoleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;

@Component
public class KyuubiExecutionStrategy implements ExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(KyuubiExecutionStrategy.class);

    private static final int MAX_RESULT_ROWS = 1000;

    @Autowired
    private DatasourceConfigService datasourceConfigService;

    @Autowired
    private ProcessManager processManager;

    @Autowired
    private MetadataSyncService metadataSyncService;

    @Autowired
    private DataAccessPermissionService dataAccessPermissionService;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private SysRoleMapper sysRoleMapper;

    @Override
    public CodeExecutionResult execute(CodeExecutionContext context) {
        String executionId = UUID.randomUUID().toString().replace("-", "");
        long startTime = System.currentTimeMillis();

        if (context.getPrimaryDatasourceConfig() == null) {
            return CodeExecutionResult.failure(executionId, "Kyuubi执行引擎需要指定数据源");
        }

        Connection connection = null;
        Statement stmt = null;
        String registeredId = null;
        try {
            connection = datasourceConfigService.getConnection(context.getPrimaryDatasourceConfig().getId(), context.getPrimaryDatasourceDatabaseName());
            stmt = connection.createStatement();
            registeredId = processManager.registerStatement(stmt, context.getSubmitBy(), "kyuubi");
            applyStorageEngineSettings(stmt, context);

            String sql = context.getCodeContent();
            log.info("Kyuubi执行SQL [存储引擎: {}]: {}", context.getPrimaryStorageEngine(),
                    sql.length() > 200 ? sql.substring(0, 200) + "..." : sql);

            DdlInfo ddlInfo = detectDdlStatement(sql);
            if (ddlInfo != null) {
                log.info("检测到DDL语句: {}, 类型: {}", sql.substring(0, Math.min(100, sql.length())), ddlInfo.ddlType);
                
                if (!hasDdlPermission(context.getSubmitBy())) {
                    log.warn("用户[{}]无DDL执行权限, 拒绝执行: {}", context.getSubmitBy(), context.getSubmitByName());
                    return CodeExecutionResult.failure(executionId, 
                            "权限不足：只有超级管理员和运维人员才能执行DDL语句（CREATE/ALTER/DROP/TRUNCATE）");
                }
            } else {
                checkDmlTablePermission(context, sql, executionId);
            }

            boolean hasResultSet = stmt.execute(sql);
            long duration = System.currentTimeMillis() - startTime;

            if (ddlInfo != null) {
                log.info("DDL语句执行成功, 开始同步元数据: {}.{}", ddlInfo.databaseName, ddlInfo.tableName);
                try {
                    boolean syncSuccess = metadataSyncService.syncSingleTableMetadata(
                            context.getPrimaryDatasourceConfig().getId(),
                            context.getPrimaryStorageEngine(),
                            ddlInfo.databaseName,
                            ddlInfo.tableName,
                            ddlInfo.ddlType
                    );
                    if (syncSuccess) {
                        log.info("元数据同步成功: {}.{}", ddlInfo.databaseName, ddlInfo.tableName);
                    } else {
                        log.warn("元数据同步失败: {}.{}", ddlInfo.databaseName, ddlInfo.tableName);
                    }
                } catch (Exception syncEx) {
                    log.error("元数据同步异常: {}.{}", ddlInfo.databaseName, ddlInfo.tableName, syncEx);
                }
            }

            if (hasResultSet) {
                ResultSet rs = stmt.getResultSet();
                StringBuilder resultData = new StringBuilder();
                List<String> columns = new ArrayList<>();
                int colCount = rs.getMetaData().getColumnCount();
                for (int i = 1; i <= colCount; i++) {
                    columns.add(rs.getMetaData().getColumnLabel(i));
                }
                resultData.append(String.join("\t", columns)).append("\n");

                int rowCount = 0;
                while (rs.next() && rowCount < MAX_RESULT_ROWS) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= colCount; i++) {
                        String val = rs.getString(i);
                        row.add(val != null ? val : "NULL");
                    }
                    resultData.append(String.join("\t", row)).append("\n");
                    rowCount++;
                }

                CodeExecutionResult result = CodeExecutionResult.success(executionId,
                        "查询完成，返回" + rowCount + "行数据", resultData.toString(), rowCount);
                result.setStartTime(new java.util.Date(startTime));
                result.setEndTime(new java.util.Date());
                result.setDurationMs((int) duration);
                return result;
            } else {
                int affectedRows = stmt.getUpdateCount();
                CodeExecutionResult result = CodeExecutionResult.success(executionId,
                        "执行完成，影响" + affectedRows + "行", null, affectedRows);
                result.setStartTime(new java.util.Date(startTime));
                result.setEndTime(new java.util.Date());
                result.setDurationMs((int) duration);
                return result;
            }
        } catch (Exception e) {
            log.error("Kyuubi执行失败", e);
            long duration = System.currentTimeMillis() - startTime;
            CodeExecutionResult result = CodeExecutionResult.failure(executionId, "Kyuubi执行异常: " + e.getMessage());
            result.setStartTime(new java.util.Date(startTime));
            result.setEndTime(new java.util.Date());
            result.setDurationMs((int) duration);
            return result;
        } finally {
            if (registeredId != null) {
                processManager.unregister(registeredId);
            }
            closeQuietly(stmt);
            closeQuietly(connection);
        }
    }

    private void applyStorageEngineSettings(Statement stmt, CodeExecutionContext context) throws Exception {
        StorageEngineType storageEngine = context.getPrimaryStorageEngine();
        if (storageEngine == null) {
            return;
        }
        switch (storageEngine) {
            case HIVE:
                applyHiveSettings(stmt);
                break;
            case DORIS:
            case STARROCKS:
                applyOlapSettings(stmt, storageEngine);
                break;
            case HUDI:
                applyHudiSettings(stmt);
                break;
            case ICEBERG:
                applyIcebergSettings(stmt);
                break;
            case CLICKHOUSE:
                applyClickHouseSettings(stmt);
                break;
            default:
                break;
        }
    }

    private void applyHiveSettings(Statement stmt) throws Exception {
        executeSetting(stmt, "SET hive.exec.parallel=true");
        executeSetting(stmt, "SET hive.exec.parallel.thread.number=8");
        executeSetting(stmt, "SET hive.auto.convert.join=true");
        executeSetting(stmt, "SET hive.map.aggr.hash.percentmemory=0.5");
    }

    private void applyOlapSettings(Statement stmt, StorageEngineType engineType) throws Exception {
        if (engineType == StorageEngineType.DORIS) {
            executeSetting(stmt, "SET enable_vectorized_engine=true");
            executeSetting(stmt, "SET batch_size=4096");
            executeSetting(stmt, "SET parallel_fragment_exec_instance_num=4");
        } else if (engineType == StorageEngineType.STARROCKS) {
            executeSetting(stmt, "SET enable_pipeline=true");
            executeSetting(stmt, "SET pipeline_dop=4");
            executeSetting(stmt, "SET enable_vectorized_scan=true");
        }
    }

    private void applyHudiSettings(Statement stmt) throws Exception {
        executeSetting(stmt, "SET spark.sql.hive.convertMetastoreParquet=false");
        executeSetting(stmt, "SET hoodie.upsert.shuffle.parallelism=200");
        executeSetting(stmt, "SET hoodie.insert.shuffle.parallelism=200");
        executeSetting(stmt, "SET hoodie.delete.shuffle.parallelism=200");
    }

    private void applyIcebergSettings(Statement stmt) throws Exception {
        executeSetting(stmt, "SET iceberg.engine.spark.execute-in-place-merge=true");
        executeSetting(stmt, "SET iceberg.engine.spark.pushdown-limit=true");
    }

    private void applyClickHouseSettings(Statement stmt) throws Exception {
        executeSetting(stmt, "SET max_execution_time=300000");
        executeSetting(stmt, "SET max_rows_to_read=10000000");
    }

    private void executeSetting(Statement stmt, String settingSql) throws Exception {
        try {
            stmt.execute(settingSql);
            log.debug("应用Kyuubi会话设置: {}", settingSql);
        } catch (Exception e) {
            log.warn("应用Kyuubi会话设置失败: {}, 错误: {}", settingSql, e.getMessage());
        }
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static class DdlInfo {
        String ddlType;
        String databaseName;
        String tableName;
    }

    private DdlInfo detectDdlStatement(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return null;
        }

        try {
            net.sf.jsqlparser.statement.Statement stmt = CCJSqlParserUtil.parse(sql);

            if (stmt instanceof CreateTable) {
                CreateTable createTable = (CreateTable) stmt;
                DdlInfo info = new DdlInfo();
                info.ddlType = "CREATE";
                info.tableName = createTable.getTable().getName();
                info.databaseName = createTable.getTable().getSchemaName();
                if (info.databaseName == null || info.databaseName.isEmpty()) {
                    info.databaseName = createTable.getTable().getDatabase().getDatabaseName();
                }
                return info;
            }

            if (stmt instanceof Alter) {
                Alter alter = (Alter) stmt;
                DdlInfo info = new DdlInfo();
                info.ddlType = "ALTER";
                info.tableName = alter.getTable().getName();
                info.databaseName = alter.getTable().getSchemaName();
                if (info.databaseName == null || info.databaseName.isEmpty()) {
                    info.databaseName = alter.getTable().getDatabase().getDatabaseName();
                }
                return info;
            }

            if (stmt instanceof Drop) {
                Drop drop = (Drop) stmt;
                if ("TABLE".equalsIgnoreCase(drop.getType())) {
                    DdlInfo info = new DdlInfo();
                    info.ddlType = "DROP";
                    info.tableName = drop.getName().getName();
                    info.databaseName = drop.getName().getSchemaName();
                    if (info.databaseName == null || info.databaseName.isEmpty()) {
                        info.databaseName = drop.getName().getDatabase().getDatabaseName();
                    }
                    return info;
                }
            }

            if (stmt instanceof Truncate) {
                Truncate truncate = (Truncate) stmt;
                DdlInfo info = new DdlInfo();
                info.ddlType = "TRUNCATE";
                info.tableName = truncate.getTable().getName();
                info.databaseName = truncate.getTable().getSchemaName();
                if (info.databaseName == null || info.databaseName.isEmpty()) {
                    info.databaseName = truncate.getTable().getDatabase().getDatabaseName();
                }
                return info;
            }

            return null;
        } catch (Exception e) {
            log.debug("SQL解析失败, 可能不是DDL语句: {}", e.getMessage());
            return null;
        }
    }

    private boolean hasDdlPermission(Long userId) {
        if (userId == null) {
            return false;
        }

        try {
            List<Long> roleIds = sysUserRoleMapper.selectRoleIdsByUserId(userId);
            if (roleIds == null || roleIds.isEmpty()) {
                return false;
            }

            List<SysRole> roles = sysRoleMapper.selectBatchIds(roleIds);
            if (roles == null || roles.isEmpty()) {
                return false;
            }

            for (SysRole role : roles) {
                String roleCode = role.getRoleCode();
                if ("admin".equalsIgnoreCase(roleCode) || "ops".equalsIgnoreCase(roleCode)) {
                    log.debug("用户[{}]拥有DDL权限, 角色: {}", userId, role.getRoleName());
                    return true;
                }
            }

            log.debug("用户[{}]无DDL权限, 角色: {}", userId, 
                    roles.stream().map(SysRole::getRoleName).reduce((a, b) -> a + "," + b).orElse("无"));
            return false;
        } catch (Exception e) {
            log.error("检查用户DDL权限异常: {}", userId, e);
            return false;
        }
    }

    private void checkDmlTablePermission(CodeExecutionContext context, String sql, String executionId) {
        try {
            net.sf.jsqlparser.statement.Statement jsqlStmt = CCJSqlParserUtil.parse(sql);
            
            String operation = null;
            List<TableRef> tableRefs = new ArrayList<>();

            if (jsqlStmt instanceof Select) {
                operation = DataAccessPermissionService.TABLE_OP_SELECT;
                tableRefs = extractTablesFromSelect((Select) jsqlStmt);
            } else if (jsqlStmt instanceof Insert) {
                operation = DataAccessPermissionService.TABLE_OP_INSERT;
                Insert insert = (Insert) jsqlStmt;
                TableRef ref = new TableRef();
                ref.tableName = insert.getTable().getName();
                ref.databaseName = insert.getTable().getSchemaName();
                if (ref.databaseName == null || ref.databaseName.isEmpty()) {
                    ref.databaseName = insert.getTable().getDatabase() != null ? 
                            insert.getTable().getDatabase().getDatabaseName() : null;
                }
                tableRefs.add(ref);
            } else if (jsqlStmt instanceof Update) {
                operation = DataAccessPermissionService.TABLE_OP_UPDATE;
                Update update = (Update) jsqlStmt;
                TableRef ref = new TableRef();
                ref.tableName = update.getTable().getName();
                ref.databaseName = update.getTable().getSchemaName();
                if (ref.databaseName == null || ref.databaseName.isEmpty()) {
                    ref.databaseName = update.getTable().getDatabase() != null ? 
                            update.getTable().getDatabase().getDatabaseName() : null;
                }
                tableRefs.add(ref);
            } else if (jsqlStmt instanceof Delete) {
                operation = DataAccessPermissionService.TABLE_OP_DELETE;
                Delete delete = (Delete) jsqlStmt;
                TableRef ref = new TableRef();
                ref.tableName = delete.getTable().getName();
                ref.databaseName = delete.getTable().getSchemaName();
                if (ref.databaseName == null || ref.databaseName.isEmpty()) {
                    ref.databaseName = delete.getTable().getDatabase() != null ? 
                            delete.getTable().getDatabase().getDatabaseName() : null;
                }
                tableRefs.add(ref);
            }

            if (operation == null || tableRefs.isEmpty()) {
                log.debug("SQL语句不涉及表操作或无法解析: {}", sql.substring(0, Math.min(50, sql.length())));
                return;
            }

            String datasourceName = context.getPrimaryDatasourceConfig().getDatasourceName();
            String defaultDatabase = context.getPrimaryDatasourceDatabaseName();

            for (TableRef tableRef : tableRefs) {
                String dbName = tableRef.databaseName != null ? tableRef.databaseName : defaultDatabase;
                
                boolean hasPermission = dataAccessPermissionService.hasTablePermission(
                        datasourceName,
                        dbName,
                        tableRef.tableName,
                        operation
                );

                if (!hasPermission) {
                    log.warn("用户[{}]无权对表 {}.{} 执行 {} 操作", 
                            context.getSubmitBy(), dbName, tableRef.tableName, operation);
                    throw new SecurityException(String.format(
                            "权限不足：无权对表 %s.%s 执行 %s 操作", 
                            dbName, tableRef.tableName, operation));
                }
                
                log.debug("用户[{}]拥有表 {}.{} 的 {} 权限", 
                        context.getSubmitBy(), dbName, tableRef.tableName, operation);
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            log.debug("DML权限检查解析失败, 放行执行: {}", e.getMessage());
        }
    }

    private List<TableRef> extractTablesFromSelect(Select select) {
        List<TableRef> tableRefs = new ArrayList<>();
        
        try {
            Object selectBody = select.getSelectBody();
            
            if (selectBody.getClass().getName().contains("PlainSelect")) {
                java.lang.reflect.Method getFromItemMethod = selectBody.getClass().getMethod("getFromItem");
                Object fromItem = getFromItemMethod.invoke(selectBody);
                
                if (fromItem != null) {
                    extractTableFromItemDynamic(fromItem, tableRefs);
                }
            }
        } catch (Exception e) {
            log.debug("解析SELECT语句的FROM子句失败: {}", e.getMessage());
        }
        
        return tableRefs;
    }

    private void extractTableFromItemDynamic(Object fromItem, List<TableRef> tableRefs) {
        try {
            String className = fromItem.getClass().getName();
            
            if (className.contains(".Table") && !className.contains("Join")) {
                TableRef ref = new TableRef();
                
                java.lang.reflect.Method getNameMethod = fromItem.getClass().getMethod("getName");
                ref.tableName = (String) getNameMethod.invoke(fromItem);
                
                try {
                    java.lang.reflect.Method getSchemaNameMethod = fromItem.getClass().getMethod("getSchemaName");
                    ref.databaseName = (String) getSchemaNameMethod.invoke(fromItem);
                    
                    if (ref.databaseName == null || ref.databaseName.isEmpty()) {
                        try {
                            java.lang.reflect.Method getTableMethod = fromItem.getClass().getMethod("getTable");
                            Object databaseObj = getTableMethod.invoke(fromItem);
                            if (databaseObj != null) {
                                java.lang.reflect.Method getDatabaseNameMethod = databaseObj.getClass().getMethod("getDatabaseName");
                                ref.databaseName = (String) getDatabaseNameMethod.invoke(databaseObj);
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (NoSuchMethodException ignored) {}
                
                tableRefs.add(ref);
            } else if (className.contains("SubJoin")) {
                java.lang.reflect.Method getLeftMethod = fromItem.getClass().getMethod("getLeft");
                Object left = getLeftMethod.invoke(fromItem);
                extractTableFromItemDynamic(left, tableRefs);
                
                java.lang.reflect.Method getJoinsMethod = fromItem.getClass().getMethod("getJoins");
                @SuppressWarnings("unchecked")
                List<Object> joins = (List<Object>) getJoinsMethod.invoke(fromItem);
                for (Object join : joins) {
                    java.lang.reflect.Method getRightItemMethod = join.getClass().getMethod("getRightItem");
                    Object rightItem = getRightItemMethod.invoke(join);
                    extractTableFromItemDynamic(rightItem, tableRefs);
                }
            } else if (className.contains("SubSelect")) {
                java.lang.reflect.Method getSelectBodyMethod = fromItem.getClass().getMethod("getSelectBody");
                Object subSelectBody = getSelectBodyMethod.invoke(fromItem);
                
                if (subSelectBody != null && subSelectBody.getClass().getName().contains("PlainSelect")) {
                    java.lang.reflect.Method getFromItemMethod = subSelectBody.getClass().getMethod("getFromItem");
                    Object subFromItem = getFromItemMethod.invoke(subSelectBody);
                    if (subFromItem != null) {
                        extractTableFromItemDynamic(subFromItem, tableRefs);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("动态提取表信息失败: {}", e.getMessage());
        }
    }

    private static class TableRef {
        String tableName;
        String databaseName;
    }

    @Override
    public EngineType getEngineType() {
        return EngineType.KYUUBI;
    }

    @Override
    public boolean supports(CodeType codeType) {
        return getEngineType().supportsCodeType(codeType);
    }
}
