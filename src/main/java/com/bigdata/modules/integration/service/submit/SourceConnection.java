package com.bigdata.modules.integration.service.submit;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.integration.enums.DataSourceKind;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CDC 源端连接参数解析。
 *
 * <p>CDC 连接器需要 host / port / database 三个独立参数，因此配置模型改成了显式字段。
 * 历史任务（字段移除前保存的）只有一条 JDBC URL，这里负责把它解析成 host / port / database：
 * 读取回显、保存、生成作业三条路径都先经过 {@link #normalize}，保证老任务可以无感编辑与上线。</p>
 *
 * <p>解析失败时不抛异常、也不删除原 url，交由 {@link #endpoint} 在真正使用时报 400，
 * 避免「打开老任务保存一次就把连接信息弄丢」。</p>
 */
public final class SourceConnection {

    private static final int DEFAULT_MYSQL_PORT = 3306;
    private static final int DEFAULT_POSTGRES_PORT = 5432;

    private SourceConnection() {
    }

    /** 解析后的端点信息 */
    public record Endpoint(String host, int port, String database, String schema) {
    }

    /**
     * 取得 CDC 连接端点；缺字段或 URL 解析失败时抛 400（带可读原因）。
     */
    public static Endpoint endpoint(DataSourceKind kind, Map<String, Object> config) {
        Map<String, Object> normalized = normalize(kind, config);
        String host = str(normalized, "host");
        String database = str(normalized, "database");
        if (host.isEmpty() || database.isEmpty()) {
            throw new BusinessException(400, kind.getLabel()
                    + " 源端缺少连接参数：host / port / database 必填；"
                    + "若沿用历史任务的 JDBC URL，请确认形如 jdbc:" + kind.getCode() + "://主机:端口/库名");
        }
        String portText = str(normalized, "port");
        int port;
        try {
            port = portText.isEmpty() ? defaultPort(kind) : Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "源端端口必须是数字，当前为: " + portText);
        }
        if (port <= 0 || port > 65535) {
            throw new BusinessException(400, "源端端口超出范围（1-65535）: " + port);
        }
        String schema = kind == DataSourceKind.POSTGRESQL
                ? firstNonEmpty(str(normalized, "schema"), "public") : "";
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
        if (kind == DataSourceKind.POSTGRESQL && parsed.schema() != null) {
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
        Endpoint endpoint = endpoint(kind, config);
        String url = kind == DataSourceKind.MYSQL
                ? "jdbc:mysql://" + endpoint.host() + ":" + endpoint.port() + "/" + endpoint.database()
                : "jdbc:postgresql://" + endpoint.host() + ":" + endpoint.port() + "/" + endpoint.database();
        if (kind == DataSourceKind.POSTGRESQL) {
            url = url + "?currentSchema=" + endpoint.schema();
        }
        return url;
    }

    /** 启动模式：initial / latest-offset / timestamp，非法值直接报错 */
    public static String startupMode(Map<String, Object> config) {
        String raw = str(config, "startupMode");
        String mode = raw.isEmpty() ? "initial" : raw.toLowerCase();
        switch (mode) {
            case "initial":
            case "latest-offset":
            case "timestamp":
                return mode;
            case "earliest-offset":
                throw new BusinessException(400, "CDC 启动模式不支持 earliest-offset，"
                        + "请使用 initial / latest-offset / timestamp");
            default:
                throw new BusinessException(400, "CDC 启动模式不合法: " + raw
                        + "（可选 initial / latest-offset / timestamp）");
        }
    }

    /** timestamp 模式必须给出毫秒时间戳 */
    public static Long startupTimestamp(String mode, Map<String, Object> config) {
        if (!"timestamp".equals(mode)) {
            return null;
        }
        String raw = str(config, "startupTimestamp");
        if (raw.isEmpty()) {
            throw new BusinessException(400, "启动模式为 timestamp 时必须填写启动时间戳（毫秒）");
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "启动时间戳必须是毫秒时间戳，当前为: " + raw);
        }
    }

    private static int defaultPort(DataSourceKind kind) {
        return kind == DataSourceKind.MYSQL ? DEFAULT_MYSQL_PORT : DEFAULT_POSTGRES_PORT;
    }

    private record Parsed(String host, int port, String database, String schema) {
    }

    /** 解析 jdbc:kind://host[:port]/db[?params]，失败返回 null */
    private static Parsed parseUrl(DataSourceKind kind, String url) {
        String prefix = kind == DataSourceKind.MYSQL ? "jdbc:mysql://" : "jdbc:postgresql://";
        if (!url.toLowerCase().startsWith(prefix)) {
            return null;
        }
        String rest = url.substring(prefix.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        String authority = rest.substring(0, slash);
        String tail = rest.substring(slash + 1);
        String params = "";
        int query = tail.indexOf('?');
        if (query >= 0) {
            params = tail.substring(query + 1);
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
        int hostEnd = authority.length();
        int port = defaultPort(kind);
        int colon;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) {
                return null;
            }
            colon = authority.indexOf(':', close);
            if (colon > 0) {
                port = parsePort(authority.substring(colon + 1), port);
                hostEnd = close + 1;
            }
        } else {
            colon = authority.lastIndexOf(':');
            if (colon > 0) {
                port = parsePort(authority.substring(colon + 1), port);
                hostEnd = colon;
            }
        }
        String host = authority.substring(0, hostEnd).trim();
        if (host.isEmpty()) {
            return null;
        }
        String schema = kind == DataSourceKind.POSTGRESQL ? schemaOf(params) : null;
        return new Parsed(host, port, database, schema);
    }

    private static int parsePort(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String schemaOf(String params) {
        for (String pair : params.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "currentSchema".equalsIgnoreCase(pair.substring(0, eq).trim())) {
                String value = pair.substring(eq + 1).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    private static String firstNonEmpty(String first, String second) {
        return first == null || first.isEmpty() ? second : first;
    }

    private static String str(Map<String, Object> config, String key) {
        if (config == null) {
            return "";
        }
        Object value = config.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
