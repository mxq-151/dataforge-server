package com.bigdata.modules.asset.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * DolphinScheduler 只读客户端 —— 为「资产概况」的调度运行看板拉取项目、流程实例与任务实例。
 *
 * <h3>与 AssetProducerService 的关系</h3>
 * <p>两者都访问同一套 {@code scheduler.dolphinscheduler.*} 配置，但用途不同：
 * {@link AssetProducerService} 是「按表名实时反查产出工作流」（面向单表详情页），
 * 本客户端是「按时间窗口批量拉取运行记录」（面向全平台成本看板）。前者结果随调度系统
 * 实时变化，必须实时查；后者要做时间窗口聚合，必须落库，所以走采集链路。</p>
 *
 * <h3>未配置时的行为</h3>
 * <p>{@code scheduler.dolphinscheduler.base-url} 为空时 {@link #isConfigured()} 为 false，
 * 采集任务直接跳过（等同于能力未开启），不报错、不产生空数据覆盖。</p>
 *
 * <h3>时间与分页</h3>
 * <p>DS 返回的时间戳是 {@code yyyy-MM-dd HH:mm:ss}；{@code duration} 字段在不同版本里
 * 可能是格式化字符串，因此本客户端<b>不解析 duration</b>，只取 {@code startTime / endTime}
 * 由调用方自行换算秒数 —— 少依赖一个口径不稳的字段。</p>
 * <p>窗口过滤在本地做（服务端日期参数不同版本支持不一致），并对翻页设置了硬上限，
 * 达到上限会打 WARN 日志，避免上游数据异常时把请求拖死。</p>
 *
 * @author dataforge
 */
@Component
public class SchedulerApiClient {

    private static final Logger log = LoggerFactory.getLogger(SchedulerApiClient.class);

    /** 上游是外部系统，不能让采集任务被它拖死 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private static final int PAGE_SIZE = 200;
    /** 单个项目最多翻多少页（200 × 50 = 1 万条/项目/次），超出即告警并停止 */
    private static final int MAX_PAGES = 50;

    private static final DateTimeFormatter DS_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DS_TIME_QUERY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${scheduler.dolphinscheduler.base-url:}")
    private String baseUrl;

    @Value("${scheduler.dolphinscheduler.token:}")
    private String token;

    @Value("${scheduler.dolphinscheduler.default-project-code:}")
    private String defaultProjectCode;

    /** 是否已配置调度系统地址 */
    public boolean isConfigured() {
        return !isBlank(baseUrl);
    }

    /** 未配置时的兜底项目编码（与 AssetProducerService 一致，默认 "1"） */
    public String fallbackProjectCode() {
        return isBlank(defaultProjectCode) ? "1" : defaultProjectCode.trim();
    }

    /** 调度项目 */
    public record ProjectRef(String code, String name) {
    }

    /** 流程实例摘要：仅取延迟判定与工作流归属所需字段 */
    public record ProcessInstanceBrief(Long id, LocalDateTime scheduleTime,
                                       Long workflowCode, String workflowName) {
    }

    /** 任务实例摘要 */
    public record TaskInstanceBrief(Long processInstanceId, String nodeName, String taskType,
                                    String state, LocalDateTime startTime, LocalDateTime endTime) {
    }

    /** 项目列表；失败抛异常，由调用方决定是否降级 */
    public List<ProjectRef> listProjects() throws Exception {
        List<ProjectRef> result = new ArrayList<>();
        for (JsonNode node : fetchPages(pageQuery -> "/dolphinscheduler/projects" + pageQuery)) {
            String code = text(node, "code");
            if (code != null && !code.isBlank()) {
                result.add(new ProjectRef(code, firstNonBlank(text(node, "name"), code)));
            }
        }
        return result;
    }

    /** 某项目在窗口内的流程实例（本地按 scheduleTime/startTime 过滤由调用方负责判定） */
    public List<ProcessInstanceBrief> processInstances(String projectCode,
                                                       LocalDateTime from, LocalDateTime to) throws Exception {
        List<ProcessInstanceBrief> result = new ArrayList<>();
        String path = "/dolphinscheduler/projects/" + encode(projectCode) + "/process-instances";
        for (JsonNode node : fetchPages(pageQuery -> path + pageQuery + dateRange(from, to))) {
            Long id = longValue(node, "id");
            if (id == null) {
                continue;
            }
            result.add(new ProcessInstanceBrief(id,
                    parseTime(text(node, "scheduleTime")),
                    longValue(node, "processDefinitionCode"),
                    firstNonBlank(text(node, "processDefinitionName"), text(node, "name"))));
        }
        return result;
    }

    /** 某项目在窗口内的任务实例 */
    public List<TaskInstanceBrief> taskInstances(String projectCode,
                                                 LocalDateTime from, LocalDateTime to) throws Exception {
        List<TaskInstanceBrief> result = new ArrayList<>();
        String path = "/dolphinscheduler/projects/" + encode(projectCode) + "/task-instances";
        for (JsonNode node : fetchPages(pageQuery -> path + pageQuery + dateRange(from, to))) {
            result.add(new TaskInstanceBrief(
                    longValue(node, "processInstanceId"),
                    firstNonBlank(text(node, "name"), text(node, "taskName")),
                    text(node, "taskType"),
                    text(node, "state"),
                    parseTime(text(node, "startTime")),
                    parseTime(text(node, "endTime"))));
        }
        return result;
    }

    // ==================================================================
    // 分页与解析
    // ==================================================================

    /** URL 片段按页生成器：传入 "?pageNo=1&pageSize=200" 之类的前缀 */
    @FunctionalInterface
    private interface PathByPage {
        String path(String pageQuery);
    }

    private List<JsonNode> fetchPages(PathByPage pathByPage) throws Exception {
        List<JsonNode> all = new ArrayList<>();
        for (int pageNo = 1; pageNo <= MAX_PAGES; pageNo++) {
            String pageQuery = "?pageNo=" + pageNo + "&pageSize=" + PAGE_SIZE;
            JsonNode root = get(trimTrailingSlash(baseUrl) + pathByPage.path(pageQuery));
            JsonNode list = root.path("data").path("totalList");
            if (!list.isArray() || list.isEmpty()) {
                break;
            }
            list.forEach(all::add);
            if (list.size() < PAGE_SIZE) {
                break;
            }
            if (pageNo == MAX_PAGES) {
                log.warn("调度系统分页达到上限 {} 页，本次采集可能不完整（请缩窄时间窗口或检查上游数据量）", MAX_PAGES);
            }
        }
        return all;
    }

    private String dateRange(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) {
            return "";
        }
        return "&startDate=" + encode(from.format(DS_TIME_QUERY))
                + "&endDate=" + encode(to.format(DS_TIME_QUERY));
    }

    private JsonNode get(String url) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET();
        if (!isBlank(token)) {
            builder.header("token", token.trim());
        }
        HttpResponse<String> response = httpClient.send(
                builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("调度系统返回 HTTP " + response.statusCode() + ": " + url);
        }
        JsonNode root = objectMapper.readTree(response.body());
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            throw new IllegalStateException("调度系统返回业务错误 code=" + code
                    + ", msg=" + root.path("msg").asText(""));
        }
        return root;
    }

    private static LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim(), DS_TIME);
        } catch (Exception e) {
            // 个别版本返回 ISO 格式；解析失败按「无时间」处理，调用方会跳过该条
            try {
                return LocalDateTime.parse(value.trim());
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static Long longValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || !value.isNumber() ? null : value.asLong();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimTrailingSlash(String url) {
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
