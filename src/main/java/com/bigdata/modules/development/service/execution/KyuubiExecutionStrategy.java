package com.bigdata.modules.development.service.execution;

import com.bigdata.modules.devops.enums.StorageEngineType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Statement;

@Component
public class KyuubiExecutionStrategy extends AbstractJdbcExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(KyuubiExecutionStrategy.class);

    @Override
    protected Logger getLog() {
        return log;
    }

    @Override
    protected String getEngineName() {
        return "Kyuubi";
    }

    @Override
    protected String getEngineCode() {
        return "kyuubi";
    }

    @Override
    protected void applySessionSettings(Statement stmt, CodeExecutionContext context) throws Exception {
        applyStorageEngineSettings(stmt, context);
    }

    @Override
    protected StorageEngineType resolveStorageEngineType(CodeExecutionContext context) {
        return context.getPrimaryStorageEngine();
    }

    @Override
    public EngineType getEngineType() {
        return EngineType.KYUUBI;
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
}
