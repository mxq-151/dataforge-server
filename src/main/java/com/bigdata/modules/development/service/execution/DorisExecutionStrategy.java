package com.bigdata.modules.development.service.execution;

import com.bigdata.modules.development.config.EngineProperties;
import com.bigdata.modules.devops.enums.DatasourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.Statement;

@Component
public class DorisExecutionStrategy extends AbstractJdbcExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(DorisExecutionStrategy.class);

    @Autowired
    private EngineProperties engineProperties;

    @Override
    protected Logger getLog() {
        return log;
    }

    @Override
    protected String getEngineName() {
        return "Doris";
    }

    @Override
    protected String getEngineCode() {
        return "doris";
    }

    @Override
    protected void applySessionSettings(Statement stmt, CodeExecutionContext context) throws Exception {
        EngineProperties.Doris dorisConfig = engineProperties.getDoris();
        executeSetting(stmt, "SET enable_vectorized_engine=true");
        executeSetting(stmt, "SET batch_size=" + dorisConfig.getBatchSize());
        executeSetting(stmt, "SET parallel_fragment_exec_instance_num=" + dorisConfig.getParallelFragmentExecInstanceNum());
        executeSetting(stmt, "SET query_timeout=" + dorisConfig.getQueryTimeout());
        executeSetting(stmt, "SET insert_timeout=" + dorisConfig.getInsertTimeout());
    }

    @Override
    protected DatasourceType resolveStorageEngineType(CodeExecutionContext context) {
        return DatasourceType.DORIS;
    }

    @Override
    public DatasourceType getEngineType() {
        return DatasourceType.DORIS;
    }
}
