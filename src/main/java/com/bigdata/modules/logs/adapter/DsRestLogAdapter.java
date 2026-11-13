package com.bigdata.modules.logs.adapter;

import com.bigdata.modules.logs.model.ComponentRole;
import com.bigdata.modules.logs.model.LogKind;
import com.bigdata.modules.logs.model.LogSource;
import com.bigdata.modules.logs.model.ServiceType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DolphinScheduler 日志适配器（REST 日志接口）。
 *
 * <h3>取数方式</h3>
 * <p>{@code GET {base-url}/log/detail?taskInstanceId=&skipLineNum=&limit=}，请求头 {@code token}。
 * {@code skipLineNum} 是<b>行偏移</b>，游标按实际返回行数推进，保证翻页不重不漏。</p>
 *
 * <h3>首行解析与主机路由</h3>
 * <p>DS 在任务日志不在当前服务端时，会先返回一行
 * {@code [LOG-PATH]: <path>, [HOST]: <host>}，真实内容在 worker 本地。这里解析该行：</p>
 * <ul>
 *   <li>host 非空且与 base-url 主机不同：按 {@code worker-endpoints} 找到该 worker 的地址后重发请求；</li>
 *   <li>找不到 worker 地址：返回降级结果并说明缺哪项配置，而不是把半行头部当日志返回；</li>
 *   <li>首行格式不符（无 {@code [LOG-PATH]}）：按原始内容返回，标记降级，不做主机路由。</li>
 * </ul>
 */
@Component
public class DsRestLogAdapter implements LogAdapter {

    private static final Logger log = LoggerFactory.getLogger(DsRestLogAdapter.class);

    /** DS 首行协议；PATH 允许包含空格，HOST 取到行尾 */
    static final Pattern LOG_HEADER = Pattern.compile(
            "^\\s*\\[LOG-PATH]:\\s*(.*?)\\s*,\\s*\\[HOST]:\\s*(.*?)\\s*$");

    private static final int HARD_MAX_LINES = 2000;

    private final HttpLogClient httpLogClient;
    private final ObjectMapper objectMapper;

    public DsRestLogAdapter(HttpLogClient httpLogClient, ObjectMapper objectMapper) {
        this.httpLogClient = httpLogClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(ServiceType serviceType, LogKind logKind, ComponentRole role) {
        if (serviceType != ServiceType.DS || logKind != LogKind.RUNTIME) {
            return false;
        }
        return role == ComponentRole.MASTER || role == ComponentRole.WORKER
                || role == ComponentRole.API || role == ComponentRole.GENERIC;
    }

    @Override
    public AdapterResult query(LogQueryContext context) {
        String taskInstanceId = context.ref("taskInstanceId", "taskInstance", "id");
        if (taskInstanceId == null) {
            throw new LogAdapterException(LogAdapterException.Code.BAD_REQUEST,
                    "缺少 ref.taskInstanceId");
        }
        LogSource source = context.getSource();
        String baseUrl = source.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new LogAdapterException(LogAdapterException.Code.UNAVAILABLE,
                    "DolphinScheduler 地址未配置");
        }

        long offset = Math.max(context.getOffset(), 0L);
        int limit = Math.min(Math.max(context.getMaxLines(), 1), HARD_MAX_LINES);

        Page page = fetch(source, baseUrl, taskInstanceId, offset, limit, context.getTimeoutMs());
        ParsedLog parsed = parse(page.content);

        String host = parsed.host;
        String path = parsed.path;
        String content = parsed.body;

        // 仅第一页做主机路由：后续页必须沿用同一个 worker，避免翻页串源
        if (parsed.headerPresent && offset == 0 && host != null && !host.isBlank()
                && !isSameHost(baseUrl, host)) {
            String workerBase = source.workerEndpoint(host);
            if (workerBase == null || workerBase.isBlank()) {
                AdapterResult degraded = AdapterResult.degraded("WORKER_ENDPOINT_MISSING",
                        "日志位于工作节点 " + host + "，未配置该节点访问地址，无法读取内容；"
                                + "请在 logs.sources[].worker-endpoints 中补充 " + host + " -> 该节点 DS 地址");
                degraded.setResolvedHost(host);
                degraded.setResolvedPath(path);
                degraded.setNextOffset(offset);
                log.warn("DS 日志位于 worker {}，但未配置 worker-endpoints", host);
                return degraded;
            }
            Page remote = fetch(source, workerBase, taskInstanceId, offset, limit, context.getTimeoutMs());
            ParsedLog remoteParsed = parse(remote.content);
            content = remoteParsed.body;
            if (remoteParsed.path != null) {
                path = remoteParsed.path;
            }
            if (remoteParsed.host != null && !remoteParsed.host.isBlank()) {
                host = remoteParsed.host;
            }
            page = remote;
        }

        BudgetLimiter.Result budget = BudgetLimiter.limitText(content, limit, context.getMaxBytes());
        int consumed = budget.getLines().size();
        long nextOffset = offset + consumed;

        boolean hasMore;
        if (budget.isLastLineTruncated()) {
            // 行游标无法表达「行内偏移」，继续翻页会重复该行，因此停止并明确提示
            hasMore = false;
        } else if (budget.isTruncated()) {
            hasMore = true;
        } else if (page.totalLines != null) {
            hasMore = nextOffset < page.totalLines;
        } else {
            // 上游未给总行数：返回满页就认为可能还有下一页
            hasMore = consumed >= limit;
        }

        AdapterResult result = AdapterResult.of(budget.getContent(), consumed, nextOffset, hasMore);
        result.setTruncated(budget.isTruncated());
        result.setTotalLines(page.totalLines);
        result.setResolvedHost(host);
        result.setResolvedPath(path);
        if (budget.isLastLineTruncated()) {
            result.setMessage("单行超过单次字节上限，已截断；为避免重复行不再继续翻页");
        }
        if (!parsed.headerPresent) {
            result.setDegraded(true);
            result.setErrorCode("HEADER_MISSING");
            result.setDegradationReason("日志首行不含 [LOG-PATH]/[HOST]，按原始内容返回，未做主机路由");
            result.setMessage(result.getDegradationReason());
        }
        return result;
    }

    private Page fetch(LogSource source, String baseUrl, String taskInstanceId,
                       long skipLines, int limit, long timeoutMs) {
        String url = stripTrailingSlash(baseUrl)
                + "/log/detail?taskInstanceId=" + encode(taskInstanceId)
                + "&skipLineNum=" + skipLines
                + "&limit=" + limit;
        Map<String, String> headers = new HashMap<>();
        if (source.getToken() != null && !source.getToken().isBlank()) {
            headers.put("token", source.getToken());
        }

        HttpTextResponse response;
        try {
            response = httpLogClient.get(url, headers, Duration.ofMillis(Math.max(1000L, timeoutMs)));
        } catch (Exception e) {
            throw new LogAdapterException(LogAdapterException.Code.UNAVAILABLE,
                    "DolphinScheduler 不可达: " + e.getMessage(), e);
        }

        int status = response.getStatus();
        if (status == 401 || status == 403) {
            throw new LogAdapterException(LogAdapterException.Code.UNAVAILABLE,
                    "DolphinScheduler 认证失败（HTTP " + status + "），请检查 token 配置");
        }
        if (status == 404) {
            throw new LogAdapterException(LogAdapterException.Code.NOT_FOUND,
                    "DolphinScheduler 日志接口不存在（HTTP 404），请检查 base-url");
        }
        if (!response.isSuccess()) {
            throw new LogAdapterException(LogAdapterException.Code.UNAVAILABLE,
                    "DolphinScheduler 返回 HTTP " + status);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new LogAdapterException(LogAdapterException.Code.FAILED,
                    "DolphinScheduler 返回内容不是合法 JSON（可能 base-url 指向了错误地址）", e);
        }
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            String msg = root.path("msg").asText("未知错误");
            String lower = msg.toLowerCase(Locale.ROOT);
            LogAdapterException.Code errorCode = (code == 10008 || lower.contains("not found"))
                    ? LogAdapterException.Code.NOT_FOUND : LogAdapterException.Code.FAILED;
            throw new LogAdapterException(errorCode, "DolphinScheduler: " + msg);
        }

        JsonNode data = root.path("data");
        String content;
        Long totalLines = null;
        if (data.isTextual()) {
            content = data.asText();
        } else {
            content = data.path("message").asText("");
            if (data.hasNonNull("lineNum")) {
                totalLines = data.get("lineNum").asLong();
            } else if (data.hasNonNull("totalLine")) {
                totalLines = data.get("totalLine").asLong();
            }
        }
        return new Page(content, totalLines);
    }

    static ParsedLog parse(String content) {
        if (content == null) {
            return new ParsedLog(false, null, null, "");
        }
        int newline = content.indexOf('\n');
        String firstLine = newline >= 0 ? content.substring(0, newline) : content;
        Matcher matcher = LOG_HEADER.matcher(firstLine);
        if (!matcher.matches()) {
            return new ParsedLog(false, null, null, content);
        }
        String path = matcher.group(1).isBlank() ? null : matcher.group(1).trim();
        String host = matcher.group(2).isBlank() ? null : matcher.group(2).trim();
        String body = newline >= 0 ? content.substring(newline + 1) : "";
        return new ParsedLog(true, host, path, body);
    }

    private boolean isSameHost(String baseUrl, String host) {
        String baseHost = hostOf(baseUrl);
        if (baseHost == null) {
            return false;
        }
        String left = stripPort(baseHost);
        String right = stripPort(host);
        if (left.equalsIgnoreCase(right)) {
            return true;
        }
        return isLoopback(left) && isLoopback(right);
    }

    private String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private String stripPort(String value) {
        if (value == null) {
            return "";
        }
        int idx = value.lastIndexOf(':');
        if (idx > 0 && value.indexOf(':') == idx) {
            return value.substring(0, idx);
        }
        return value;
    }

    private boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
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

    private static final class Page {
        private final String content;
        private final Long totalLines;

        private Page(String content, Long totalLines) {
            this.content = content;
            this.totalLines = totalLines;
        }
    }

    /** 首行解析结果 */
    static final class ParsedLog {
        private final boolean headerPresent;
        private final String host;
        private final String path;
        private final String body;

        private ParsedLog(boolean headerPresent, String host, String path, String body) {
            this.headerPresent = headerPresent;
            this.host = host;
            this.path = path;
            this.body = body;
        }

        boolean isHeaderPresent() {
            return headerPresent;
        }

        String getHost() {
            return host;
        }

        String getPath() {
            return path;
        }

        String getBody() {
            return body;
        }
    }
}
