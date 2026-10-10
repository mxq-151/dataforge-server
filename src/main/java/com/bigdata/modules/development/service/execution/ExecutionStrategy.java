package com.bigdata.modules.development.service.execution;

import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.vo.ColumnMetadataVO;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public interface ExecutionStrategy {

    /**
     * 单表「当日最新分区」的大小。
     *
     * @param partitionValue 最新分区值（如 2026-10-09 / 20261009）
     * @param sizeBytes      该分区存储字节数；取不到为 null
     */
    record TablePartitionSize(String partitionValue, Long sizeBytes) {
    }

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
     * 采集指定库的「表名 → 存储字节数」，供每日存储统计刷新使用。
     *
     * <p>只需大小、不做整表元数据同步时应走本方法：JDBC 系查系统表、Kyuubi 走
     * DESCRIBE EXTENDED、HBase 走 RegionMetrics。默认无低成本口径，返回空 Map；
     * 实现方失败时同样返回已采集到的部分（调用方按覆盖写处理，不影响存量值）。</p>
     */
    default Map<String, Long> listTableSizes(DatasourceConfig datasourceConfig, String databaseName) {
        return new HashMap<>();
    }

    /**
     * 采集指定库的「表名 → 行数」，与 {@link #listTableSizes} 同源同口径。
     *
     * <p>行数取各引擎维护的估算值（PG {@code pg_class.reltuples}、MySQL/Doris
     * {@code information_schema.TABLES.TABLE_ROWS}、ClickHouse {@code system.tables.total_rows}），
     * 不做 {@code count(*)} —— 资产盘点要的是常态成本，不是精确值。
     * 上游未统计过（值为负 / 缺列）的表不出现在返回结果中；<b>0 是合法值</b>（空表）。
     * 默认返回空 Map，表示该引擎无此口径，调用方应保留存量值而不是写 0。</p>
     */
    default Map<String, Long> listTableRowCounts(DatasourceConfig datasourceConfig, String databaseName) {
        return new HashMap<>();
    }

    /**
     * 采集「表名 → 当日最新分区大小」，供每日分区大小采集任务使用。
     *
     * <p>只对天分区表调用（判定见资产模块的 {@code DailyPartitionDetector}）：分区表整表大小
     * 只增不减，增长率要下沉到分区粒度才看得出单日突增。默认无此口径、返回空 Map；
     * 实现方失败时同样返回已采集到的部分，调用方按覆盖写处理，不影响存量值。</p>
     *
     * @param tableNames 需要采集的表名（同一库内），避免对整库逐表探测
     */
    default Map<String, TablePartitionSize> listLatestPartitions(DatasourceConfig datasourceConfig,
                                                                 String databaseName,
                                                                 Collection<String> tableNames) {
        return new HashMap<>();
    }
}
