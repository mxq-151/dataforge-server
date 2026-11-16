package com.bigdata.modules.telemetry.util;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Jackson JsonNode 的只读取值辅助：缺失节点返回 null，不抛异常、不补默认值。
 *
 * <p>引擎响应字段名在各版本间会变，取不到时由调用方决定是省略样本还是报错，
 * 这里只负责「有没有 / 是不是数字」。</p>
 */
public final class JsonNodes {

    private JsonNodes() {
    }

    /** 按 {@code a.b.c} 取子节点；任一层缺失返回 null。 */
    public static JsonNode path(JsonNode node, String dotted) {
        JsonNode current = node;
        for (String segment : dotted.split("\\.")) {
            if (current == null || current.isMissingNode()) {
                return null;
            }
            current = current.get(segment);
        }
        return current;
    }

    /** 数字节点转 Double，其余（缺失、字符串、布尔）返回 null。 */
    public static Double numeric(JsonNode node) {
        return node != null && node.isNumber() ? node.asDouble() : null;
    }

    /** 取字符串字段：缺失或空白返回 null。 */
    public static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }
}
