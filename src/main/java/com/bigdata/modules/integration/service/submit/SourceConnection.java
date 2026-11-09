package com.bigdata.modules.integration.service.submit;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.integration.enums.DataSourceKind;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * CDC 源端连接参数解析。
 *
 * <p>支持的源端：MySQL、PostgreSQL、Oracle、SQL Server、Db2、OceanBase（MySQL 模式）。
 * CDC 连接器需要 host / port / database 三个独立参数，因此配置模型是显式字段；
 * 历史任务（字段移除前保存的）只有一条 JDBC URL，这里负责把它解析成 host / port / database：
 * 读取回显、保存、生成作业三条路径都先经过 {@link #normalize}。</p>
 *
 * <p>解析失败时不抛异常、也不删除原 url，交由 {@link #endpoint} 在真正使用时报 400，
 * 避免「打开老任务保存一次就把连接信息弄丢」。</p>
 */
public final class SourceConnection {

    private SourceConnection() {
    }

    /** 解析后的端点信息 */
    public record Endpoint(String host, int port, String database, String schema) {
    }

    /** 启动位点：模式 + timestamp 毫秒（timestamp 模式）+ SCN（Oracle specific-offset 模式） */
    public record Startup(String mode, Long timestampMillis, Long scn) {
    }

    /**
     * 取得 CDC 连接端点；缺字段或 URL 解析失败时抛 400（带可读原因）。
     */
    public static Endpoint endpoint(DataSourceKind kind, Map<String, Object> config) {
        Map<String, Object> normalized = normalize(kind, config);
        if (kind == DataSourceKind.MONGODB) {
            String hosts = str(normalized, "hosts");
            String database = str(normalized, "database");
            if (hosts.isEmpty() || database.isEmpty()) {
                throw new BusinessException(400, "MongoDB 源端缺少连接参数：hosts / database 必填");
            }
            return new Endpoint(hosts, 0, database, "");
        }
        if (kind == DataSourceKind.TIDB && str(normalized, "pdAddresses").isEmpty()) {
            throw new BusinessException(400, "TiDB 源端缺少连接参数：pd-addresses 必填（如 127.0.0.1:2379）");
        }
        String host = str(normalized, "host");
        String database = str(normalized, "database");
        if (host.isEmpty() || database.isEmpty()) {
            throw new BusinessException(400, kind.getLabel()
                    + " 源端缺少连接参数：host / port / database 必填；"
                    + "若沿用历史任务的 JDBC URL，请确认连接串格式与 " + kind.getLabel() + " 匹配");
        }
        String portText = str(normalized, "port");
        int port;
        try {
            port = portText.isEmpty() ? kind.defaultPort() : Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "源端端口必须是数字，当前为: " + portText);
        }
        if (port <= 0 || port > 65535) {
            throw new BusinessException(400, "源端端口超出范围（1-65535）: " + port);
        }
        String schema = schemaOf(kind, normalized);
        return new Endpoint(host, port, database, schema);
    }

    /**
     * 把配置补成 host / port / database 显式字段；只有成功解析出三者时才移除历史 url。
     *
     * @return 新的配置副本（入参为 null 时返回空 Map）
     */
    public static Map<String, Object> normalize(DataSourceKind kind, Map<String, Object> config) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (config != null) {
            result.putAll(config);
        }
        if (!kind.isCdcSource()) {
            return result;
        }
        if (kind == DataSourceKind.MONGODB) {
            // MongoDB 使用 hosts + database，不存在可以解析的 JDBC URL
            return result;
        }
        if (!str(result, "host").isEmpty() && !str(result, "database").isEmpty()) {
            result.remove("url");
            return result;
        }
        String url = str(result, "url");
        if (url.isEmpty()) {
            return result;
        }
        Parsed parsed = parseUrl(kind, url);
        if (parsed == null) {
            return result;
        }
        result.putIfAbsent("host", parsed.host());
        result.putIfAbsent("port", String.valueOf(parsed.port()));
        result.putIfAbsent("database", parsed.database());
        if (parsed.schema() != null) {
            result.putIfAbsent("schema", parsed.schema());
        }
        // 已完整落到显式字段，删掉旧 url，避免详情里再出现一条连接串
        if (!str(result, "host").isEmpty() && !str(result, "database").isEmpty()) {
            result.remove("url");
        }
        return result;
    }

    /** 从显式字段拼回 JDBC URL（读取字段清单时连源库使用） */
    public static String jdbcUrl(DataSourceKind kind, Map<String, Object> config) {
        if (kind == DataSourceKind.MONGODB) {
            throw new BusinessException(400, "MongoDB 没有 JDBC 元数据，字段清单由集合样本推断");
        }
        Endpoint endpoint = endpoint(kind, config);
        String hostPort = endpoint.host() + ":" + endpoint.port();
        return switch (kind) {
            case MYSQL, OCEANBASE, TIDB -> "jdbc:mysql://" + hostPort + "/" + endpoint.database();
            case POSTGRESQL -> "jdbc:postgresql://" + hostPort + "/" + endpoint.database()
                    + "?currentSchema=" + endpoint.schema();
            case ORACLE -> "jdbc:oracle:thin:@//" + hostPort + "/" + endpoint.database();
            case SQLSERVER -> "jdbc:sqlserver://" + hostPort + ";databaseName=" + endpoint.database();
            case DB2 -> "jdbc:db2://" + hostPort + "/" + endpoint.database();
            default -> throw new BusinessException(400, "源端类型不是 CDC 数据源: " + kind.getLabel());
        };
    }

    /**
     * 启动位点校验。每种连接器支持的模式不同：
     * MySQL / PostgreSQL / OceanBase：initial / latest-offset / timestamp；
     * Oracle：initial / latest-offset / specific-offset（配 SCN）；
     * SQL Server / Db2：initial / latest-offset。
     */
    public static Startup startup(DataSourceKind kind, Map<String, Object> config) {
        String raw = str(config, "startupMode");
        String mode = raw.isEmpty() ? "initial" : raw.toLowerCase(Locale.ROOT);
        switch (mode) {
            case "initial", "latest-offset" -> {
                return new Startup(mode, null, null);
            }
            case "timestamp" -> {
                if (!supportsTimestamp(kind)) {
                    throw new BusinessException(400, kind.getLabel()
                            + " 不支持 timestamp 启动模式；" + allowedModes(kind));
                }
                return new Startup(mode, requireLong(config, "startupTimestamp",
                        "启动模式为 timestamp 时必须填写启动时间戳（毫秒）"), null);
            }
            case "specific-offset" -> {
                if (kind != DataSourceKind.ORACLE) {
                    throw new BusinessException(400, kind.getLabel()
                            + " 不支持 specific-offset 启动模式；" + allowedModes(kind));
                }
                return new Startup(mode, null, requireLong(config, "startupScn",
                        "启动模式为 specific-offset 时必须填写 Oracle SCN"));
            }
            default -> throw new BusinessException(400, "CDC 启动模式不合法: " + raw
                    + "；" + allowedModes(kind));
        }
    }

    private static boolean supportsTimestamp(DataSourceKind kind) {
        return kind == DataSourceKind.MYSQL || kind == DataSourceKind.POSTGRESQL
                || kind == DataSourceKind.OCEANBASE || kind == DataSourceKind.MONGODB;
    }

    private static String allowedModes(DataSourceKind kind) {
        return switch (kind) {
            case ORACLE -> "可选 initial / latest-offset / specific-offset";
            case SQLSERVER, DB2, TIDB -> "可选 initial / latest-offset";
            default -> "可选 initial / latest-offset / timestamp";
        };
    }

    private static Long requireLong(Map<String, Object> config, String key, String message) {
        String raw = str(config, key);
        if (raw.isEmpty()) {
            throw new BusinessException(400, message);
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new BusinessException(400, message + "，当前为: " + raw);
        }
    }

    private static String schemaOf(DataSourceKind kind, Map<String, Object> config) {
        String raw = str(config, "schema");
        if (!raw.isEmpty()) {
            return raw;
        }
        String fallback = kind.defaultSchema();
        if (!fallback.isEmpty()) {
            return fallback;
        }
        if (kind == DataSourceKind.ORACLE || kind == DataSourceKind.DB2) {
            throw new BusinessException(400, kind.getLabel() + " 源端缺少模式名：schema 必填");
        }
        return "";
    }

    private record Parsed(String host, int port, String database, String schema) {
    }

    /** 解析历史任务里的 JDBC URL，失败返回 null */
    private static Parsed parseUrl(DataSourceKind kind, String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        try {
            return switch (kind) {
                case MYSQL, OCEANBASE, TIDB -> parseUrlWithScheme(url, "jdbc:mysql://", kind.defaultPort());
                case POSTGRESQL -> {
                    Parsed parsed = parseUrlWithScheme(url, "jdbc:postgresql://", 5432);
                    yield parsed == null ? null
                            : new Parsed(parsed.host(), parsed.port(), parsed.database(),
                            schemaParam(url, "currentSchema"));
                }
                case ORACLE -> parseOracleUrl(url);
                case SQLSERVER -> parseSqlServerUrl(url);
                case DB2 -> parseUrlWithScheme(url, "jdbc:db2://", 50000);
                default -> null;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 解析 jdbc:xxx://host[:port]/db[?params] */
    private static Parsed parseUrlWithScheme(String url, String prefix, int defaultPort) {
        if (!url.toLowerCase(Locale.ROOT).startsWith(prefix)) {
            return null;
        }
        String rest = url.substring(prefix.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        String authority = rest.substring(0, slash);
        String tail = rest.substring(slash + 1);
        int query = tail.indexOf('?');
        if (query >= 0) {
            tail = tail.substring(0, query);
        }
        int semicolon = tail.indexOf(';');
        if (semicolon >= 0) {
            tail = tail.substring(0, semicolon);
        }
        String database = tail.trim();
        if (database.isEmpty()) {
            return null;
        }
        HostPort hostPort = parseHostPort(authority, defaultPort);
        return hostPort == null ? null
                : new Parsed(hostPort.host(), hostPort.port(), database, null);
    }

    /** jdbc:oracle:thin:@//host:port/service 或 jdbc:oracle:thin:@host:port:SID */
    private static Parsed parseOracleUrl(String url) {
        String prefix = "jdbc:oracle:thin:@";
        if (!url.toLowerCase(Locale.ROOT).startsWith(prefix)) {
            return null;
        }
        String rest = url.substring(prefix.length());
        if (rest.startsWith("//")) {
            return parseUrlWithScheme("jdbc:oracle:thin://" + rest.substring(2),
                    "jdbc:oracle:thin://", 1521);
        }
        int colon = rest.lastIndexOf(':');
        if (colon <= 0 || colon == rest.length() - 1) {
            return null;
        }
        String hostPort = rest.substring(0, colon);
        String database = rest.substring(colon + 1).trim();
        if (database.isEmpty()) {
            return null;
        }
        HostPort parsed = parseHostPort(hostPort, 1521);
        return parsed == null ? null : new Parsed(parsed.host(), parsed.port(), database, null);
    }

    /** jdbc:sqlserver://host:port;databaseName=db;... */
    private static Parsed parseSqlServerUrl(String url) {
        String prefix = "jdbc:sqlserver://";
        if (!url.toLowerCase(Locale.ROOT).startsWith(prefix)) {
            return null;
        }
        String rest = url.substring(prefix.length());
        String[] segments = rest.split(";");
        HostPort hostPort = parseHostPort(segments[0], 1433);
        if (hostPort == null) {
            return null;
        }
        String database = "";
        for (int i = 1; i < segments.length; i++) {
            int eq = segments[i].indexOf('=');
            if (eq > 0 && "databaseName".equalsIgnoreCase(segments[i].substring(0, eq).trim())) {
                database = segments[i].substring(eq + 1).trim();
            }
        }
        return database.isEmpty() ? null
                : new Parsed(hostPort.host(), hostPort.port(), database, null);
    }

    private record HostPort(String host, int port) {
    }

    private static HostPort parseHostPort(String authority, int defaultPort) {
        String text = authority.trim();
        if (text.isEmpty()) {
            return null;
        }
        int hostEnd = text.length();
        int port = defaultPort;
        int colon;
        if (text.startsWith("[")) {
            int close = text.indexOf(']');
            if (close < 0) {
                return null;
            }
            colon = text.indexOf(':', close);
            if (colon > 0) {
                port = parsePort(text.substring(colon + 1), port);
                hostEnd = close + 1;
            }
        } else {
            colon = text.lastIndexOf(':');
            if (colon > 0) {
                port = parsePort(text.substring(colon + 1), port);
                hostEnd = colon;
            }
        }
        String host = text.substring(0, hostEnd).trim();
        return host.isEmpty() ? null : new HostPort(host, port);
    }

    private static int parsePort(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String schemaParam(String url, String key) {
        int query = url.indexOf('?');
        if (query < 0) {
            return null;
        }
        for (String pair : url.substring(query + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && key.equalsIgnoreCase(pair.substring(0, eq).trim())) {
                String value = pair.substring(eq + 1).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    private static String str(Map<String, Object> config, String key) {
        if (config == null) {
            return "";
        }
        Object value = config.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
