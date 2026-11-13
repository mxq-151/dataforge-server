package com.bigdata.modules.logs.adapter;

import com.bigdata.modules.logs.model.ComponentRole;
import com.bigdata.modules.logs.model.LogKind;
import com.bigdata.modules.logs.model.LogSource;
import com.bigdata.modules.logs.model.ServiceType;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;

/**
 * YARN NodeManager 容器日志适配器（单容器、可 tail）。
 *
 * <p>{@code GET {nm}/containerlogs/{containerId}/{user}?start=<byteOffset>}，
 * NodeManager 原生支持按字节起点读取，因此游标使用<b>字节偏移</b>，并切在行边界，
 * 下一页不会出现半行重复。</p>
 *
 * <p>该接口依赖 YARN 版本与容器日志保留策略；聚合后的历史日志请使用 {@link YarnLogAdapter}。</p>
 */
@Component
public class YarnRestLogAdapter implements LogAdapter {

    private final HttpLogClient httpLogClient;

    public YarnRestLogAdapter(HttpLogClient httpLogClient) {
        this.httpLogClient = httpLogClient;
    }

    @Override
    public boolean supports(ServiceType serviceType, LogKind logKind, ComponentRole role) {
        if (serviceType != ServiceType.YARN || logKind != LogKind.RUNTIME) {
            return false;
        }
        return role == ComponentRole.NODE_MANAGER || role == ComponentRole.GENERIC;
    }

    @Override
    public AdapterResult query(LogQueryContext context) {
        String containerId = context.ref("containerId", "container", "container_id");
        if (containerId == null) {
            throw new LogAdapterException(LogAdapterException.Code.BAD_REQUEST,
                    "缺少 ref.containerId");
        }
        LogSource source = context.getSource();
        String host = context.ref("host", "nodeManager", "nodeManagerHost");
        String nodeManagerUrl = host == null ? null : source.workerEndpoint(host);
        if (nodeManagerUrl == null || nodeManagerUrl.isBlank()) {
            nodeManagerUrl = source.getBaseUrl();
        }
        if (nodeManagerUrl == null || nodeManagerUrl.isBlank()) {
            throw new LogAdapterException(LogAdapterException.Code.UNAVAILABLE,
                    "未配置 NodeManager 地址（base-url 与 worker-endpoints 均为空）");
        }

        String user = context.ref("user");
        if (user == null) {
            user = source.getConfig().getOptions().getDefaultUser();
        }
        if (user == null || user.isBlank()) {
            user = "yarn";
        }

        long offset = Math.max(context.getOffset(), 0L);
        long window = Math.max(1024L, Math.min(context.getMaxBytes() + 1L,
                source.getConfig().getOptions().getFetchBytes()));
        String url = stripTrailingSlash(nodeManagerUrl)
                + "/containerlogs/" + encode(containerId) + "/" + encode(user)
                + "?start=" + offset;

        HttpTextResponse response;
        try {
            response = httpLogClient.get(url, new HashMap<>(),
                    Duration.ofMillis(Math.max(1000L, context.getTimeoutMs())));
        } catch (Exception e) {
            throw new LogAdapterException(LogAdapterException.Code.UNAVAILABLE,
                    "YARN NodeManager 不可达: " + e.getMessage(), e);
        }

        int status = response.getStatus();
        if (status == 404) {
            throw new LogAdapterException(LogAdapterException.Code.NOT_FOUND,
                    "NodeManager 上不存在容器日志: " + containerId);
        }
        if (status == 401 || status == 403) {
            throw new LogAdapterException(LogAdapterException.Code.UNAVAILABLE,
                    "YARN NodeManager 拒绝访问（HTTP " + status + "），请检查容器用户与权限");
        }
        if (!response.isSuccess()) {
            throw new LogAdapterException(LogAdapterException.Code.UNAVAILABLE,
                    "YARN NodeManager 返回 HTTP " + status);
        }

        String body = response.getBody();
        boolean cutByWindow = body.getBytes(StandardCharsets.UTF_8).length >= window;

        // 切在最后一个换行之后，避免下一页从半行开始
        String windowed = cutAtLineBoundary(body, (int) Math.min(window, Integer.MAX_VALUE));
        BudgetLimiter.Result budget = BudgetLimiter.limitText(windowed, context.getMaxLines(),
                context.getMaxBytes());
        int consumedBytes = bytesOfFirstLines(windowed, budget.getLines().size());

        AdapterResult result = AdapterResult.of(budget.getContent(), budget.getLines().size(),
                offset + consumedBytes, budget.isTruncated() || cutByWindow);
        result.setTruncated(budget.isTruncated());
        result.setResolvedHost(host);
        return result;
    }

    static String cutAtLineBoundary(String body, int maxBytes) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        if (raw.length <= maxBytes) {
            return body;
        }
        int idx = maxBytes;
        while (idx > 0 && raw[idx] != '\n') {
            idx--;
        }
        if (idx <= 0) {
            return BudgetLimiter.truncateUtf8(body, maxBytes);
        }
        return new String(raw, 0, idx + 1, StandardCharsets.UTF_8);
    }

    static int bytesOfFirstLines(String content, int lineCount) {
        if (content == null || content.isEmpty() || lineCount <= 0) {
            return 0;
        }
        int seen = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                seen++;
                if (seen == lineCount) {
                    return content.substring(0, i + 1).getBytes(StandardCharsets.UTF_8).length;
                }
            }
        }
        return content.getBytes(StandardCharsets.UTF_8).length;
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
}
