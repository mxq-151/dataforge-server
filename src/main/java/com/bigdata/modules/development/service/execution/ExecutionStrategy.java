package com.bigdata.modules.development.service.execution;

import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.vo.ColumnMetadataVO;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * 采集指定库的「表名 → 存储字节数」，供每日存储大小刷新使用。
     *
     * <p>只需大小、不做整表元数据同步时应走本方法：JDBC 系查系统表、Kyuubi 走
     * DESCRIBE EXTENDED、HBase 走 RegionMetrics。默认无低成本口径，返回空 Map；
     * 实现方失败时同样返回已采集到的部分（调用方按覆盖写处理，不影响存量值）。</p>
     */
    default Map<String, Long> listTableSizes(DatasourceConfig datasourceConfig, String databaseName) {
        return new HashMap<>();
    }
}
