package com.bigdata.modules.logs.adapter;

import com.bigdata.modules.logs.model.ComponentRole;
import com.bigdata.modules.logs.model.LogKind;
import com.bigdata.modules.logs.model.LogSource;
import com.bigdata.modules.logs.model.ServiceType;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Spark History Server 日志适配器。
 *
 * <p>{@code GET {base-url}{logs-path-template}}，模板默认
 * {@code /api/v1/applications/{appId}/logs}，可配置 {@code {from}} / {@code {size}} 占位符。
 * 该接口随 Spark 版本与 SHS 配置不同是否存在，因此：</p>
 * <ul>
 *   <li>连接失败 / 5xx：降级为 {@code SOURCE_UNREACHABLE}，不抛 500；</li>
 *   <li>404 或返回 JSON：降级为 {@code UNSUPPORTED}，提示改用 YARN 聚合日志或 file 适配器。</li>
 * </ul>
 */
@Component
public class SparkShsLogAdapter implements LogAdapter {

    private static final String DEFAULT_TEMPLATE = "/api/v1/applications/{appId}/logs";

    private final HttpLogClient httpLogClient;

    public SparkShsLogAdapter(HttpLogClient httpLogClient) {
        this.httpLogClient = httpLogClient;
    }

    @Override
    public boolean supports(ServiceType serviceType, LogKind logKind, ComponentRole role) {
        if (serviceType != ServiceType.SPARK || logKind != LogKind.RUNTIME) {
            return false;
        }
        return role == ComponentRole.HISTORY_SERVER || role == ComponentRole.DRIVER
                || role == ComponentRole.EXECUTOR || role == ComponentRole.GENERIC;
    }

    @Override
    public AdapterResult query(LogQueryContext context) {
        String appId = context.ref("appId", "applicationId");
        if (appId == null) {
            throw new LogAdapterException(LogAdapterException.Code.BAD_REQUEST,
                    "缺少 ref.appId");
        }
        LogSource source = context.getSource();
        String baseUrl = source.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new LogAdapterException(LogAdapterException.Code.UNAVAILABLE,
                    "Spark History Server 地址未配置");
        }

        String template = source.getConfig().getOptions().getLogsPathTemplate();
        if (template == null || template.isBlank()) {
            template = DEFAULT_TEMPLATE;
        }
        boolean paged = template.contains("{from}") || template.contains("{size}");
        if (!paged && context.getOffset() > 0) {
            throw new LogAdapterException(LogAdapterException.Code.BAD_REQUEST,
                    "当前 Spark History Server 日志接口不支持游标分页，请不传 cursor");
        }

        String path = template
                .replace("{appId}", encode(appId))
                .replace("{from}", String.valueOf(Math.max(context.getOffset(), 0L)))
                .replace("{size}", String.valueOf(Math.max(context.getMaxBytes(), 1)));
        String url = stripTrailingSlash(baseUrl) + (path.startsWith("/") ? path : "/" + path);

        HttpTextResponse response;
        try {
            response = httpLogClient.get(url, null, Duration.ofMillis(Math.max(1000L, context.getTimeoutMs())));
        } catch (Exception e) {
            return AdapterResult.degraded("SOURCE_UNREACHABLE",
                    "Spark History Server 不可达: " + e.getMessage());
        }

        int status = response.getStatus();
        if (status == 404) {
            return AdapterResult.degraded("UNSUPPORTED",
                    "当前 Spark History Server 未提供日志接口（HTTP 404，依赖 Spark 版本与 eventLog 配置），"
                            + "可改用 YARN 聚合日志或 file 适配器读取 eventLog/driver 日志");
        }
        if (status == 401 || status == 403) {
            return AdapterResult.degraded("UNAUTHORIZED",
                    "Spark History Server 拒绝访问（HTTP " + status + "）");
        }
        if (status >= 500) {
            return AdapterResult.degraded("SOURCE_UNREACHABLE",
                    "Spark History Server 异常（HTTP " + status + "）");
        }
        if (!response.isSuccess()) {
            return AdapterResult.degraded("UPSTREAM_ERROR",
                    "Spark History Server 返回 HTTP " + status);
        }

        String body = response.getBody();
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return AdapterResult.degraded("UNSUPPORTED",
                    "Spark History Server 返回 JSON 而非日志文本，请确认 logs-path-template 指向日志接口"
                            + "（部分版本需开启 eventLog 或改用 YARN 聚合日志）");
        }

        BudgetLimiter.Result budget = BudgetLimiter.limitText(body, context.getMaxLines(),
                context.getMaxBytes());
        long nextOffset = paged
                ? Math.max(context.getOffset(), 0L) + budget.getByteSize()
                : 0L;
        boolean hasMore = paged && body != null
                && body.getBytes(StandardCharsets.UTF_8).length >= context.getMaxBytes();

        AdapterResult result = AdapterResult.of(budget.getContent(), budget.getLines().size(), nextOffset, hasMore);
        result.setTruncated(budget.isTruncated());
        return result;
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
