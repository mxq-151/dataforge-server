package com.bigdata.modules.integration.enums;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据集成支持的源端 / 目标端类型，以及每类连接参数的表单描述。
 *
 * <p>表单描述由后端下发（GET /integration/options），前端据此动态渲染配置项，
 * 避免前后端各维护一份字段清单导致「新增数据源类型时页面漏字段」。</p>
 *
 * <p>模型是「一份源连接 + 一份目标连接 + 一张表清单」：源端不再配置单表 table/topic，
 * 也不再要求填写字段列表；目标端不再配置单个 topic/索引/表名，改为可选的命名模板
 * {@code nameTemplate}（默认 {@code {table}}，支持 {@code {db}}），由服务端按表推导目标标识。</p>
 *
 * <p>任务统一提交为 Flink CDC 作业，因此源端需要的是 CDC 连接的独立参数
 * （host / port / database / username / password），而不是一条 JDBC URL；
 * 历史任务里的 {@code url} 会在读取与保存时由服务端解析成 host/port/database
 * （见 {@code SourceConnection}）。字段清单仍由源库元数据自动读取（表清单 + 排除字段决定）。</p>
 */
public enum DataSourceKind {

    MYSQL("mysql", "MySQL", true, false,
            fields("host", "主机", true, "127.0.0.1", "text",
                    "port", "端口", true, "3306", "text",
                    "database", "数据库", true, "order_db", "text",
                    "username", "用户名", true, "root", "text",
                    "password", "密码", true, "******", "password",
                    "where", "过滤条件（对所有表生效）", false, "id > 100", "text",
                    "serverId", "server-id 基数（可选）", false, "5400（每表 +1）", "text",
                    "startupMode", "启动模式", false, "initial / latest-offset / timestamp", "text",
                    "startupTimestamp", "启动时间戳（毫秒，timestamp 模式用）", false, "1700000000000", "text",
                    "serverTimezone", "时区", false, "Asia/Shanghai", "text",
                    "connectionPoolSize", "连接池大小（可选）", false, "20", "text"),
            fields("url", "JDBC URL", true, "jdbc:mysql://127.0.0.1:3306/db", "text",
                    "username", "用户名", true, "root", "text",
                    "password", "密码", true, "******", "password",
                    "schema", "目标库（可选）", false, "dwd", "text",
                    "nameTemplate", "命名模板", false, "{table}（默认），可用 {db}_{table}", "text")),

    POSTGRESQL("postgresql", "PostgreSQL", true, false,
            fields("host", "主机", true, "127.0.0.1", "text",
                    "port", "端口", true, "5432", "text",
                    "database", "数据库", true, "order_db", "text",
                    "schema", "模式名（Schema）", false, "public", "text",
                    "username", "用户名", true, "postgres", "text",
                    "password", "密码", true, "******", "password",
                    "where", "过滤条件（对所有表生效）", false, "id > 100", "text",
                    "startupMode", "启动模式", false, "initial / latest-offset / timestamp", "text",
                    "startupTimestamp", "启动时间戳（毫秒，timestamp 模式用）", false, "1700000000000", "text",
                    "serverTimezone", "时区", false, "Asia/Shanghai", "text",
                    "connectionPoolSize", "连接池大小（可选）", false, "20", "text"),
            fields("url", "JDBC URL", true, "jdbc:postgresql://127.0.0.1:5432/db", "text",
                    "username", "用户名", true, "postgres", "text",
                    "password", "密码", true, "******", "password",
                    "schema", "目标 Schema（可选）", false, "public", "text",
                    "nameTemplate", "命名模板", false, "{table}（默认），可用 {db}_{table}", "text")),

    ORACLE("oracle", "Oracle", true, false,
            fields("host", "主机", true, "127.0.0.1", "text",
                    "port", "端口", true, "1521", "text",
                    "database", "服务名 / SID", true, "ORCLCDB", "text",
                    "schema", "模式名（Schema，必填）", true, "INVENTORY", "text",
                    "username", "用户名", true, "flinkuser", "text",
                    "password", "密码", true, "******", "password",
                    "where", "过滤条件（对所有表生效）", false, "ID > 100", "text",
                    "startupMode", "启动模式", false, "initial / latest-offset / specific-offset", "text",
                    "startupScn", "启动 SCN（specific-offset 模式用）", false, "2384593", "text",
                    "connectionPoolSize", "连接池大小（可选）", false, "20", "text"),
            fields()),

    SQLSERVER("sqlserver", "SQL Server", true, false,
            fields("host", "主机", true, "127.0.0.1", "text",
                    "port", "端口", true, "1433", "text",
                    "database", "数据库", true, "inventory", "text",
                    "schema", "模式名（默认 dbo）", false, "dbo", "text",
                    "username", "用户名", true, "sa", "text",
                    "password", "密码", true, "******", "password",
                    "where", "过滤条件（对所有表生效）", false, "id > 100", "text",
                    "startupMode", "启动模式", false, "initial / latest-offset", "text",
                    "serverTimezone", "时区", false, "UTC", "text",
                    "connectionPoolSize", "连接池大小（可选）", false, "20", "text"),
            fields()),

    DB2("db2", "Db2", true, false,
            fields("host", "主机", true, "127.0.0.1", "text",
                    "port", "端口", true, "50000", "text",
                    "database", "数据库", true, "TESTDB", "text",
                    "schema", "模式名（Schema，必填）", true, "DB2INST1", "text",
                    "username", "用户名", true, "db2inst1", "text",
                    "password", "密码", true, "******", "password",
                    "where", "过滤条件（对所有表生效）", false, "ID > 100", "text",
                    "startupMode", "启动模式", false, "initial / latest-offset", "text",
                    "serverTimezone", "时区", false, "UTC", "text",
                    "connectionPoolSize", "连接池大小（可选）", false, "20", "text"),
            fields()),

    OCEANBASE("oceanbase", "OceanBase", true, false,
            fields("host", "主机", true, "127.0.0.1", "text",
                    "port", "端口", true, "2881", "text",
                    "database", "数据库", true, "order_db", "text",
                    "username", "用户名（可带 @租户）", true, "root@test", "text",
                    "password", "密码", true, "******", "password",
                    "where", "过滤条件（对所有表生效）", false, "id > 100", "text",
                    "serverId", "server-id 基数（可选）", false, "5400（每表 +1）", "text",
                    "startupMode", "启动模式", false, "initial / latest-offset / timestamp", "text",
                    "startupTimestamp", "启动时间戳（毫秒，timestamp 模式用）", false, "1700000000000", "text",
                    "serverTimezone", "时区", false, "Asia/Shanghai", "text",
                    "connectionPoolSize", "连接池大小（可选）", false, "20", "text"),
            fields()),

    KAFKA("kafka", "Kafka", false, true,
            fields(),
            fields("bootstrapServers", "Broker 地址", true, "127.0.0.1:9092", "text",
                    "format", "消息格式", false, "json", "text",
                    "nameTemplate", "Topic 模板", false, "{table}（默认），可用 {db}_{table}", "text")),

    HDFS("hdfs", "HDFS", false, false,
            fields(),
            fields("path", "基础路径", false, "hdfs://namenode:8020/dwd（默认 /user/dataforge）", "text",
                    "format", "文件格式", false, "parquet", "text",
                    "nameTemplate", "目录命名模板", false, "{table}（默认），可用 {db}_{table}", "text")),

    S3("s3", "S3 / 对象存储", false, false,
            fields(),
            fields("endpoint", "Endpoint", true, "http://127.0.0.1:9000", "text",
                    "bucket", "Bucket", true, "dataforge", "text",
                    "path", "对象前缀", false, "dwd（默认 dataforge）", "text",
                    "accessKey", "AccessKey", true, "minioadmin", "text",
                    "secretKey", "SecretKey", true, "******", "password",
                    "format", "文件格式", false, "parquet", "text",
                    "nameTemplate", "对象命名模板", false, "{table}（默认），可用 {db}_{table}", "text")),

    ELASTICSEARCH("elasticsearch", "Elasticsearch", false, false,
            fields(),
            fields("hosts", "集群地址", true, "http://127.0.0.1:9200", "text",
                    "username", "用户名", false, "elastic", "text",
                    "password", "密码", false, "******", "password",
                    "nameTemplate", "索引命名模板", false, "{table}（默认），可用 {db}_{table}", "text")),

    DORIS("doris", "Apache Doris", false, false,
            fields(),
            fields("fenodes", "FE 地址", true, "127.0.0.1:8030", "text",
                    "database", "目标库", true, "dwd", "text",
                    "username", "用户名", true, "root", "text",
                    "password", "密码", true, "******", "password",
                    "nameTemplate", "表名模板", false, "{table}（默认），可用 {db}_{table}", "text")),

    STARROCKS("starrocks", "StarRocks", false, false,
            fields(),
            fields("jdbcUrl", "FE JDBC 地址", true, "jdbc:mysql://127.0.0.1:9030", "text",
                    "loadUrl", "FE HTTP 地址（导入）", true, "http://127.0.0.1:8030", "text",
                    "database", "目标库", true, "dwd", "text",
                    "username", "用户名", true, "root", "text",
                    "password", "密码", true, "******", "password",
                    "nameTemplate", "表名模板", false, "{table}（默认），可用 {db}_{table}", "text")),

    /**
     * Hive 表（仅作为目标端）。
     *
     * <p>Flink 分支通过 HiveCatalog 写入，需要 Hive 配置目录与版本。</p>
     */
    HIVE("hive", "Hive 表", false, false,
            fields(),
            fields("database", "数据库", true, "dwd", "text",
                    "path", "warehouse 基础路径", false, "/user/hive/warehouse（默认）", "text",
                    "format", "文件格式", false, "parquet", "text",
                    "hiveConfDir", "Hive 配置目录", false, "/etc/hive/conf", "text",
                    "hiveVersion", "Hive 版本", false, "3.1.2", "text",
                    "nameTemplate", "命名模板", false, "{table}（默认），可用 {db}_{table}", "text"));

    private final String code;
    private final String label;
    /** 是否可作为流式（流模式）源端 */
    private final boolean streamSource;
    /** 是否可作为流式（流模式）目标端 */
    private final boolean streamSink;
    private final List<ConnectorField> readFields;
    private final List<ConnectorField> writeFields;

    DataSourceKind(String code, String label, boolean streamSource, boolean streamSink,
                   List<ConnectorField> readFields, List<ConnectorField> writeFields) {
        this.code = code;
        this.label = label;
        this.streamSource = streamSource;
        this.streamSink = streamSink;
        this.readFields = readFields;
        this.writeFields = writeFields;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public boolean isStreamSource() {
        return streamSource;
    }

    public boolean isStreamSink() {
        return streamSink;
    }

    public List<ConnectorField> getReadFields() {
        return readFields;
    }

    public List<ConnectorField> getWriteFields() {
        return writeFields;
    }

    public static DataSourceKind fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (DataSourceKind kind : values()) {
            if (kind.code.equalsIgnoreCase(code)) {
                return kind;
            }
        }
        return null;
    }

    /** 是否为 CDC 源端 */
    public boolean isCdcSource() {
        return switch (this) {
            case MYSQL, POSTGRESQL, ORACLE, SQLSERVER, DB2, OCEANBASE -> true;
            default -> false;
        };
    }

    /** Flink CDC Table/SQL 连接器标识；非 CDC 源端返回 null */
    public String cdcConnector() {
        return switch (this) {
            case MYSQL -> "mysql-cdc";
            case POSTGRESQL -> "postgres-cdc";
            case ORACLE -> "oracle-cdc";
            case SQLSERVER -> "sqlserver-cdc";
            case DB2 -> "db2-cdc";
            case OCEANBASE -> "oceanbase-cdc";
            default -> null;
        };
    }

    /** CDC 源端读取字段元数据时使用的默认端口 */
    public int defaultPort() {
        return switch (this) {
            case MYSQL, OCEANBASE -> 3306;
            case POSTGRESQL -> 5432;
            case ORACLE -> 1521;
            case SQLSERVER -> 1433;
            case DB2 -> 50000;
            default -> 0;
        };
    }

    /**
     * 源表清单里前缀的含义：
     * DATABASE=库名（MySQL/PG/OceanBase，前缀必须等于源端 database）；
     * SCHEMA=模式名（SQL Server / Db2，允许 schema.table，可跨 schema）；
     * NONE=不填前缀（Oracle 的 schema 由连接参数单独指定）。
     */
    public TableQualifier tableQualifier() {
        return switch (this) {
            case MYSQL, POSTGRESQL, OCEANBASE -> TableQualifier.DATABASE;
            case SQLSERVER, DB2 -> TableQualifier.SCHEMA;
            default -> TableQualifier.NONE;
        };
    }

    /** 连接参数里的默认 schema；空串表示必填或无默认值 */
    public String defaultSchema() {
        return switch (this) {
            case POSTGRESQL -> "public";
            case SQLSERVER -> "dbo";
            default -> "";
        };
    }

    /** 连接器是否使用 server-id 区分实例（MySQL / OceanBase） */
    public boolean needsServerId() {
        return this == MYSQL || this == OCEANBASE;
    }

    /** 目标端是否支持 upsert（写入 DDL 需要声明主键） */
    public boolean supportsUpsertSink() {
        return this == MYSQL || this == POSTGRESQL || this == DORIS || this == STARROCKS;
    }

    /** 表清单前缀语义 */
    public enum TableQualifier {
        DATABASE,
        SCHEMA,
        NONE
    }

    private static List<ConnectorField> fields(Object... raw) {
        List<ConnectorField> list = new ArrayList<>();
        for (int i = 0; i + 4 < raw.length; i += 5) {
            list.add(new ConnectorField(String.valueOf(raw[i]), String.valueOf(raw[i + 1]),
                    Boolean.parseBoolean(String.valueOf(raw[i + 2])),
                    String.valueOf(raw[i + 3]), String.valueOf(raw[i + 4])));
        }
        return list;
    }

    /** 单个连接参数字段的表单描述 */
    public static class ConnectorField {
        private final String key;
        private final String label;
        private final boolean required;
        private final String placeholder;
        private final String type;

        public ConnectorField(String key, String label, boolean required, String placeholder, String type) {
            this.key = key;
            this.label = label;
            this.required = required;
            this.placeholder = placeholder;
            this.type = type;
        }

        public String getKey() {
            return key;
        }

        public String getLabel() {
            return label;
        }

        public boolean isRequired() {
            return required;
        }

        public String getPlaceholder() {
            return placeholder;
        }

        public String getType() {
            return type;
        }
    }
}
