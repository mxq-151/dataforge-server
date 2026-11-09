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

    /** 是否为 CDC 源端（只有 MySQL / PostgreSQL） */
    public boolean isCdcSource() {
        return this == MYSQL || this == POSTGRESQL;
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
