package com.bigdata.modules.telemetry.service.logdata.task;

import com.bigdata.modules.telemetry.common.http.EngineRestClient;
import com.bigdata.modules.telemetry.common.model.BackendType;
import com.bigdata.modules.telemetry.common.model.BackendWarning;
import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.Requirement;
import com.bigdata.modules.telemetry.common.model.RequirementKind;
import com.bigdata.modules.telemetry.common.model.BackendCapability;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.bigdata.modules.telemetry.config.properties.FlinkRestConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.bigdata.modules.telemetry.util.Urls;
import com.bigdata.modules.telemetry.util.JsonNodes;
import com.bigdata.modules.telemetry.service.logdata.model.ExceptionQueryResult;
import com.bigdata.modules.telemetry.service.logdata.model.ExceptionQuerySpec;
import com.bigdata.modules.telemetry.service.logdata.spi.ExceptionSummaryBackend;

/**
 * Flink REST 异常摘要后端：只查询 {@code GET /jobs/{jobId}/exceptions}。
 *
 * <p>与指标后端拆开的原因：异常摘要是 engineEvidence，不属于指标，两者只有 REST 客户端
 * 与配置相同，生命周期与故障域互不影响。</p>
 *
 * <p>Flink 2.2 不存在 {@code /jobs/{jobId}/exceptions/{exception}} 子资源：实测任意路径参数
 * 都返回 404，源码里的 {@code JobExceptionsHandler} 也只有一个 {@code @Get}。因此这里不保留
 * 单条详情的调用路径，避免按旧版文档加回一个必然 404 的请求。</p>
 */
public final class FlinkRestExceptionBackend implements ExceptionSummaryBackend {

    private final String backendId;
    private final FlinkRestConfig config;
    private final EngineRestClient client;

    public FlinkRestExceptionBackend(String backendId, FlinkRestConfig config,
                                     String authorizationHeader, ObjectMapper objectMapper) {
        this.backendId = backendId;
        this.config = config;
        this.client = new EngineRestClient(config.getBaseUrl(), authorizationHeader,
                Duration.ofMillis(Math.max(config.getLimits().getTimeoutMs(), 1000)),
                config.getLimits().getMaxResponseBytes(), objectMapper);
    }

    @Override
    public String backendId() {
        return backendId;
    }

    public BackendType backendType() {
        return BackendType.FLINK_REST;
    }

    @Override
    public BackendCapability evaluate(ExceptionQuerySpec spec) {
        if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            return BackendCapability.unavailable(CapabilityCode.NOT_CONFIGURED,
                    "未配置 Flink REST 地址",
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "telemetry.backends.flink-rest[].base-url",
                            "配置 JobManager REST 地址")));
        }
        if (spec.jobId() == null || spec.jobId().isBlank()) {
            return BackendCapability.unavailable(CapabilityCode.INVALID_ARGUMENT,
                    "缺少 ref.jobId", List.of());
        }
        return BackendCapability.available();
    }

    /**
     * 服务端通过 {@code maxExceptions} 自己限制历史条数，并以 {@code exceptionHistory.truncated}
     * 告知是否还有更多；客户端还会对并发异常条数与单条 stacktrace 长度做上限保护，并显式标记。
     */
    @Override
    public ExceptionQueryResult fetch(ExceptionQuerySpec spec) {
        int maxExceptions = clamp(spec.maxExceptions(), config.getMaxExceptions());
        int maxConcurrent = clamp(spec.maxConcurrentExceptions(), config.getMaxConcurrentExceptions());
        boolean includeStackTraces = spec.includeStackTraces() == null
                || spec.includeStackTraces();
        JsonNode root = client.getJson(exceptionsPath(spec.jobId())
                + "?maxExceptions=" + maxExceptions);
        return parseHistory(root, maxExceptions, maxConcurrent, includeStackTraces);
    }

    private ExceptionQueryResult parseHistory(JsonNode root, int maxExceptions, int maxConcurrent,
                                              boolean includeStackTraces) {
        JsonNode history = root.path("exceptionHistory");
        if (!history.isObject()) {
            throw new BackendException(CapabilityCode.PARSE_ERROR,
                    "Flink 异常响应缺少 exceptionHistory（不识别旧版扁平结构）");
        }
        boolean serverTruncated = history.path("truncated").asBoolean(false);
        List<BackendWarning> warnings = new ArrayList<>();
        JsonNode entries = history.path("entries");
        int available = entries.isArray() ? entries.size() : 0;
        List<ExceptionQueryResult.ExceptionRecord> records = new ArrayList<>();
        int concurrentTotal = 0;
        boolean concurrentTruncated = false;
        boolean stackTraceTruncated = false;
        for (JsonNode node : entries) {
            if (records.size() >= maxExceptions) {
                break;
            }
            int concurrentAvailable = node.path("concurrentExceptions").size();
            concurrentTotal += concurrentAvailable;
            if (concurrentAvailable > maxConcurrent) {
                concurrentTruncated = true;
            }
            ExceptionQueryResult.ExceptionRecord record =
                    parseEntry(node, serverTruncated, maxConcurrent, includeStackTraces);
            stackTraceTruncated = stackTraceTruncated || record.stackTraceTruncated()
                    || record.concurrentExceptions().stream()
                    .anyMatch(item -> item.stackTraceTruncated());
            records.add(record);
        }
        boolean limitTruncated = available > records.size() || concurrentTruncated
                || stackTraceTruncated;
        if (serverTruncated) {
            warnings.add(BackendWarning.of("EXCEPTION_HISTORY_TRUNCATED",
                    "Flink 服务端异常历史已截断（exceptionHistory.truncated=true），未返回全部历史"));
        }
        if (available > records.size()) {
            warnings.add(BackendWarning.of("RESULT_TRUNCATED",
                    "异常条数超过上限 " + maxExceptions + "，仅返回前 " + records.size() + " 条"));
        }
        if (concurrentTruncated) {
            warnings.add(BackendWarning.of("RESULT_TRUNCATED",
                    "并发异常条数超过上限 " + maxConcurrent + "，仅保留前 " + maxConcurrent + " 条"));
        }
        if (stackTraceTruncated) {
            warnings.add(BackendWarning.of("STACKTRACE_TRUNCATED",
                    "单条 stacktrace 超过 " + config.getMaxStackTraceChars()
                            + " 字符上限，已客户端截断"));
        }
        return new ExceptionQueryResult(records, concurrentTotal, serverTruncated,
                limitTruncated, maxExceptions, warnings);
    }

    private ExceptionQueryResult.ExceptionRecord parseEntry(JsonNode node, boolean serverTruncated,
                                                            int maxConcurrent,
                                                            boolean includeStackTraces) {
        String exceptionName = JsonNodes.text(node, "exceptionName");
        Stack stack = stackTrace(node, includeStackTraces);
        List<ExceptionQueryResult.ConcurrentExceptionRecord> concurrent = new ArrayList<>();
        for (JsonNode child : node.path("concurrentExceptions")) {
            if (concurrent.size() >= maxConcurrent) {
                break;
            }
            concurrent.add(parseConcurrent(child, includeStackTraces));
        }
        return new ExceptionQueryResult.ExceptionRecord(exceptionName,
                rootCause(stack.value(), exceptionName), stack.value(),
                instant(node.path("timestamp").asLong(0L)),
                JsonNodes.text(node, "taskName"), JsonNodes.text(node, "taskManagerId"),
                JsonNodes.text(node, "endpoint"), failureLabels(node),
                serverTruncated, stack.truncated(), stack.chars(), concurrent);
    }

    private ExceptionQueryResult.ConcurrentExceptionRecord parseConcurrent(
            JsonNode node, boolean includeStackTraces) {
        String exceptionName = JsonNodes.text(node, "exceptionName");
        Stack stack = stackTrace(node, includeStackTraces);
        return new ExceptionQueryResult.ConcurrentExceptionRecord(exceptionName,
                rootCause(stack.value(), exceptionName), stack.value(),
                instant(node.path("timestamp").asLong(0L)),
                JsonNodes.text(node, "taskName"), JsonNodes.text(node, "taskManagerId"),
                JsonNodes.text(node, "endpoint"), failureLabels(node),
                stack.truncated(), stack.chars());
    }

    private Stack stackTrace(JsonNode node, boolean include) {
        if (!include) {
            return new Stack(null, false, 0);
        }
        String value = JsonNodes.text(node, "stacktrace");
        if (value == null) {
            return new Stack(null, false, 0);
        }
        int chars = value.length();
        int cap = Math.max(config.getMaxStackTraceChars(), 1);
        if (chars > cap) {
            return new Stack(value.substring(0, cap), true, chars);
        }
        return new Stack(value, false, chars);
    }

    /** 取最后一个 {@code Caused by:} 行作为根因；没有 cause 时回退异常类名。 */
    private static String rootCause(String stackTrace, String exceptionName) {
        if (stackTrace == null) {
            return exceptionName;
        }
        String root = null;
        for (String line : stackTrace.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Caused by:")) {
                String cause = trimmed.substring("Caused by:".length()).trim();
                if (!cause.isEmpty()) {
                    root = cause;
                }
            }
        }
        return root == null ? exceptionName : root;
    }

    private static Instant instant(long epochMillis) {
        return epochMillis <= 0 ? null : Instant.ofEpochMilli(epochMillis);
    }


    private static Map<String, String> failureLabels(JsonNode node) {
        Map<String, String> labels = new LinkedHashMap<>();
        JsonNode value = node.path("failureLabels");
        if (value.isObject()) {
            value.fields().forEachRemaining(entry ->
                    labels.put(entry.getKey(), entry.getValue().asText()));
        }
        return labels;
    }

    private static int clamp(Integer requested, int configured) {
        int cap = Math.max(configured, 1);
        return requested == null ? cap : Math.max(1, Math.min(requested, cap));
    }

    private static String exceptionsPath(String jobId) {
        return "/jobs/" + Urls.encode(jobId) + "/exceptions";
    }


    private record Stack(String value, boolean truncated, int chars) {
    }
}
