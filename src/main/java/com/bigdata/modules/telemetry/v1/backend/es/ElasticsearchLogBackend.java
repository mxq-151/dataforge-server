package com.bigdata.modules.telemetry.v1.backend.es;

import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.Requirement;
import com.bigdata.modules.telemetry.model.RequirementKind;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties.EsConfig;
import com.bigdata.modules.telemetry.v1.model.BackendType;
import com.bigdata.modules.telemetry.v1.model.BackendWarning;
import com.bigdata.modules.telemetry.v1.model.LogDataType;
import com.bigdata.modules.telemetry.v1.model.LogEntry;
import com.bigdata.modules.telemetry.v1.spi.BackendCapability;
import com.bigdata.modules.telemetry.v1.spi.BackendException;
import com.bigdata.modules.telemetry.v1.spi.LogBackend;
import com.bigdata.modules.telemetry.v1.spi.LogQueryResult;
import com.bigdata.modules.telemetry.v1.spi.LogQuerySpec;
import com.bigdata.modules.telemetry.v1.support.CursorCodec;
import com.bigdata.modules.telemetry.v1.support.LogFilterEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch 日志后端：PIT + search_after 分页，索引映射在能力判定阶段校验。
 */
public final class ElasticsearchLogBackend implements LogBackend {

    private static final int MAX_LIMIT = 2000;

    private final String backendId;
    private final EsConfig config;
    private final ObjectMapper objectMapper;
    private final EsHttpClient http;
    private final EsMappingValidator validator;

    public ElasticsearchLogBackend(String backendId, EsConfig config, String authorizationHeader,
                                   ObjectMapper objectMapper) {
        this.backendId = backendId;
        this.config = config;
        this.objectMapper = objectMapper;
        Duration timeout = Duration.ofMillis(Math.max(config.getLimits().getTimeoutMs(), 1000));
        this.http = new EsHttpClient(config.getUrls().isEmpty() ? "" : config.getUrls().get(0),
                authorizationHeader, timeout, config.getLimits().getMaxResponseBytes(), objectMapper);
        this.validator = new EsMappingValidator(config.getIndex(),
                config.getLimits().getMappingCacheTtlMs(), http, objectMapper);
    }

    @Override
    public String backendId() {
        return backendId;
    }

    @Override
    public BackendType backendType() {
        return BackendType.ELASTICSEARCH;
    }

    @Override
    public BackendCapability evaluate(LogQuerySpec spec) {
        if (spec.dataType() != LogDataType.SERVICE) {
            return BackendCapability.unavailable(CapabilityCode.LOG_DATA_TYPE_MISMATCH,
                    "elasticsearch 只承载 log.service，任务日志请用 yarn", List.of());
        }
        if (config.getUrls() == null || config.getUrls().isEmpty()) {
            return BackendCapability.unavailable(CapabilityCode.NOT_CONFIGURED,
                    "未配置 Elasticsearch 地址",
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "telemetry.backends.elasticsearch[].urls", "配置 ES 地址")));
        }
        EsMappingValidator.Check check = validator.check();
        if (!check.ok()) {
            return BackendCapability.unavailable(check.code(), check.reason(), check.requirements());
        }
        return BackendCapability.available();
    }

    @Override
    public LogQueryResult fetch(LogQuerySpec spec) {
        try {
            LogFilterEngine.compile(spec.filter());
        } catch (IllegalArgumentException e) {
            throw new BackendException(CapabilityCode.INVALID_ARGUMENT, e.getMessage());
        }
        String filterHash = CursorCodec.hash(objectMapper, spec.filter().keywords(),
                spec.filter().excludeKeywords(), spec.filter().matchMode(),
                spec.filter().regex(), spec.filter().caseSensitive());
        String rangeHash = CursorCodec.hash(objectMapper,
                spec.timeRange() == null ? null : spec.timeRange().start(),
                spec.timeRange() == null ? null : spec.timeRange().end());

        EsCursor cursor = spec.cursor() == null ? null
                : CursorCodec.decode(spec.cursor(), EsCursor.class, objectMapper);
        String pitId;
        List<JsonNode> searchAfter;
        long expiresAt;
        if (cursor != null) {
            if (!backendId.equals(cursor.backendId()) || !filterHash.equals(cursor.filterHash())
                    || !rangeHash.equals(cursor.rangeHash()) || !spec.stream().equals(cursor.stream())) {
                throw new BackendException(CapabilityCode.CURSOR_MISMATCH,
                        "过滤条件、时间范围或后端已变化，请从第一页重新查询");
            }
            if (cursor.expiresAt() < System.currentTimeMillis()) {
                throw new BackendException(CapabilityCode.CURSOR_EXPIRED,
                        "游标已过期（PIT 失效），请从第一页重新查询");
            }
            pitId = cursor.pitId();
            searchAfter = cursor.sort();
            expiresAt = cursor.expiresAt();
        } else {
            pitId = openPit();
            searchAfter = null;
            expiresAt = System.currentTimeMillis()
                    + Math.max(config.getLimits().getPitKeepAliveSeconds(), 1) * 1000L;
        }

        int limit = Math.max(1, Math.min(spec.limit() <= 0 ? 500 : spec.limit(), MAX_LIMIT));
        long maxBytes = spec.maxBytes() > 0 ? spec.maxBytes() : config.getLimits().getMaxResponseBytes();
        ObjectNode body = EsQueryBuilder.build(spec, pitId, searchAfter, limit + 1, objectMapper);
        EsHttpClient.Response response = http.post("/_search", body.toString());
        if (response.status() >= 400) {
            throw error(response);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new BackendException(CapabilityCode.PARSE_ERROR, "Elasticsearch 响应不是合法 JSON");
        }

        JsonNode hits = root.path("hits").path("hits");
        List<BackendWarning> warnings = new ArrayList<>();
        boolean truncated = hits.size() > limit;
        int take = Math.min(hits.size(), limit);
        List<LogEntry> entries = new ArrayList<>();
        long bytes = 0;
        int included = 0;
        boolean byteStopped = false;
        for (int i = 0; i < take; i++) {
            JsonNode source = hits.get(i).path("_source");
            String message = source.path("message").asText("");
            long size = message.getBytes(StandardCharsets.UTF_8).length + 1L;
            if (bytes + size > maxBytes) {
                byteStopped = true;
                truncated = true;
                break;
            }
            entries.add(new LogEntry(parseInstant(source.path("@timestamp").asText(null)),
                    source.path("stream").asText(spec.stream()),
                    source.path("host_id").asText(spec.target().hostId()),
                    message, Map.of()));
            bytes += size;
            included++;
        }
        if (byteStopped && included == 0) {
            warnings.add(BackendWarning.of("PAGE_BYTES_EXCEEDED",
                    "单条日志超过 maxBytes，无法返回该页，请提高 maxBytes"));
        }
        int failedShards = root.path("_shards").path("failed").asInt(0);
        if (failedShards > 0) {
            truncated = true;
            warnings.add(BackendWarning.of("PARTIAL_SHARDS",
                    "有 " + failedShards + " 个分片失败，结果不完整"));
        }

        String nextCursor = null;
        if (truncated && included > 0) {
            JsonNode last = hits.get(included - 1);
            List<JsonNode> sortValues = new ArrayList<>();
            JsonNode sortNode = last.path("sort");
            if (sortNode.isArray()) {
                sortNode.forEach(sortValues::add);
            }
            nextCursor = CursorCodec.encode(new EsCursor(backendId, pitId, sortValues,
                    filterHash, rangeHash, spec.stream(), expiresAt), objectMapper);
        }
        return new LogQueryResult(entries, nextCursor, truncated,
                new LogQueryResult.ScanStats(1, bytes, included, entries.size()), warnings);
    }

    private String openPit() {
        EsHttpClient.Response response = http.post("/" + config.getIndex() + "/_pit?keep_alive=2m", "");
        if (response.status() == 404) {
            throw new BackendException(CapabilityCode.NOT_CONFIGURED,
                    "日志索引不存在或不可见: " + config.getIndex(),
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "telemetry.backends.elasticsearch[].index", "确认索引名与读权限")));
        }
        if (response.status() >= 400) {
            throw error(response);
        }
        try {
            String pitId = objectMapper.readTree(response.body()).path("id").asText("");
            if (pitId.isBlank()) {
                throw new BackendException(CapabilityCode.PARSE_ERROR, "PIT 响应缺少 id");
            }
            return pitId;
        } catch (BackendException e) {
            throw e;
        } catch (Exception e) {
            throw new BackendException(CapabilityCode.PARSE_ERROR, "PIT 响应不是合法 JSON");
        }
    }

    private BackendException error(EsHttpClient.Response response) {
        String errorType = "";
        try {
            errorType = objectMapper.readTree(response.body()).path("error").path("type").asText("");
        } catch (Exception ignored) {
            // 错误体不是 JSON 时按状态码处理
        }
        CapabilityCode code = switch (response.status()) {
            case 400 -> CapabilityCode.INVALID_ARGUMENT;
            case 401, 403 -> CapabilityCode.UPSTREAM_UNAUTHORIZED;
            case 404 -> "index_not_found_exception".equals(errorType)
                    || "search_context_missing_exception".equals(errorType)
                    ? CapabilityCode.CURSOR_EXPIRED : CapabilityCode.NOT_CONFIGURED;
            case 429 -> CapabilityCode.RATE_LIMITED;
            default -> response.status() >= 500
                    ? CapabilityCode.UPSTREAM_ERROR : CapabilityCode.PARSE_ERROR;
        };
        String message = errorType.isBlank()
                ? "Elasticsearch 请求失败，HTTP " + response.status()
                : errorType + "（HTTP " + response.status() + "）";
        return new BackendException(code, message);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** PIT + search_after 游标。 */
    record EsCursor(String backendId, String pitId, List<JsonNode> sort, String filterHash,
                    String rangeHash, String stream, long expiresAt) {
    }
}
