package com.bigdata.modules.development.service.execution;

import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.vo.ColumnMetadataVO;
import java.util.ArrayList;
import java.util.List;

public interface ExecutionStrategy {

    CodeExecutionResult execute(CodeExecutionContext context);

    /**
     * 在异步执行之前检查权限（在请求线程中执行，SecurityContext可用）
     * 默认不做任何检查，JDBC类策略可覆写此方法
     */
    default void checkPermission(CodeExecutionContext context) {
        // 默认无权限检查
    }

    DatasourceType getEngineType();

    boolean supports(CodeType codeType);

    /**
     * 测试数据源连接，默认返回false
     */
    default boolean testConnection(DatasourceConfig datasourceConfig) {
        return false;
    }

    /**
     * 获取数据库列表，默认返回空列表
     */
    default List<String> listDatabases(DatasourceConfig datasourceConfig) {
        return new ArrayList<>();
    }

    /**
     * 获取指定库下的表列表，默认返回空列表
     */
    default List<TableMetadata> listTables(DatasourceConfig datasourceConfig, String databaseName) {
        return new ArrayList<>();
    }

    /**
     * 获取指定表的字段信息，默认返回空列表
     */
    default List<ColumnMetadataVO> listColumns(DatasourceConfig datasourceConfig, String databaseName, String tableName) {
        return new ArrayList<>();
    }
}
