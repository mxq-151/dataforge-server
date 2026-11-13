package com.bigdata.modules.logs.adapter;

import com.bigdata.modules.logs.model.ComponentRole;
import com.bigdata.modules.logs.model.LogKind;
import com.bigdata.modules.logs.model.LogSource;
import com.bigdata.modules.logs.model.ServiceType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Flink REST 适配器：作业异常与运行摘要（<b>结构化层</b>）。
 *
 * <h3>明确边界</h3>
 * <p>Flink REST 返回的是作业/异常<b>摘要</b>，不是原始日志。本适配器输出的是整理后的诊断证据，
 * 首行会显式标注「非原始日志」。原始 JobManager / TaskManager 日志请使用
 * {@code logKind=runtime|stdout} 走文件适配器。</p>
 *
 * <h3>取数策略（控制对 JM 的压力）</h3>
 * <ul>
 *   <li>{@code GET /jobs/{jobId}}：状态、时间、失败任务数；</li>
 *   <li>{@code GET /jobs/{jobId}/exceptions}：根异常 + 全部异常历史（含 task/vertex 定位与堆栈），
 *       一次返回，<b>不逐 task 拉取</b>；</li>
 *   <li>{@code GET /jobs/{jobId}/checkpoints}：checkpoint 成功/失败计数与最近一次失败；</li>
 *   <li>{@code GET /jobs/{jobId}/metrics?get=numRestarts}：重启次数（可选，失败不阻断）。</li>
 * </ul>
 * <p>单次请求超时取 {@code min(查询预算, logs.sources[].options.rest-timeout-ms)}，
 * 全部失败由上层统一转降级，不抛 500。</p>
 */
@Component
public class FlinkRestLogAdapter implements LogAdapter {

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final String SUMMARY_NOTE =
            "Flink REST 返回作业异常/运行摘要（结构化证据），不是原始日志；原始日志请使用 logKind=runtime/stdout";

    private final HttpLogClient httpLogClient;
    private final ObjectMapper objectMapper;

    public FlinkRestLogAdapter(HttpLogClient httpLogClient, ObjectMapper objectMapper) {
        this.httpLogClient = httpLogClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(ServiceType serviceType, LogKind logKind, ComponentRole role) {
        if (serviceType != ServiceType.FLINK || logKind != LogKind.EXCEPTION) {
            return false;
        }
        return role == ComponentRole.JOB_MANAGER || role == ComponentRole.API
                || role == ComponentRole.GENERIC;
    }

    @Override
    public AdapterResult query(LogQueryContext context) {
        String jobId = context.ref("jobId", "job_id", "job");
        if (jobId == null) {
            throw new LogAdapterException(LogAdapterException.Code.BAD_REQUEST, "缺少 ref.jobId");
        }
        LogSource source = context.getSource();
        String baseUrl = source.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new LogAdapterException(LogAdapterException.Code.UNAVAILABLE,
                    "未配置 Flink JobManager REST 地址（base-url）");
        }

        long timeoutMs = Math.max(1000L, Math.min(context.getTimeoutMs(),
                source.getConfig().getOptions().getRestTimeoutMs()));
        String token = source.getToken();

        JsonNode job = requireJson(baseUrl, "/jobs/" + encode(jobId), token, timeoutMs,
                "Flink 作业不存在或已归档: " + jobId);
        JsonNode exceptions = optionalJson(baseUrl, "/jobs/" + encode(jobId) + "/exceptions", token, timeoutMs);
        JsonNode checkpoints = optionalJson(baseUrl, "/jobs/" + encode(jobId) + "/checkpoints", token, timeoutMs);
        JsonNode metrics = optionalJson(baseUrl,
                "/jobs/" + encode(jobId) + "/metrics?get=numRestarts", token, timeoutMs);

        List<String> lines = render(job, exceptions, checkpoints, metrics,
                context.getStartTime(), context.getEndTime());

        int total = lines.size();
        int from = (int) Math.min(Math.max(context.getOffset(), 0L), total);
        List<String> page = lines.subList(from, total);
        BudgetLimiter.Result budget = BudgetLimiter.limit(page, context.getMaxLines(), context.getMaxBytes());

        long nextOffset = from + budget.getLines().size();
        boolean hasMore = !budget.isLastLineTruncated() && nextOffset < total;
        AdapterResult result = AdapterResult.of(budget.getContent(), budget.getLines().size(),
                nextOffset, hasMore);
        result.setTruncated(budget.isTruncated());
        result.setResolvedHost(hostOf(baseUrl));
        result.setMessage(SUMMARY_NOTE);
        if (exceptions == null) {
            result.setMessage(SUMMARY_NOTE + "；异常接口不可用，仅返回作业/checkpoint 摘要");
        }
        return result;
    }

    private List<String> render(JsonNode job, JsonNode exceptions, JsonNode checkpoints,
                                JsonNode metrics, LocalDateTime start, LocalDateTime end) {
        List<String> lines = new ArrayList<>();
        lines.add("# Flink REST 结构化摘要（非原始日志）");

        String jobName = text(job, "name");
        String jobIdText = text(job, "jid");
        if ("-".equals(jobIdText)) {
            jobIdText = text(job, "id");
        }
        String state = text(job, "state");
        long startTime = job.path("start-time").asLong(-1);
        long endTime = job.path("end-time").asLong(-1);
        long duration = job.path("duration").asLong(-1);
        int failedTasks = job.path("tasks").path("failed").asInt(0);
        lines.add("JOB id=" + jobIdText + " name=" + jobName + " state=" + state
                + " start=" + formatEpoch(startTime) + " end=" + formatEpoch(endTime)
                + " duration=" + (duration >= 0 ? duration + "ms" : "-")
                + " failedTasks=" + failedTasks);

        String restarts = "-";
        if (metrics != null && metrics.isArray() && !metrics.isEmpty()) {
            restarts = metrics.get(0).path("value").asText("-");
        }
        lines.add("RESTARTS numRestarts=" + restarts);

        if (checkpoints != null) {
            JsonNode counts = checkpoints.path("counts");
            lines.add("CHECKPOINT completed=" + counts.path("completed").asInt(0)
                    + " failed=" + counts.path("failed").asInt(0)
                    + " inProgress=" + counts.path("in_progress").asInt(0)
                    + " restored=" + counts.path("restored").asInt(0));
            JsonNode latestFailed = checkpoints.path("latest").path("failed");
            if (!latestFailed.isMissingNode() && !latestFailed.isNull()) {
                lines.add("CHECKPOINT latestFailed id=" + text(latestFailed, "id")
                        + " time=" + formatEpoch(latestFailed.path("trigger_timestamp").asLong(-1))
                        + " 说明=checkpoint 失败会触发作业重启，需结合异常栈定位");
            }
        }

        String rootException = optionalText(exceptions, "root-exception");
        if (rootException != null && !rootException.isBlank()) {
            lines.add("ROOT_EXCEPTION");
            appendStack(lines, rootException);
        }

        List<JsonNode> exceptionList = new ArrayList<>();
        if (exceptions != null && exceptions.path("all-exceptions").isArray()) {
            exceptions.path("all-exceptions").forEach(exceptionList::add);
        }
        exceptionList.removeIf(node -> !inRange(node.path("timestamp").asLong(0), start, end));
        exceptionList.sort(Comparator.comparingLong(node -> node.path("timestamp").asLong(0)));

        if (exceptionList.isEmpty() && (rootException == null || rootException.isBlank())) {
            lines.add("EXCEPTIONS 无异常记录");
        }
        for (JsonNode exception : exceptionList) {
            long timestamp = exception.path("timestamp").asLong(0);
            lines.add("EXCEPTION time=" + formatEpoch(timestamp)
                    + " task=" + text(exception, "taskName")
                    + " taskId=" + text(exception, "taskId")
                    + " name=" + text(exception, "exceptionName"));
            String stack = text(exception, "stacktrace");
            if (stack != null && !stack.isBlank()) {
                appendStack(lines, stack);
            }
        }
        return lines;
    }

    private void appendStack(List<String> lines, String stack) {
        for (String line : stack.split("\n", -1)) {
            if (line == null || line.isBlank()) {
                continue;
            }
            lines.add("  " + line.trim());
        }
    }

    private boolean inRange(long timestamp, LocalDateTime start, LocalDateTime end) {
        if (timestamp <= 0 || (start == null && end == null)) {
            return true;
        }
        LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
        if (start != null && time.isBefore(start)) {
            return false;
        }
        return end == null || !time.isAfter(end);
    }

    private JsonNode requireJson(String baseUrl, String path, String token, long timeoutMs,
                                 String notFoundMessage) {
        Response response = get(baseUrl, path, token, timeoutMs);
        if (response.status == 404) {
            throw new LogAdapterException(LogAdapterException.Code.NOT_FOUND, notFoundMessage);
        }
        if (response.status == 401 || response.status == 403) {
            throw new LogAdapterException(LogAdapterException.Code.UNAVAILABLE,
                    "Flink JobManager 拒绝访问（HTTP " + response.status + "），请检查认证配置");
        }
        if (!response.isSuccess()) {
            throw new LogAdapterException(LogAdapterException.Code.UNAVAILABLE,
                    "Flink JobManager 返回 HTTP " + response.status);
        }
        return response.node;
    }

    private JsonNode optionalJson(String baseUrl, String path, String token, long timeoutMs) {
        try {
            Response response = get(baseUrl, path, token, timeoutMs);
            return response.isSuccess() ? response.node : null;
        } catch (LogAdapterException e) {
            return null;
        }
    }

    private Response get(String baseUrl, String path, String token, long timeoutMs) {
        String url = stripTrailingSlash(baseUrl) + path;
        Map<String, String> headers = new HashMap<>();
        if (token != null && !token.isBlank()) {
            headers.put("Authorization", "Bearer " + token);
        }
        HttpTextResponse response;
        try {
            response = httpLogClient.get(url, headers, Duration.ofMillis(timeoutMs));
        } catch (Exception e) {
            throw new LogAdapterException(LogAdapterException.Code.UNAVAILABLE,
                    "Flink JobManager 不可达: " + e.getMessage(), e);
        }
        JsonNode node = null;
        if (response.isSuccess()) {
            try {
                node = objectMapper.readTree(response.getBody());
            } catch (Exception e) {
                throw new LogAdapterException(LogAdapterException.Code.FAILED,
                        "Flink REST 返回内容不是合法 JSON: " + e.getMessage(), e);
            }
        }
        return new Response(response.getStatus(), node);
    }

    /** 缺失/空值返回 {@code null}（而不是占位符 {@code -}），便于区分「无异常」与「有值」 */
    private String optionalText(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private String text(JsonNode node, String field) {
        if (node == null) {
            return "-";
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return "-";
        }
        String text = value.asText();
        return text == null || text.isBlank() ? "-" : text;
    }

    private String formatEpoch(long timestamp) {
        if (timestamp <= 0) {
            return "-";
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
                .format(DISPLAY);
    }

    private String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private String stripTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static final class Response {
        private final int status;
        private final JsonNode node;

        private Response(int status, JsonNode node) {
            this.status = status;
            this.node = node;
        }

        private boolean isSuccess() {
            return status >= 200 && status < 300;
        }
    }
}
