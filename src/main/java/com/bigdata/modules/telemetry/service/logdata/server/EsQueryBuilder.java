package com.bigdata.modules.telemetry.service.logdata.server;

import com.bigdata.modules.telemetry.service.logdata.model.LogFilter;
import com.bigdata.modules.telemetry.service.logdata.model.LogQuerySpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 统一日志语义 → Elasticsearch DSL。
 *
 * <p>字面量关键词用 {@code wildcard} on {@code message.keyword}，不能用 {@code match_phrase}：
 * 后者按分词匹配，与本地子串语义不一致。</p>
 */
public final class EsQueryBuilder {

    static final String MESSAGE_KEYWORD = "message.keyword";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ISO_INSTANT;

    private EsQueryBuilder() {
    }

    public static ObjectNode build(LogQuerySpec spec, String pitId, List<JsonNode> searchAfter,
                                   int size, ObjectMapper mapper) {
        ObjectNode body = mapper.createObjectNode();
        body.put("size", size);
        body.put("track_total_hits", false);
        ArrayNode source = body.putArray("_source");
        source.add("@timestamp");
        source.add("stream");
        source.add("host_id");
        source.add("service_type");
        source.add("component_id");
        source.add("message");

        ObjectNode bool = body.putObject("query").putObject("bool");
        ArrayNode filter = bool.putArray("filter");
        term(filter, "service_type", spec.target().serviceType());
        term(filter, "component_id", spec.target().componentId());
        if (!spec.target().clusterScoped()) {
            term(filter, "host_id", spec.target().hostId());
        }
        term(filter, "stream", spec.stream());
        if (spec.timeRange() != null
                && (spec.timeRange().start() != null || spec.timeRange().end() != null)) {
            ObjectNode range = filter.addObject().putObject("range").putObject("@timestamp");
            range.put("format", "strict_date_optional_time||epoch_millis");
            if (spec.timeRange().start() != null) {
                range.put("gte", TIME_FORMAT.format(spec.timeRange().start()));
            }
            if (spec.timeRange().end() != null) {
                range.put("lte", TIME_FORMAT.format(spec.timeRange().end()));
            }
        }

        LogFilter filterSpec = spec.filter();
        ArrayNode includes = bool.putArray(
                filterSpec.matchMode() == LogFilter.MatchMode.ANY ? "should" : "must");
        for (String keyword : filterSpec.keywords()) {
            if (filterSpec.regex()) {
                includes.addObject().putObject("regexp").putObject(MESSAGE_KEYWORD)
                        .put("value", keyword)
                        .put("case_insensitive", !filterSpec.caseSensitive());
            } else {
                includes.addObject().putObject("wildcard").putObject(MESSAGE_KEYWORD)
                        .put("value", "*" + escapeWildcard(keyword) + "*")
                        .put("case_insensitive", !filterSpec.caseSensitive());
            }
        }
        if (filterSpec.matchMode() == LogFilter.MatchMode.ANY && !filterSpec.keywords().isEmpty()) {
            bool.put("minimum_should_match", 1);
        }
        if (!filterSpec.excludeKeywords().isEmpty()) {
            ArrayNode mustNot = bool.putArray("must_not");
            for (String keyword : filterSpec.excludeKeywords()) {
                if (filterSpec.regex()) {
                    mustNot.addObject().putObject("regexp").putObject(MESSAGE_KEYWORD)
                            .put("value", keyword)
                            .put("case_insensitive", !filterSpec.caseSensitive());
                } else {
                    mustNot.addObject().putObject("wildcard").putObject(MESSAGE_KEYWORD)
                            .put("value", "*" + escapeWildcard(keyword) + "*")
                            .put("case_insensitive", !filterSpec.caseSensitive());
                }
            }
        }

        ArrayNode sort = body.putArray("sort");
        sort.addObject().putObject("@timestamp").put("order", "asc");
        sort.addObject().putObject("_shard_doc").put("order", "asc");
        if (searchAfter != null && !searchAfter.isEmpty()) {
            ArrayNode after = body.putArray("search_after");
            searchAfter.forEach(after::add);
        }
        if (pitId != null) {
            body.putObject("pit").put("id", pitId).put("keep_alive", "2m");
        }
        return body;
    }

    private static void term(ArrayNode filter, String field, String value) {
        filter.addObject().putObject("term").put(field, value);
    }

    /** 用户输入里的 {@code *} / {@code ?} 必须当字面量，不能当通配符。 */
    static String escapeWildcard(String value) {
        return value.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?");
    }
}
