package com.bigdata.modules.telemetry.service.metricdata.server;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.BackendWarning;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricQueryResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PromQL 响应解析：matrix / vector 统一转 {@code series[]}。
 *
 * <p>只接受 matrix 与 vector；scalar/string 视为 PARSE_ERROR，不猜测含义。缺失采样不补 0，
 * 部分结果标记 truncated + PARTIAL_RESPONSE。</p>
 */
public final class PromqlResponseParser {

    private PromqlResponseParser() {
    }

    public static MetricQueryResult parse(String body, ObjectMapper mapper, PromqlDialect dialect) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            throw new BackendException(CapabilityCode.PARSE_ERROR, "PromQL 响应不是合法 JSON");
        }
        if ("error".equals(root.path("status").asText())) {
            throw PromqlErrorMapper.fromPayload(root);
        }
        JsonNode data = root.path("data");
        boolean partial = root.path("isPartial").asBoolean(false) || data.path("isPartial").asBoolean(false);
        String resultType = data.path("resultType").asText("");
        JsonNode result = data.path("result");
        if (!result.isArray()) {
            throw new BackendException(CapabilityCode.PARSE_ERROR,
                    "PromQL 响应缺少 data.result，resultType=" + resultType);
        }

        List<MetricQueryResult.Series> series = new ArrayList<>();
        if ("matrix".equals(resultType)) {
            for (JsonNode item : result) {
                series.add(parseMatrix(item));
            }
        } else if ("vector".equals(resultType)) {
            for (JsonNode item : result) {
                series.add(parseVector(item));
            }
        } else {
            throw new BackendException(CapabilityCode.PARSE_ERROR,
                    "不支持的 resultType: " + resultType + "（只接受 matrix/vector）");
        }

        if (series.size() > dialect.maxSeries()) {
            throw new BackendException(CapabilityCode.QUERY_TOO_EXPENSIVE,
                    "返回序列数 " + series.size() + " 超过上限 " + dialect.maxSeries()
                            + "，请收窄 selector 或时间范围");
        }
        for (MetricQueryResult.Series item : series) {
            if (item.samples().size() > dialect.maxPointsPerSeries()) {
                throw new BackendException(CapabilityCode.QUERY_TOO_EXPENSIVE,
                        "单序列点数 " + item.samples().size() + " 超过上限 "
                                + dialect.maxPointsPerSeries() + "，请增大 step 或缩短时间范围");
            }
        }
        List<BackendWarning> warnings = partial
                ? List.of(BackendWarning.of("PARTIAL_RESPONSE", "上游返回部分结果，结果不完整"))
                : List.of();
        return new MetricQueryResult(series, partial, warnings);
    }

    private static MetricQueryResult.Series parseMatrix(JsonNode item) {
        List<MetricQueryResult.Sample> samples = new ArrayList<>();
        for (JsonNode value : item.path("values")) {
            if (!value.isArray() || value.size() < 2) {
                continue;
            }
            samples.add(new MetricQueryResult.Sample(
                    Instant.ofEpochMilli((long) (value.get(0).asDouble() * 1000d)),
                    parseValue(value.get(1).asText())));
        }
        return new MetricQueryResult.Series(labels(item.path("metric")), samples);
    }

    private static MetricQueryResult.Series parseVector(JsonNode item) {
        List<MetricQueryResult.Sample> samples = new ArrayList<>();
        JsonNode value = item.path("value");
        if (value.isArray() && value.size() >= 2) {
            samples.add(new MetricQueryResult.Sample(
                    Instant.ofEpochMilli((long) (value.get(0).asDouble() * 1000d)),
                    parseValue(value.get(1).asText())));
        }
        return new MetricQueryResult.Series(labels(item.path("metric")), samples);
    }

    private static Map<String, String> labels(JsonNode metric) {
        Map<String, String> labels = new LinkedHashMap<>();
        metric.fields().forEachRemaining(entry -> labels.put(entry.getKey(), entry.getValue().asText()));
        return labels;
    }

    static double parseValue(String raw) {
        return switch (raw) {
            case "NaN" -> Double.NaN;
            case "+Inf", "Inf" -> Double.POSITIVE_INFINITY;
            case "-Inf" -> Double.NEGATIVE_INFINITY;
            default -> Double.parseDouble(raw);
        };
    }
}
