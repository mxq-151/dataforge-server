package com.bigdata.modules.telemetry.v1.backend.ds;

import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.Requirement;
import com.bigdata.modules.telemetry.model.RequirementKind;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties.DsConfig;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties.DsLimits;
import com.bigdata.modules.telemetry.v1.model.BackendType;
import com.bigdata.modules.telemetry.v1.model.BackendWarning;
import com.bigdata.modules.telemetry.v1.model.LogDataType;
import com.bigdata.modules.telemetry.v1.model.LogEntry;
import com.bigdata.modules.telemetry.v1.spi.BackendCapability;
import com.bigdata.modules.telemetry.v1.spi.BackendException;
import com.bigdata.modules.telemetry.v1.spi.LogBackend;
import com.bigdata.modules.telemetry.v1.spi.LogQueryResult;
import com.bigdata.modules.telemetry.v1.spi.LogQuerySpec;
import com.bigdata.modules.telemetry.v1.support.LogFilterEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DolphinScheduler 任务日志后端：只承载 {@code log.task}。
 *
 * <p>取数路径：登录拿 session → 按 {@code ref.jobId}（DS taskInstanceId）或
 * {@code ref.jobName}（+ {@code workflowInstanceId} 消歧）定位任务实例 →
 * 分页调用 {@code /log/detail}。不使用旧 {@code /log/{id}}（日志缺失时返回 HTML）。</p>
 *
 * <p>jobName 在 DS 中不全局唯一：命中多条且未给 {@code workflowInstanceId} 时返回
 * {@link CapabilityCode#AMBIGUOUS_REFERENCE}，绝不静默取第一条。</p>
 *
 * <p>分页受总行数 / 总字节 / 页数三重上限约束，命中即 {@code truncated=true}。空日志返回
 * {@link CapabilityCode#NO_LOG_CONTENT}，由服务层映射为 UNAVAILABLE，不伪造空数组成功。</p>
 */
public final class DsApiLogBackend implements LogBackend {

    private static final String DEFAULT_HOST = "cluster";
    private static final int TASK_PAGE_SIZE = 100;

    private final String backendId;
    private final DsConfig config;
    private final DsLimits limits;
    private final String username;
    private final String password;
    private final DsApiClient client;

    public DsApiLogBackend(String backendId, DsConfig config, String username, String password,
                           ObjectMapper objectMapper) {
        this.backendId = backendId;
        this.config = config;
        this.limits = config.getLimits() == null ? new DsLimits() : config.getLimits();
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.client = isBlank(config.getBaseUrl()) ? null
                : new DsApiClient(config.getBaseUrl(), this.username, this.password,
                Duration.ofMillis(Math.max(limits.getTimeoutMs(), 1000)),
                Math.max(limits.getMaxBytes(), 1L), objectMapper);
    }

    @Override
    public String backendId() {
        return backendId;
    }

    @Override
    public BackendType backendType() {
        return BackendType.DS_API;
    }

    @Override
    public BackendCapability evaluate(LogQuerySpec spec) {
        if (spec.dataType() != LogDataType.TASK) {
            String actual = spec.dataType() == null ? "null" : spec.dataType().wire();
            return BackendCapability.unavailable(CapabilityCode.LOG_DATA_TYPE_MISMATCH,
                    "ds-api 只承载 log.task，不能用于 " + actual, List.of());
        }
        if (!LogDataType.TASK.supportsStream(spec.stream())) {
            return BackendCapability.unavailable(CapabilityCode.STREAM_NOT_SUPPORTED,
                    "DolphinScheduler 任务日志只支持 stdout / stderr，不支持 " + spec.stream(),
                    List.of());
        }
        if (spec.cursor() != null && !spec.cursor().isBlank()) {
            return BackendCapability.unavailable(CapabilityCode.INVALID_ARGUMENT,
                    "DS /log/detail 由服务端 skipLineNum 分页，不支持 cursor", List.of());
        }
        if (spec.timeRange() != null
                && (spec.timeRange().start() != null || spec.timeRange().end() != null)) {
            return BackendCapability.unavailable(CapabilityCode.TIME_FILTER_UNSUPPORTED,
                    "DS 任务日志没有统一的行首时间戳，不支持时间范围过滤", List.of());
        }
        if (isBlank(config.getBaseUrl())) {
            return BackendCapability.unavailable(CapabilityCode.NOT_CONFIGURED,
                    "未配置 DolphinScheduler base-url",
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "telemetry.backends.ds-api[].base-url",
                            "配置 DS API 根地址（含 /dolphinscheduler 上下文）")));
        }
        if (isBlank(username) || isBlank(password)) {
            return BackendCapability.unavailable(CapabilityCode.NOT_CONFIGURED,
                    "未配置 DolphinScheduler 登录凭据",
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "telemetry.backends.ds-api[].auth.username/password",
                            "配置登录用户名与密码（密码建议用 password-ref）")));
        }
        if (isBlank(jobId(spec)) && isBlank(jobName(spec))) {
            return BackendCapability.unavailable(CapabilityCode.INVALID_ARGUMENT,
                    "缺少必填引用: ref.jobId / ref.jobName（至少提供一个）", List.of());
        }
        return BackendCapability.available();
    }

    @Override
    public LogQueryResult fetch(LogQuerySpec spec) {
        if (client == null) {
            throw new BackendException(CapabilityCode.NOT_CONFIGURED,
                    "未配置 DolphinScheduler base-url");
        }
        LogFilterEngine filter;
        try {
            filter = LogFilterEngine.compile(spec.filter());
        } catch (IllegalArgumentException e) {
            throw new BackendException(CapabilityCode.INVALID_ARGUMENT, e.getMessage());
        }
        TaskRef task = resolveTask(spec);

        int pageSize = Math.max(limits.getPageSize(), 1);
        int maxPages = Math.max(limits.getMaxPages(), 1);
        int maxLines = Math.max(limits.getMaxLines(), 1);
        long maxBytes = spec.maxBytes() > 0
                ? Math.min(spec.maxBytes(), Math.max(limits.getMaxBytes(), 1L))
                : Math.max(limits.getMaxBytes(), 1L);
        int entryLimit = spec.limit() > 0 ? spec.limit() : 500;

        List<LogEntry> entries = new ArrayList<>();
        long totalLines = 0;
        long totalBytes = 0;
        boolean truncated = false;
        boolean contentSeen = false;
        int skip = 0;

        outer:
        for (int page = 0; page < maxPages; page++) {
            JsonNode data = client.get("/log/detail?taskInstanceId=" + task.taskInstanceId()
                    + "&skipLineNum=" + skip + "&limit=" + pageSize);
            int lineNum = data.path("lineNum").asInt(0);
            String message = data.path("message").asText("");
            if (lineNum <= 0 && message.isBlank()) {
                break;
            }
            String[] rawLines = message.split("\\r?\\n", -1);
            for (String raw : rawLines) {
                if (raw.isBlank()) {
                    continue;
                }
                contentSeen = true;
                totalLines++;
                totalBytes += utf8Length(raw) + 1;
                if (totalLines > maxLines || totalBytes > maxBytes) {
                    truncated = true;
                    break outer;
                }
                if (!filter.matches(raw)) {
                    continue;
                }
                if (entries.size() >= entryLimit) {
                    truncated = true;
                    break outer;
                }
                entries.add(new LogEntry(null, spec.stream(), task.hostId(), raw,
                        fields(task, spec.stream())));
            }
            skip += lineNum > 0 ? lineNum : rawLines.length;
            if (lineNum < pageSize) {
                break;
            }
        }

        if (!contentSeen) {
            throw new BackendException(CapabilityCode.NO_LOG_CONTENT,
                    "任务实例 " + task.taskInstanceId() + " 存在但日志为空"
                            + "（任务可能尚未开始执行或日志已被清理）");
        }

        List<BackendWarning> warnings = new ArrayList<>();
        if (truncated) {
            warnings.add(BackendWarning.of("OUTPUT_TRUNCATED",
                    "DS 日志超过 maxLines / maxBytes / limit，结果已截断且无法续页"));
        }
        if (task.hostFallback()) {
            warnings.add(BackendWarning.of("HOST_FALLBACK",
                    "未取到 taskInstance.host（" + task.hostReason() + "），hostId 使用 "
                            + DEFAULT_HOST));
        }
        return new LogQueryResult(entries, null, truncated,
                new LogQueryResult.ScanStats(0, totalBytes, totalLines, entries.size()), warnings);
    }

    // ------------------------------------------------------------------ 任务定位

    private TaskRef resolveTask(LogQuerySpec spec) {
        Long workflowInstanceId = optionalLong(spec.refs().get("workflowInstanceId"),
                "ref.workflowInstanceId");
        Long projectCode = optionalLong(spec.refs().get("projectCode"), "ref.projectCode");
        String jobId = jobId(spec);
        if (!isBlank(jobId)) {
            long taskInstanceId;
            try {
                taskInstanceId = Long.parseLong(jobId.trim());
            } catch (NumberFormatException e) {
                throw new BackendException(CapabilityCode.INVALID_ARGUMENT,
                        "DS 的 ref.jobId 必须是 taskInstanceId（数字），实际为: " + jobId);
            }
            ResolvedHost resolved = resolveHost(taskInstanceId, projectCode);
            if (resolved != null && !resolved.host().isBlank()) {
                return new TaskRef(taskInstanceId, resolved.host(),
                        workflowInstanceId == null ? resolved.workflowInstanceId() : workflowInstanceId,
                        resolved.projectCode() == null ? projectCode : resolved.projectCode(),
                        false, null);
            }
            String reason = resolved == null
                    ? "按 jobId 查询未取到 taskInstance.host（项目扫描未命中或未授权）"
                    : "taskInstance.host 为空";
            return new TaskRef(taskInstanceId, DEFAULT_HOST, workflowInstanceId, projectCode,
                    true, reason);
        }

        String jobName = jobName(spec).trim();
        List<ProjectCode> projects = projectScope(projectCode);
        Map<Long, JsonNode> matches = new HashMap<>();
        for (ProjectCode project : projects) {
            for (JsonNode item : queryTaskInstances(project.code(), jobName,
                    workflowInstanceId)) {
                if (!jobName.equals(item.path("name").asText(""))) {
                    continue;
                }
                long id = item.path("id").asLong(0L);
                if (id > 0) {
                    matches.putIfAbsent(id, item);
                }
            }
        }
        if (matches.isEmpty()) {
            throw new BackendException(CapabilityCode.NOT_FOUND,
                    "DolphinScheduler 未找到名称为 " + jobName + " 的任务实例"
                            + (workflowInstanceId == null ? ""
                            : "（workflowInstanceId=" + workflowInstanceId + "）"));
        }
        if (matches.size() > 1) {
            Set<Long> ids = new LinkedHashSet<>(matches.keySet());
            throw new BackendException(CapabilityCode.AMBIGUOUS_REFERENCE,
                    "DolphinScheduler 中 jobName=" + jobName + " 命中 " + matches.size()
                            + " 条任务实例 " + ids + "；请提供 ref.workflowInstanceId 消歧，"
                            + "或直接使用 ref.jobId");
        }
        JsonNode item = matches.values().iterator().next();
        String host = item.path("host").asText("");
        Long wfId = optionalLong(item.path("workflowInstanceId").asText(""), null);
        Long foundProject = optionalLong(item.path("projectCode").asText(""), null);
        boolean fallback = host.isBlank();
        return new TaskRef(item.path("id").asLong(), fallback ? DEFAULT_HOST : host,
                wfId == null ? workflowInstanceId : wfId,
                foundProject == null ? projectCode : foundProject,
                fallback, "taskInstance.host 为空");
    }

    /** 按 taskInstanceId 尽力解析 host；失败时返回 null，由调用方回退 cluster。 */
    private ResolvedHost resolveHost(long taskInstanceId, Long projectCode) {
        try {
            for (ProjectCode project : projectScope(projectCode)) {
                JsonNode item = findTaskInstance(project.code(), taskInstanceId);
                if (item != null) {
                    return new ResolvedHost(item.path("host").asText(""),
                            optionalLong(item.path("workflowInstanceId").asText(""), null),
                            optionalLong(item.path("projectCode").asText(""), null));
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private JsonNode findTaskInstance(long projectCode, long taskInstanceId) {
        int maxPages = Math.max(limits.getMaxPages(), 1);
        int page = 1;
        int totalPage;
        do {
            JsonNode data = client.get("/projects/" + projectCode + "/task-instances?pageNo="
                    + page + "&pageSize=" + TASK_PAGE_SIZE);
            JsonNode list = data.path("totalList");
            if (list.isArray()) {
                for (JsonNode item : list) {
                    if (item.path("id").asLong(0L) == taskInstanceId) {
                        return item;
                    }
                }
            }
            totalPage = data.path("totalPage").asInt(1);
            page++;
        } while (page <= totalPage && page <= maxPages);
        return null;
    }

    /** 项目范围：显式 ref.projectCode > 配置的 project-codes > 当前账号的授权项目。 */
    private List<ProjectCode> projectScope(Long refProjectCode) {
        if (refProjectCode != null) {
            return List.of(new ProjectCode(refProjectCode));
        }
        List<Long> configured = config.getProjectCodes();
        if (configured != null && !configured.isEmpty()) {
            return configured.stream().map(ProjectCode::new).toList();
        }
        return listProjects();
    }

    private List<ProjectCode> listProjects() {
        List<ProjectCode> projects = new ArrayList<>();
        int maxPages = Math.max(limits.getMaxPages(), 1);
        int page = 1;
        int totalPage;
        do {
            JsonNode data = client.get("/projects?pageNo=" + page
                    + "&pageSize=" + TASK_PAGE_SIZE);
            JsonNode list = data.path("totalList");
            if (list.isArray()) {
                for (JsonNode item : list) {
                    long code = item.path("code").asLong(0L);
                    if (code > 0) {
                        projects.add(new ProjectCode(code));
                    }
                }
            }
            totalPage = data.path("totalPage").asInt(1);
            page++;
        } while (page <= totalPage && page <= maxPages);
        return projects;
    }

    private List<JsonNode> queryTaskInstances(long projectCode, String jobName,
                                              Long workflowInstanceId) {
        List<JsonNode> items = new ArrayList<>();
        int maxPages = Math.max(limits.getMaxPages(), 1);
        int page = 1;
        int totalPage;
        do {
            StringBuilder path = new StringBuilder("/projects/").append(projectCode)
                    .append("/task-instances?pageNo=").append(page)
                    .append("&pageSize=").append(TASK_PAGE_SIZE)
                    .append("&taskName=").append(encode(jobName));
            if (workflowInstanceId != null) {
                path.append("&workflowInstanceId=").append(workflowInstanceId);
            }
            JsonNode data = client.get(path.toString());
            JsonNode list = data.path("totalList");
            if (list.isArray()) {
                list.forEach(items::add);
            }
            totalPage = data.path("totalPage").asInt(1);
            page++;
        } while (page <= totalPage && page <= maxPages);
        return items;
    }

    // ------------------------------------------------------------------ 辅助

    private Map<String, String> fields(TaskRef task, String stream) {
        Map<String, String> fields = new HashMap<>();
        fields.put("source", "ds-log-detail");
        fields.put("taskInstanceId", String.valueOf(task.taskInstanceId()));
        fields.put("host", task.hostId());
        fields.put("stream", stream);
        if (task.workflowInstanceId() != null) {
            fields.put("workflowInstanceId", String.valueOf(task.workflowInstanceId()));
        }
        if (task.projectCode() != null) {
            fields.put("projectCode", String.valueOf(task.projectCode()));
        }
        return fields;
    }

    private static String jobId(LogQuerySpec spec) {
        return spec.refs().get("jobId");
    }

    private static String jobName(LogQuerySpec spec) {
        return spec.refs().get("jobName");
    }

    private static Long optionalLong(String value, String field) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new BackendException(CapabilityCode.INVALID_ARGUMENT,
                    (field == null ? "引用" : field) + " 必须是数字，实际为: " + value);
        }
    }

    private static long utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ProjectCode(long code) {
    }

    private record ResolvedHost(String host, Long workflowInstanceId, Long projectCode) {
    }

    private record TaskRef(long taskInstanceId,
                           String hostId,
                           Long workflowInstanceId,
                           Long projectCode,
                           boolean hostFallback,
                           String hostReason) {
    }
}
