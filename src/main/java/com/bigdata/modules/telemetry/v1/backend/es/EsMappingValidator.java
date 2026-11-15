package com.bigdata.modules.telemetry.v1.backend.es;

import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.Requirement;
import com.bigdata.modules.telemetry.model.RequirementKind;
import com.bigdata.modules.telemetry.v1.spi.BackendException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Elasticsearch 版本与索引映射校验。
 *
 * <p>在能力判定阶段校验：{@code @timestamp} 必须是 date，{@code stream/service_type/
 * component_id/host_id} 必须是 keyword，{@code message.keyword} 的 ignore_above 必须足够大。
 * 不满足直接 {@code ES_MAPPING_INCOMPATIBLE}，避免查询时静默漏长行。</p>
 *
 * <p>结果按 TTL 缓存，避免每次查询都打 {@code _mapping}。</p>
 */
final class EsMappingValidator {

    static final int MIN_IGNORE_ABOVE = 32766;

    private final String index;
    private final long cacheTtlMs;
    private final EsHttpClient http;
    private final ObjectMapper mapper;
    private volatile Check cached;
    private volatile long cachedAt;

    EsMappingValidator(String index, long cacheTtlMs, EsHttpClient http, ObjectMapper mapper) {
        this.index = index;
        this.cacheTtlMs = cacheTtlMs;
        this.http = http;
        this.mapper = mapper;
    }

    Check check() {
        Check snapshot = cached;
        if (snapshot != null && System.currentTimeMillis() - cachedAt < cacheTtlMs) {
            return snapshot;
        }
        synchronized (this) {
            if (cached != null && System.currentTimeMillis() - cachedAt < cacheTtlMs) {
                return cached;
            }
            Check result = load();
            cached = result;
            cachedAt = System.currentTimeMillis();
            return result;
        }
    }

    private Check load() {
        try {
            EsHttpClient.Response versionResponse = http.get("/");
            if (versionResponse.status() >= 400) {
                return fail(CapabilityCode.UPSTREAM_ERROR,
                        "无法读取 Elasticsearch 版本: HTTP " + versionResponse.status());
            }
            String version = mapper.readTree(versionResponse.body())
                    .path("version").path("number").asText("");
            if (!versionAtLeast(version, 7, 10)) {
                return fail(CapabilityCode.VERSION_UNSUPPORTED,
                        "Elasticsearch 版本 " + version + " 低于 7.10，缺少 PIT / case_insensitive");
            }

            EsHttpClient.Response mappingResponse = http.get("/" + index + "/_mapping");
            if (mappingResponse.status() == 404) {
                return fail(CapabilityCode.NOT_CONFIGURED,
                        "日志索引不存在或不可见: " + index);
            }
            if (mappingResponse.status() >= 400) {
                return fail(CapabilityCode.UPSTREAM_ERROR,
                        "读取索引映射失败: HTTP " + mappingResponse.status());
            }
            JsonNode root = mapper.readTree(mappingResponse.body());
            JsonNode properties = firstMappingProperties(root);
            if (properties == null) {
                return fail(CapabilityCode.ES_MAPPING_INCOMPATIBLE,
                        "索引映射为空，无法校验日志字段: " + index);
            }
            return validateFields(properties);
        } catch (BackendException e) {
            return fail(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return fail(CapabilityCode.PARSE_ERROR, "解析 Elasticsearch 映射失败: " + e.getMessage());
        }
    }

    private Check validateFields(JsonNode properties) {
        if (!"date".equals(properties.path("@timestamp").path("type").asText())) {
            return fail(CapabilityCode.ES_MAPPING_INCOMPATIBLE,
                    "缺少 date 类型的 @timestamp 字段");
        }
        for (String field : List.of("stream", "service_type", "component_id", "host_id")) {
            if (!"keyword".equals(properties.path(field).path("type").asText())) {
                return fail(CapabilityCode.ES_MAPPING_INCOMPATIBLE,
                        field + " 必须是 keyword 类型");
            }
        }
        JsonNode message = properties.path("message");
        JsonNode keyword = message.path("fields").path("keyword");
        if (keyword.isMissingNode()) {
            keyword = properties.path(EsQueryBuilder.MESSAGE_KEYWORD);
        }
        if (!"keyword".equals(keyword.path("type").asText())) {
            return fail(CapabilityCode.ES_MAPPING_INCOMPATIBLE,
                    "缺少 message.keyword（keyword 类型），无法做大小写不敏感子串匹配");
        }
        int ignoreAbove = keyword.path("ignore_above").asInt(0);
        if (ignoreAbove < MIN_IGNORE_ABOVE) {
            return fail(CapabilityCode.ES_MAPPING_INCOMPATIBLE,
                    "message.keyword.ignore_above=" + ignoreAbove + "，低于 " + MIN_IGNORE_ABOVE
                            + " 会丢失长行");
        }
        return new Check(CapabilityCode.OK, "映射可用", List.of());
    }

    private static JsonNode firstMappingProperties(JsonNode root) {
        var fields = root.fields();
        if (!fields.hasNext()) {
            return null;
        }
        return fields.next().getValue().path("mappings").path("properties");
    }

    private static boolean versionAtLeast(String version, int major, int minor) {
        String[] parts = version.split("\\.");
        try {
            int actualMajor = Integer.parseInt(parts[0]);
            int actualMinor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return actualMajor > major || (actualMajor == major && actualMinor >= minor);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Check fail(CapabilityCode code, String reason) {
        return new Check(code, reason,
                List.of(new Requirement(RequirementKind.CONFIG, "elasticsearch.index.mappings",
                        "需要 @timestamp(date)、stream/service_type/component_id/host_id(keyword)、"
                                + "message.keyword(ignore_above>=32766)")));
    }

    record Check(CapabilityCode code, String reason, List<Requirement> requirements) {
        boolean ok() {
            return code == CapabilityCode.OK;
        }
    }
}
