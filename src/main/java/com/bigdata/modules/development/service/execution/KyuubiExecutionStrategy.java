package com.bigdata.modules.development.service.execution;

import com.bigdata.modules.devops.enums.StorageEngineType;
import com.bigdata.modules.devops.service.DatasourceConfigService;
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

@Component
public class KyuubiExecutionStrategy implements ExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(KyuubiExecutionStrategy.class);

    private static final int MAX_RESULT_ROWS = 1000;

    @Autowired
    private DatasourceConfigService datasourceConfigService;

    @Override
    public CodeExecutionResult execute(CodeExecutionContext context) {
        String executionId = UUID.randomUUID().toString().replace("-", "");
        long startTime = System.currentTimeMillis();

        if (context.getPrimaryDatasourceConfig() == null) {
            return CodeExecutionResult.failure(executionId, "Kyuubi执行引擎需要指定数据源");
        }

        Connection connection = null;
        Statement stmt = null;
        try {
            connection = datasourceConfigService.getConnection(context.getPrimaryDatasourceConfig().getId(), context.getPrimaryDatasourceDatabaseName());
            stmt = connection.createStatement();
            applyStorageEngineSettings(stmt, context);

            String sql = context.getCodeContent();
            log.info("Kyuubi执行SQL [存储引擎: {}]: {}", context.getPrimaryStorageEngine(),
                    sql.length() > 200 ? sql.substring(0, 200) + "..." : sql);

            boolean hasResultSet = stmt.execute(sql);
            long duration = System.currentTimeMillis() - startTime;

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

    @Override
    public EngineType getEngineType() {
        return EngineType.KYUUBI;
    }

    @Override
    public boolean supports(CodeType codeType) {
        return getEngineType().supportsCodeType(codeType);
    }
}
