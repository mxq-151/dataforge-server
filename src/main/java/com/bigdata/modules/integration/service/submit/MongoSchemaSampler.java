package com.bigdata.modules.integration.service.submit;

import com.bigdata.common.exception.BusinessException;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.BsonType;
import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * MongoDB 字段清单推断。
 *
 * <p>MongoDB 没有 JDBC 元数据，Flink mongodb-cdc 的 SQL DDL 又必须显式声明列，
 * 因此这里从集合里抽样若干文档推断列与类型：</p>
 * <ul>
 *   <li>{@code _id} 固定作为主键（mongodb-cdc 只接受 _id 作为主键）；</li>
 *   <li>只支持扁平字段；遇到嵌套文档/数组直接报 400，避免生成与实际文档不匹配的 DDL；</li>
 *   <li>同一字段在不同样本中类型冲突时退化为 STRING。</li>
 * </ul>
 */
final class MongoSchemaSampler {

    private static final int SAMPLE_SIZE = 50;
    private static final int SERVER_SELECTION_TIMEOUT_SECONDS = 10;

    private MongoSchemaSampler() {
    }

    static Map<String, List<ColumnDef>> resolveAll(Map<String, Object> config, List<TableSpec> tables) {
        String hosts = str(config, "hosts");
        String database = str(config, "database");
        if (hosts.isEmpty() || database.isEmpty()) {
            throw new BusinessException(400, "MongoDB 源端缺少连接参数：hosts / database 必填");
        }
        MongoClientSettings settings = settings(config, hosts, database);
        Map<String, List<ColumnDef>> result = new LinkedHashMap<>();
        try (MongoClient client = MongoClients.create(settings)) {
            MongoDatabase db = client.getDatabase(database);
            for (TableSpec table : tables) {
                String collection = table.table();
                MongoCollection<Document> target = db.getCollection(collection);
                List<Document> samples = target.find().limit(SAMPLE_SIZE).into(new ArrayList<>());
                result.put(table.getSourceTable(), inferColumns(samples, collection));
            }
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BusinessException(400, "连接 MongoDB 推断字段失败：" + e.getMessage());
        }
    }

    private static MongoClientSettings settings(Map<String, Object> config, String hosts, String database) {
        String uri = hosts;
        if (!uri.startsWith("mongodb://") && !uri.startsWith("mongodb+srv://")) {
            uri = "mongodb://" + uri;
        }
        String options = str(config, "connectionOptions");
        if (!options.isEmpty()) {
            uri = uri + (uri.contains("?") ? "&" : "?") + options;
        }
        MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri))
                .applyToClusterSettings(b -> b.serverSelectionTimeout(
                        SERVER_SELECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        String username = str(config, "username");
        if (!username.isEmpty()) {
            String authSource = authSource(options, database);
            builder.credential(MongoCredential.createCredential(username, authSource,
                    str(config, "password").toCharArray()));
        }
        return builder.build();
    }

    private static String authSource(String options, String database) {
        for (String pair : options.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "authSource".equalsIgnoreCase(pair.substring(0, eq).trim())) {
                String value = pair.substring(eq + 1).trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return database;
    }

    /** 从样本文档推断 Flink 列定义（包可见，便于单测不依赖真实 MongoDB） */
    static List<ColumnDef> inferColumns(List<Document> samples, String collection) {
        if (samples == null || samples.isEmpty()) {
            throw new BusinessException(400, "MongoDB 集合「" + collection
                    + "」没有样本数据，无法推断字段；请先写入至少一条文档");
        }
        Map<String, String> types = new LinkedHashMap<>();
        for (Document document : samples) {
            for (Map.Entry<String, Object> entry : document.entrySet()) {
                String name = entry.getKey();
                String type = flinkType(entry.getValue(), collection, name);
                if (type == null) {
                    continue;
                }
                String existing = types.get(name);
                if (existing == null) {
                    types.put(name, type);
                } else if (!existing.equals(type)) {
                    // 同一字段类型不一致时退化为 STRING，保证建表不会因样本差异失败
                    types.put(name, "STRING");
                }
            }
        }
        if (types.isEmpty()) {
            throw new BusinessException(400, "MongoDB 集合「" + collection + "」没有可用字段");
        }
        List<ColumnDef> columns = new ArrayList<>(types.size());
        if (types.containsKey("_id")) {
            columns.add(new ColumnDef("_id", types.get("_id"), true));
        }
        for (Map.Entry<String, String> entry : types.entrySet()) {
            if ("_id".equals(entry.getKey())) {
                continue;
            }
            columns.add(new ColumnDef(entry.getKey(), entry.getValue()));
        }
        return columns;
    }

    private static String flinkType(Object value, String collection, String field) {
        if (value == null) {
            return null;
        }
        if (value instanceof Document || value instanceof List) {
            throw new BusinessException(400, "MongoDB 集合「" + collection + "」的字段「" + field
                    + "」是嵌套文档或数组，暂不支持自动推断；请改用扁平集合");
        }
        BsonType type = toBsonType(value);
        if (type == null) {
            return null;
        }
        return switch (type) {
            case OBJECT_ID, STRING, SYMBOL, REGULAR_EXPRESSION, JAVASCRIPT, JAVASCRIPT_WITH_SCOPE,
                 DB_POINTER, MIN_KEY, MAX_KEY -> "STRING";
            case BOOLEAN -> "BOOLEAN";
            case INT32 -> "INT";
            case INT64 -> "BIGINT";
            case DOUBLE -> "DOUBLE";
            case DECIMAL128 -> "DECIMAL(38,18)";
            case DATE_TIME, TIMESTAMP -> "TIMESTAMP(3)";
            case BINARY -> "BYTES";
            default -> "STRING";
        };
    }

    /** 兼容 BSON 值类型判断（避免直接依赖具体实现类） */
    private static BsonType toBsonType(Object value) {
        if (value instanceof org.bson.types.ObjectId) {
            return BsonType.OBJECT_ID;
        }
        if (value instanceof org.bson.types.Decimal128) {
            return BsonType.DECIMAL128;
        }
        if (value instanceof org.bson.types.Binary || value instanceof byte[]) {
            return BsonType.BINARY;
        }
        if (value instanceof java.util.Date || value instanceof java.time.Instant) {
            return BsonType.DATE_TIME;
        }
        if (value instanceof org.bson.BsonTimestamp) {
            return BsonType.TIMESTAMP;
        }
        if (value instanceof String) {
            return BsonType.STRING;
        }
        if (value instanceof Boolean) {
            return BsonType.BOOLEAN;
        }
        if (value instanceof Integer) {
            return BsonType.INT32;
        }
        if (value instanceof Long) {
            return BsonType.INT64;
        }
        if (value instanceof Double || value instanceof Float) {
            return BsonType.DOUBLE;
        }
        return BsonType.STRING;
    }

    private static String str(Map<String, Object> config, String key) {
        if (config == null) {
            return "";
        }
        Object value = config.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
