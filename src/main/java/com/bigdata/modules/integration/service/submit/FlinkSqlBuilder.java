package com.bigdata.modules.integration.service.submit;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.integration.config.IntegrationClusterProperties;
import com.bigdata.modules.integration.enums.DataSourceKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 把集成任务定义翻译为 Flink CDC 作业脚本（单源连接 + 单目标连接 + 多张表）。
 *
 * <p>源端统一使用 Flink CDC：MySQL 用 {@code mysql-cdc}、PostgreSQL 用 {@code postgres-cdc}，
 * 连接参数为 hostname / port / database-name / table-name / username / password，
 * 历史任务里的 JDBC URL 先由 {@link SourceConnection} 解析成显式字段。</p>
 *
 * <p>多表的组织方式是<b>每张表一个 CDC source 实例</b>（source 与 sink 一一对应），
 * 而不是把整库合并成一个正则 table-name：这样每张表的投影、排除字段、目标标识都能独立推导。
 * 代价是 MySQL 需要多个 server-id，因此按「基数 + 表序号」分配：
 * {@code server-id = serverId 基数 + i}，基数默认 5400 + (taskId % 100000) * 10，
 * 保存任务时就会校验基数合法与范围不溢出（见 {@code IntegrationTaskService#checkCdcTables}）。</p>
 *
 * <p>列清单由源库元数据自动读取后去掉该表排除字段，生成显式列投影而不是 {@code SELECT *}；
 * 读并行度落到 {@code parallelism.default} 与 {@code scan.incremental.snapshot.parallelism}，
 * 写并行度落到每个 sink 的 {@code sink.parallelism}；过滤条件写在 INSERT ... SELECT 的 WHERE 里。</p>
 */
public final class FlinkSqlBuilder {

    /** MySQL server-id 缺省基数：同一任务内按表序号递增 */
    static final long DEFAULT_SERVER_ID_BASE = 5400L;
    /** 缺省基数按 taskId 错开，降低同库多任务之间的 server-id 冲突概率 */
    private static final long TASK_SERVER_ID_STRIDE = 10L;

    private FlinkSqlBuilder() {
    }

    public static String build(DataIntegrationSubmitter.SubmitContext ctx,
                               IntegrationClusterProperties.Flink props,
                               int readParallelism, int writeParallelism) {
        DataSourceKind sourceKind = requireKind(ctx.getSourceType(), "源端");
        if (!sourceKind.isCdcSource()) {
            throw new BusinessException(400, "源端仅支持 CDC 数据源（MySQL / PostgreSQL / Oracle / SQL Server / Db2 / OceanBase）：当前为 "
                    + sourceKind.getLabel());
        }
        DataSourceKind targetKind = requireKind(ctx.getTargetType(), "目标端");
        Map<String, Object> sourceConfig = SourceConnection.normalize(sourceKind, ctx.getSourceConfig());
        List<TableSpec> tables = withColumns(sourceKind, sourceConfig,
                normalizeTables(sourceKind, sourceConfig, sortedTables(ctx)));
        Map<String, String> identifiers = TargetNaming.resolveAll(targetKind, ctx.getTargetConfig(), tables);
        int read = Math.max(1, readParallelism);
        int write = Math.max(1, writeParallelism);
        long serverIdBase = serverIdBase(sourceKind, sourceConfig, ctx.getTaskId(), tables.size());
        SourceConnection.Startup startup = SourceConnection.startup(sourceKind, sourceConfig);
        String startupMode = startup.mode();
        String where = str(sourceConfig, "where");

        StringBuilder sql = new StringBuilder();
        appendHeader(sql, ctx, sourceKind, tables, identifiers, serverIdBase, startupMode);

        sql.append("SET 'pipeline.name' = ").append(quote(ctx.getTaskCode())).append(";\n");
        sql.append("-- CDC 作业为流式运行；作业模式只影响启动位点的默认值\n");
        sql.append("SET 'execution.runtime-mode' = 'streaming';\n");
        sql.append("SET 'parallelism.default' = ").append(quote(String.valueOf(read))).append(";\n");
        boolean checkpointOverridden = ctx.getExtraParams() != null
                && ctx.getExtraParams().containsKey("execution.checkpointing.interval");
        if (props.getCheckpointInterval() > 0 && !checkpointOverridden) {
            sql.append("SET 'execution.checkpointing.interval' = ")
                    .append(quote(props.getCheckpointInterval() + "ms")).append(";\n");
        }
        if (ctx.getExtraParams() != null) {
            for (Map.Entry<String, Object> entry : ctx.getExtraParams().entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                sql.append("SET ").append(quote(entry.getKey())).append(" = ")
                        .append(quote(String.valueOf(entry.getValue()))).append(";\n");
            }
        }
        sql.append('\n');

        if (targetKind == DataSourceKind.HIVE) {
            appendHiveCatalog(sql, ctx.getTargetConfig());
        }

        List<List<ColumnDef>> projections = new ArrayList<>();
        for (int i = 0; i < tables.size(); i++) {
            TableSpec table = tables.get(i);
            List<ColumnDef> projection = table.projection();
            projections.add(projection);
            appendSource(sql, sourceKind, sourceConfig, table, projection, "src_" + i, read,
                    serverIdFor(sourceKind, serverIdBase, i), startup,
                    ctx.getTaskCode());
            appendSink(sql, targetKind, ctx.getTargetConfig(), identifiers.get(table.getSourceTable()),
                    projection, "snk_" + i, write, ctx.getTaskCode());
        }

        for (int i = 0; i < tables.size(); i++) {
            sql.append(insertStatement(targetKind, identifiers.get(tables.get(i).getSourceTable()),
                    projections.get(i), i, where)).append('\n');
        }
        return sql.toString();
    }

    /**
     * 表清单归一化：
     * <ul>
     *   <li>MySQL / PostgreSQL / OceanBase：表清单只填表名，库名统一取源端 database；
     *       老数据带「库.表」时库名必须与源端一致；</li>
     *   <li>SQL Server / Db2：允许「schema.table」，只填表名时补源端 schema（SQL Server 默认 dbo）；</li>
     *   <li>Oracle：只填表名，schema 由连接参数单独指定。</li>
     * </ul>
     */
    private static List<TableSpec> normalizeTables(DataSourceKind kind, Map<String, Object> config,
                                                  List<TableSpec> tables) {
        SourceConnection.Endpoint endpoint = SourceConnection.endpoint(kind, config);
        List<TableSpec> result = new ArrayList<>(tables.size());
        switch (kind.tableQualifier()) {
            case DATABASE -> {
                String database = endpoint.database();
                for (TableSpec table : tables) {
                    String db = table.database();
                    if (db.isEmpty()) {
                        result.add(new TableSpec(database + "." + table.getSourceTable(),
                                table.getExcludeColumns(), table.getSortNo(), table.getColumns()));
                        continue;
                    }
                    if (!db.equalsIgnoreCase(database)) {
                        throw new BusinessException(400, "表清单里的「" + table.getSourceTable()
                                + "」属于库「" + db + "」，与源端数据库「" + database + "」不一致；"
                                + "CDC 一份源连接只能读一个库，表清单请只填表名，或拆分任务");
                    }
                    result.add(table);
                }
            }
            case SCHEMA -> {
                String schema = str(config, "schema");
                if (schema.isEmpty()) {
                    schema = kind.defaultSchema();
                }
                for (TableSpec table : tables) {
                    String prefix = table.database();
                    if (!prefix.isEmpty()) {
                        if (table.table().isEmpty()) {
                            throw new BusinessException(400, "表「" + table.getSourceTable()
                                    + "」缺少表名，请填写「schema.表名」或只填表名");
                        }
                        result.add(table);
                        continue;
                    }
                    if (schema.isEmpty()) {
                        throw new BusinessException(400, kind.getLabel()
                                + " 表「" + table.getSourceTable() + "」需要 schema：请填写「schema.表名」或在源端配置 schema");
                    }
                    result.add(new TableSpec(schema + "." + table.getSourceTable(),
                            table.getExcludeColumns(), table.getSortNo(), table.getColumns()));
                }
            }
            default -> {
                for (TableSpec table : tables) {
                    if (!table.database().isEmpty()) {
                        throw new BusinessException(400, kind.getLabel() + " 的表清单只填表名（如 "
                                + table.table() + "），schema 请在源端连接的 schema 参数里指定");
                    }
                    result.add(table);
                }
            }
        }
        return result;
    }

    /** 为没有列清单的表从源库元数据补充列（测试可直接构造带列的 TableSpec 跳过数据库） */
    private static List<TableSpec> withColumns(DataSourceKind kind, Map<String, Object> config,
                                               List<TableSpec> tables) {
        List<TableSpec> missing = tables.stream()
                .filter(table -> table.getColumns().isEmpty())
                .collect(Collectors.toList());
        if (missing.isEmpty()) {
            return tables;
        }
        Map<String, List<ColumnDef>> resolved = SourceColumnResolver.resolveAll(kind, config, missing);
        return tables.stream()
                .map(table -> table.getColumns().isEmpty()
                        ? table.withColumns(resolved.get(table.getSourceTable())) : table)
                .collect(Collectors.toList());
    }

    private static List<TableSpec> sortedTables(DataIntegrationSubmitter.SubmitContext ctx) {
        List<TableSpec> tables = ctx.getTables() == null ? List.of() : ctx.getTables().stream()
                .sorted(Comparator.comparingInt(TableSpec::getSortNo))
                .collect(Collectors.toList());
        if (tables.isEmpty()) {
            throw new BusinessException(400, "至少需要配置一张需要同步的表");
        }
        return tables;
    }

    /** MySQL / OceanBase 需要按表分配 server-id；PostgreSQL 用复制槽、Oracle/SQL Server/Db2 走各自日志，不占 server-id */
    public static long serverIdBase(DataSourceKind kind, Map<String, Object> config, Long taskId, int tableCount) {
        if (!kind.needsServerId()) {
            return 0L;
        }
        String raw = str(config, "serverId");
        long base;
        if (raw.isEmpty()) {
            long seed = taskId == null ? 0L : Math.abs(taskId % 100_000L);
            base = DEFAULT_SERVER_ID_BASE + seed * TASK_SERVER_ID_STRIDE;
        } else {
            try {
                base = Long.parseLong(raw);
            } catch (NumberFormatException e) {
                throw new BusinessException(400, "server-id 基数必须是数字，当前为: " + raw);
            }
        }
        if (base <= 0) {
            throw new BusinessException(400, "server-id 基数必须大于 0，当前为: " + base);
        }
        if (base + tableCount - 1 > Integer.MAX_VALUE) {
            throw new BusinessException(400, "server-id 基数 " + base + " 加上 " + tableCount
                    + " 张表后超出 32 位整数范围，请调小基数");
        }
        return base;
    }

    private static long serverIdFor(DataSourceKind kind, long base, int index) {
        return kind.needsServerId() ? base + index : 0L;
    }

    private static void appendHeader(StringBuilder sql, DataIntegrationSubmitter.SubmitContext ctx,
                                     DataSourceKind sourceKind, List<TableSpec> tables,
                                     Map<String, String> identifiers, long serverIdBase,
                                     String startupMode) {
        sql.append("-- generated by dataforge data-integration (Flink CDC): ")
                .append(ctx.getTaskCode()).append('\n');
        sql.append("-- source: ").append(sourceKind.getLabel()).append(" / connector=")
                .append(cdcConnector(sourceKind)).append(", startup-mode=").append(startupMode)
                .append(", tables=").append(tables.size()).append('\n');
        if (sourceKind.needsServerId()) {
            sql.append("-- server-id：每表一个 CDC source，按基数递增分配（基数 ")
                    .append(serverIdBase).append("）\n");
        }
        for (TableSpec table : tables) {
            sql.append("--   ").append(table.describe()).append(" → ")
                    .append(identifiers.get(table.getSourceTable())).append('\n');
        }
        sql.append("-- 说明：每张表一个 CDC source + 一个 sink；投影列 = 全部字段 - 排除字段。\n\n");
    }

    private static void appendHiveCatalog(StringBuilder sql, Map<String, Object> targetConfig) {
        String confDir = str(targetConfig, "hiveConfDir");
        String version = str(targetConfig, "hiveVersion");
        sql.append("CREATE CATALOG IF NOT EXISTS `hive` WITH (\n");
        sql.append("  'type' = 'hive'");
        if (!confDir.isEmpty()) {
            sql.append(",\n  'hive-conf-dir' = ").append(quote(confDir));
        }
        if (!version.isEmpty()) {
            sql.append(",\n  'hive-version' = ").append(quote(version));
        }
        sql.append("\n);\n\n");
    }

    private static void appendSource(StringBuilder sql, DataSourceKind kind, Map<String, Object> config,
                                     TableSpec table, List<ColumnDef> columns, String name, int parallelism,
                                     long serverId, SourceConnection.Startup startup,
                                     String taskCode) {
        // SQL Server / Db2 的 table-name 必须是 schema.table；其余连接器 schema 单独传或不需要
        String tableName = kind.tableQualifier() == DataSourceKind.TableQualifier.SCHEMA
                ? table.getSourceTable() : table.table();
        if (kind == DataSourceKind.DB2) {
            // Db2 未加引号的对象默认大写
            tableName = tableName.toUpperCase(Locale.ROOT);
        }
        sql.append("CREATE TABLE `").append(name).append("` (\n");
        appendColumns(sql, columns, true);
        sql.append(") WITH (\n")
                .append(renderOptions(sourceOptions(kind, config, tableName, parallelism, serverId,
                        startup, taskCode, indexOf(name))))
                .append(");\n\n");
    }

    /** src_0 → 0，用于 PostgreSQL 复制槽命名 */
    private static int indexOf(String sourceName) {
        int underscore = sourceName.lastIndexOf('_');
        return underscore < 0 ? 0 : Integer.parseInt(sourceName.substring(underscore + 1));
    }

    private static void appendSink(StringBuilder sql, DataSourceKind kind, Map<String, Object> config,
                                   String identifier, List<ColumnDef> columns, String name,
                                   int writeParallelism, String taskCode) {
        if (kind == DataSourceKind.HIVE) {
            String database = identifier.substring(0, identifier.indexOf('.'));
            String table = identifier.substring(identifier.indexOf('.') + 1);
            sql.append("CREATE TABLE IF NOT EXISTS `hive`.").append(SqlDialect.FLINK.quote(database))
                    .append('.').append(SqlDialect.FLINK.quote(table)).append(" (\n");
            appendColumns(sql, columns, false);
            Map<String, String> options = new LinkedHashMap<>();
            options.put("sink.parallelism", String.valueOf(writeParallelism));
            putIfPresent(options, "sink.partition-commit.policy.kind",
                    defaultValue(str(config, "commitPolicy"), "metastore,success-file"));
            sql.append(") WITH (\n").append(renderOptions(options)).append(");\n\n");
            return;
        }
        sql.append("CREATE TABLE `").append(name).append("` (\n");
        appendColumns(sql, columns, kind.supportsUpsertSink());
        sql.append(") WITH (\n")
                .append(renderOptions(sinkOptions(kind, config, identifier, writeParallelism, taskCode)))
                .append(");\n\n");
    }

    private static String insertStatement(DataSourceKind kind, String identifier, List<ColumnDef> columns,
                                          int index, String where) {
        String targetName;
        if (kind == DataSourceKind.HIVE) {
            String database = identifier.substring(0, identifier.indexOf('.'));
            String table = identifier.substring(identifier.indexOf('.') + 1);
            targetName = "`hive`." + SqlDialect.FLINK.quote(database) + "." + SqlDialect.FLINK.quote(table);
        } else {
            targetName = "`snk_" + index + "`";
        }
        List<String> names = new ArrayList<>();
        for (ColumnDef column : columns) {
            names.add("`" + column.getName() + "`");
        }
        String filter = where == null || where.trim().isEmpty() ? "" : " WHERE " + where.trim();
        return "INSERT INTO " + targetName + " SELECT " + String.join(", ", names)
                + " FROM `src_" + index + "`" + filter + ";";
    }

    private static void appendColumns(StringBuilder sql, List<ColumnDef> columns, boolean withPrimaryKey) {
        List<String> primaryKeys = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            ColumnDef column = columns.get(i);
            sql.append("  `").append(column.getName()).append("` ").append(column.getFlinkType());
            if (column.isPrimaryKey()) {
                primaryKeys.add(column.getName());
            }
            boolean last = i == columns.size() - 1;
            boolean primaryKeyLine = withPrimaryKey && !primaryKeys.isEmpty();
            sql.append(last && !primaryKeyLine ? "\n" : ",\n");
        }
        if (withPrimaryKey && !primaryKeys.isEmpty()) {
            List<String> quoted = new ArrayList<>(primaryKeys.size());
            for (String key : primaryKeys) {
                quoted.add("`" + key + "`");
            }
            sql.append("  PRIMARY KEY (").append(String.join(", ", quoted)).append(") NOT ENFORCED\n");
        }
    }

    /** Flink CDC Table/SQL 连接器标识 */
    static String cdcConnector(DataSourceKind kind) {
        String connector = kind.cdcConnector();
        if (connector == null) {
            throw new BusinessException(400, "源端类型不是 CDC 数据源: " + kind.getLabel());
        }
        return connector;
    }

    private static Map<String, String> sourceOptions(DataSourceKind kind, Map<String, Object> c,
                                                     String tableName, int parallelism, long serverId,
                                                     SourceConnection.Startup startup,
                                                     String taskCode, int index) {
        SourceConnection.Endpoint endpoint = SourceConnection.endpoint(kind, c);
        Map<String, String> options = new LinkedHashMap<>();
        options.put("connector", cdcConnector(kind));
        if (kind == DataSourceKind.MONGODB) {
            // mongodb-cdc 使用 hosts / database / collection，没有 hostname/port/table-name
            options.put("hosts", endpoint.host());
            options.put("database", endpoint.database());
            options.put("collection", tableName);
            putIfPresent(options, "username", str(c, "username"));
            putIfPresent(options, "password", str(c, "password"));
            putIfPresent(options, "connection.options", str(c, "connectionOptions"));
            options.put("scan.startup.mode", startup.mode());
            if (startup.timestampMillis() != null) {
                options.put("scan.startup.timestamp-millis", String.valueOf(startup.timestampMillis()));
            }
            return options;
        }
        if (kind == DataSourceKind.TIDB) {
            // tidb-cdc 通过 PD 发现 TiKV，host/port 只用于平台读取字段元数据
            options.put("pd-addresses", required(c, "pdAddresses", "PD 地址"));
            options.put("database-name", endpoint.database());
            options.put("table-name", tableName);
            options.put("scan.startup.mode", startup.mode());
            return options;
        }
        options.put("hostname", endpoint.host());
        options.put("port", String.valueOf(endpoint.port()));
        options.put("database-name", endpoint.database());
        switch (kind) {
            case POSTGRESQL -> {
                options.put("schema-name", endpoint.schema());
                options.put("slot.name", slotName(taskCode, index));
                options.put("decoding.plugin.name", "pgoutput");
            }
            case ORACLE -> options.put("schema-name", endpoint.schema().toUpperCase(Locale.ROOT));
            case MYSQL, OCEANBASE -> options.put("server-id", String.valueOf(serverId));
            default -> {
                // SQL Server / Db2 的 schema 已包含在 table-name 里
            }
        }
        options.put("table-name", tableName);
        putIfPresent(options, "username", str(c, "username"));
        putIfPresent(options, "password", str(c, "password"));
        options.put("scan.startup.mode", startup.mode());
        if (startup.timestampMillis() != null) {
            options.put("scan.startup.timestamp-millis", String.valueOf(startup.timestampMillis()));
        }
        if (startup.scn() != null) {
            options.put("scan.startup.specific-offset.scn", String.valueOf(startup.scn()));
        }
        switch (kind) {
            case MYSQL -> {
                putIfPresent(options, "server-time-zone", str(c, "serverTimezone"));
                options.put("scan.incremental.snapshot.parallelism", String.valueOf(Math.max(1, parallelism)));
            }
            case OCEANBASE, SQLSERVER, DB2 -> putIfPresent(options, "server-time-zone", str(c, "serverTimezone"));
            default -> {
                // PostgreSQL / Oracle 不支持 server-time-zone
            }
        }
        if (kind == DataSourceKind.MYSQL || kind == DataSourceKind.POSTGRESQL
                || kind == DataSourceKind.ORACLE || kind == DataSourceKind.OCEANBASE) {
            putIfPresent(options, "connection.pool.size", str(c, "connectionPoolSize"));
        }
        return options;
    }

    /** PostgreSQL 复制槽名：小写字母/数字/下划线，长度不超过 63 */
    static String slotName(String taskCode, int index) {
        String code = taskCode == null ? "job" : taskCode.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]", "_");
        String slot = "df_" + code + "_" + index;
        return slot.length() <= 63 ? slot : slot.substring(0, 63);
    }

    private static Map<String, String> sinkOptions(DataSourceKind kind, Map<String, Object> c,
                                                   String identifier, int writeParallelism, String taskCode) {
        Map<String, String> options = new LinkedHashMap<>();
        switch (kind) {
            case MYSQL, POSTGRESQL -> {
                options.put("connector", "jdbc");
                options.put("url", required(c, "url", "JDBC URL"));
                options.put("table-name", jdbcTableName(c, identifier));
                putIfPresent(options, "username", str(c, "username"));
                putIfPresent(options, "password", str(c, "password"));
            }
            case DORIS -> {
                options.put("connector", "doris");
                options.put("fenodes", required(c, "fenodes", "FE 地址"));
                options.put("table.identifier", required(c, "database", "目标库") + "." + identifier);
                putIfPresent(options, "username", str(c, "username"));
                putIfPresent(options, "password", str(c, "password"));
                // CDC 需要跟随源端的删除；label 前缀保证同一 Flink 作业内导入事务唯一
                options.put("sink.enable-delete", "true");
                options.put("sink.label-prefix", dorisLabelPrefix(taskCode, identifier));
            }
            case STARROCKS -> {
                options.put("connector", "starrocks");
                options.put("jdbc-url", required(c, "jdbcUrl", "FE JDBC 地址"));
                options.put("load-url", required(c, "loadUrl", "FE HTTP 地址"));
                options.put("database-name", required(c, "database", "目标库"));
                options.put("table-name", identifier);
                putIfPresent(options, "username", str(c, "username"));
                putIfPresent(options, "password", str(c, "password"));
            }
            case KAFKA -> {
                options.put("connector", "kafka");
                options.put("topic", identifier);
                options.put("properties.bootstrap.servers", required(c, "bootstrapServers", "Broker 地址"));
                options.put("format", defaultValue(str(c, "format"), "json"));
            }
            case HDFS -> {
                options.put("connector", "filesystem");
                // 目标标识已由 TargetNaming 拼上基础路径（base + 模板），此处直接使用避免重复拼接
                options.put("path", identifier);
                options.put("format", defaultValue(str(c, "format"), "parquet"));
            }
            case S3 -> {
                options.put("connector", "filesystem");
                // 目标标识已包含 s3://bucket/prefix/模板 的完整路径，这里只做必填校验
                required(c, "bucket", "Bucket");
                options.put("path", identifier);
                options.put("format", defaultValue(str(c, "format"), "parquet"));
                putIfPresent(options, "s3.endpoint", str(c, "endpoint"));
                putIfPresent(options, "s3.access-key", str(c, "accessKey"));
                putIfPresent(options, "s3.secret-key", str(c, "secretKey"));
            }
            case ELASTICSEARCH -> {
                options.put("connector", defaultValue(str(c, "connector"), "elasticsearch-7"));
                options.put("hosts", required(c, "hosts", "集群地址"));
                options.put("index", identifier);
                putIfPresent(options, "username", str(c, "username"));
                putIfPresent(options, "password", str(c, "password"));
            }
            default -> throw new BusinessException(400, "Flink 不支持的目标端类型: " + kind.getCode());
        }
        options.put("sink.parallelism", String.valueOf(Math.max(1, writeParallelism)));
        return options;
    }

    /** JDBC 目标可选 schema 前缀：schema 存在时写成 schema.table */
    private static String jdbcTableName(Map<String, Object> c, String identifier) {
        String schema = str(c, "schema");
        return schema.isEmpty() ? identifier : schema + "." + identifier;
    }

    /** Doris 导入 label 前缀：只允许字母、数字、下划线、短横线，长度不超过 128 */
    private static String dorisLabelPrefix(String taskCode, String identifier) {
        String prefix = "df_" + (taskCode == null ? "job" : taskCode) + "_" + identifier;
        String sanitized = prefix.replaceAll("[^A-Za-z0-9_-]", "_");
        return sanitized.length() <= 128 ? sanitized : sanitized.substring(0, 128);
    }

    private static String renderOptions(Map<String, String> options) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Map.Entry<String, String> entry : options.entrySet()) {
            sb.append("  ").append(quote(entry.getKey())).append(" = ")
                    .append(quote(entry.getValue()));
            sb.append(++i == options.size() ? "\n" : ",\n");
        }
        return sb.toString();
    }

    private static DataSourceKind requireKind(String type, String stage) {
        DataSourceKind kind = DataSourceKind.fromCode(type);
        if (kind == null) {
            throw new BusinessException(400, stage + "类型不支持: " + type);
        }
        return kind;
    }

    private static void putIfPresent(Map<String, String> options, String key, String value) {
        if (value != null && !value.isEmpty()) {
            options.put(key, value);
        }
    }

    private static String required(Map<String, Object> config, String key, String label) {
        String value = str(config, key);
        if (value == null || value.isEmpty()) {
            throw new BusinessException(400, "缺少必填连接参数: " + label + " (" + key + ")");
        }
        return value;
    }

    private static String str(Map<String, Object> config, String key) {
        if (config == null) {
            return "";
        }
        Object value = config.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static String quote(String value) {
        return SqlDialect.literal(value);
    }
}
