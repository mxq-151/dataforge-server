package com.bigdata.modules.telemetry.common.http;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import com.bigdata.modules.telemetry.util.Urls;

/**
 * Spark History Server 应用的 attempt 路径片段与完成状态。
 *
 * <p>带 attempt 的应用（如 YARN 的 {@code application_*}）在 SHS 中必须把 attemptId 拼进
 * URL，否则 {@code /stages}、{@code /allexecutors}、{@code /environment} 等端点会返回
 * "no such app"；本地应用的 attempts 中没有 attemptId，保持原路径不变。</p>
 *
 * <p>同时带回 {@code completed}：SHS 也会读未完成的事件日志（运行中应用），此时返回的
 * stage/executor 数字是滞后的近似快照，调用方需要据此给出告警而不是当成终值。</p>
 */
public final class SparkShsAttempts {

    private SparkShsAttempts() {
    }

    /** 应用详情中的 attempt 信息。{@code segment} 直接拼在应用路径之后。 */
    public record AttemptRef(String segment, boolean completed, Instant lastUpdated,
                             String sparkVersion) {

        public static AttemptRef empty() {
            return new AttemptRef("", true, null, "");
        }
    }

    /** 返回 {@code /<latestAttemptId>}；应用没有 attemptId 时返回空串。 */
    public static String latestSegment(EngineRestClient client, String applicationId) {
        return load(client, applicationId).segment();
    }

    /** 读应用详情，返回路径片段 + 是否已结束 + 最后更新时间 + Spark 版本。 */
    public static AttemptRef load(EngineRestClient client, String applicationId) {
        JsonNode root = client.getJson("/api/v1/applications/" + Urls.encode(applicationId));
        JsonNode attempts = root.path("attempts");
        String latest = "";
        int latestId = Integer.MIN_VALUE;
        boolean completed = true;
        Instant lastUpdated = null;
        String sparkVersion = "";
        if (attempts.isArray()) {
            for (JsonNode attempt : attempts) {
                String id = attempt.path("attemptId").asText("");
                int numeric;
                try {
                    numeric = id.isBlank() ? 0 : Integer.parseInt(id);
                } catch (NumberFormatException e) {
                    numeric = 0;
                }
                if (numeric >= latestId) {
                    latestId = numeric;
                    latest = id;
                    completed = attempt.path("completed").asBoolean(true);
                    lastUpdated = parseInstant(attempt.path("lastUpdated").asText(""));
                    sparkVersion = attempt.path("appSparkVersion").asText("");
                }
            }
        }
        return new AttemptRef(latest.isBlank() ? "" : "/" + Urls.encode(latest), completed,
                lastUpdated, sparkVersion == null ? "" : sparkVersion.trim());
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

}
