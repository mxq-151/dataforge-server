package com.bigdata.modules.logs.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Base64;

/**
 * 不透明游标。
 *
 * <p>调用方只应原样回传，不应解析或构造。游标里带 sourceId，跨源回传会被拒绝，
 * 避免「拿 A 源的游标查 B 源」导致偏移错乱。</p>
 *
 * <p>格式：{@code Base64Url(JSON {"v":1,"sourceId":"...","offset":123,"token":"..."})}。
 * offset 的语义由适配器解释（DS 为行偏移，file/YARN 为字节偏移）。</p>
 */
public final class LogCursor {

    private static final int VERSION = 1;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String sourceId;
    private final long offset;
    private final String token;

    public LogCursor(String sourceId, long offset, String token) {
        this.sourceId = sourceId;
        this.offset = offset;
        this.token = token;
    }

    public String getSourceId() {
        return sourceId;
    }

    public long getOffset() {
        return offset;
    }

    public String getToken() {
        return token;
    }

    /**
     * 解码游标；空值返回 null。
     *
     * @throws IllegalArgumentException 游标格式非法（由服务层转 400）
     */
    public static LogCursor decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(raw.trim());
            JsonNode node = MAPPER.readTree(bytes);
            if (node.path("v").asInt(-1) != VERSION) {
                throw new IllegalArgumentException("游标版本不支持，请从第一页重新查询");
            }
            String sid = node.hasNonNull("sourceId") ? node.get("sourceId").asText() : null;
            long offset = node.path("offset").asLong(-1);
            if (sid == null || sid.isBlank() || offset < 0) {
                throw new IllegalArgumentException("游标内容非法");
            }
            String token = node.hasNonNull("token") ? node.get("token").asText() : null;
            return new LogCursor(sid, offset, token);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("游标格式非法");
        }
    }

    public String encode() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("v", VERSION);
        node.put("sourceId", sourceId);
        node.put("offset", offset);
        if (token != null) {
            node.put("token", token);
        }
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MAPPER.writeValueAsBytes(node));
        } catch (Exception e) {
            throw new IllegalStateException("生成游标失败", e);
        }
    }
}
