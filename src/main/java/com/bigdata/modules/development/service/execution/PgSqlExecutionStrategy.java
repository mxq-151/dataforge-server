package com.bigdata.modules.development.service.execution;

import com.bigdata.modules.devops.enums.DatasourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Statement;

/**
 * PostgreSQL 执行策略。
 * <p>
 * 复用 {@link AbstractJdbcExecutionStrategy} 的统一 JDBC 执行流程，
 * 不应用 Doris 特有的会话设置；元数据(库/表/字段)走标准 JDBC DatabaseMetaData。
 * </p>
 */
@Component
public class PgSqlExecutionStrategy extends AbstractJdbcExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(PgSqlExecutionStrategy.class);

    @Override
    protected Logger getLog() {
        return log;
    }

    @Override
    protected String getEngineName() {
        return "PostgreSQL";
    }

    @Override
    protected String getEngineCode() {
        return "postgresql";
    }

    @Override
    protected void applySessionSettings(Statement stmt, CodeExecutionContext context) throws Exception {
        // PostgreSQL 无需 Doris/StarRocks 特有的会话变量设置，保持默认即可
    }

    @Override
    protected DatasourceType resolveStorageEngineType(CodeExecutionContext context) {
        return DatasourceType.POSTGRESQL;
    }

    @Override
    public DatasourceType getEngineType() {
        return DatasourceType.POSTGRESQL;
    }
}