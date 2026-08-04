package com.bigdata.modules.development.service.execution;

import com.bigdata.modules.devops.enums.StorageEngineType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Statement;

/**
 * MySQL 执行策略。
 * <p>
 * 复用 {@link AbstractJdbcExecutionStrategy} 的统一 JDBC 执行流程，
 * 但不应用 Doris 特有的会话设置（如 SET enable_vectorized_engine=true 等 MySQL 不支持的语法）。
 * </p>
 */
@Component
public class MySqlExecutionStrategy extends AbstractJdbcExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(MySqlExecutionStrategy.class);

    @Override
    protected Logger getLog() {
        return log;
    }

    @Override
    protected String getEngineName() {
        return "MySQL";
    }

    @Override
    protected String getEngineCode() {
        return "mysql";
    }

    @Override
    protected void applySessionSettings(Statement stmt, CodeExecutionContext context) throws Exception {
        // MySQL 无需 Doris/StarRocks 特有的会话变量设置，保持默认即可
    }

    @Override
    protected StorageEngineType resolveStorageEngineType(CodeExecutionContext context) {
        return StorageEngineType.MYSQL;
    }

    @Override
    public EngineType getEngineType() {
        return EngineType.MYSQL;
    }
}
