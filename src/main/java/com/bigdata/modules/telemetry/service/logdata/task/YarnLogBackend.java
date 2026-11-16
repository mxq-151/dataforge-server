package com.bigdata.modules.telemetry.service.logdata.task;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.Requirement;
import com.bigdata.modules.telemetry.common.model.RequirementKind;
import com.bigdata.modules.telemetry.common.http.EngineRestClient;
import com.bigdata.modules.telemetry.common.model.BackendType;
import com.bigdata.modules.telemetry.common.model.BackendWarning;
import com.bigdata.modules.telemetry.service.logdata.model.LogDataType;
import com.bigdata.modules.telemetry.service.logdata.model.LogEntry;
import com.bigdata.modules.telemetry.common.model.BackendCapability;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.bigdata.modules.telemetry.service.logdata.spi.LogBackend;
import com.bigdata.modules.telemetry.service.logdata.model.LogQueryResult;
import com.bigdata.modules.telemetry.service.logdata.model.LogQuerySpec;
import com.bigdata.modules.telemetry.service.logdata.support.LogFilterEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.bigdata.modules.telemetry.config.properties.YarnConfig;
import com.bigdata.modules.telemetry.util.Urls;
import com.bigdata.modules.telemetry.util.Strings;

/**
 * YARN 容器日志后端：只承载 {@code log.task}。
 *
 * <p><b>两条取数路径，按可用性降级：</b></p>
 * <ol>
 *   <li><b>aggregated</b>：{@code yarn logs -applicationId ... -log_files stdout|stderr}。YARN 聚合
 *       日志由 NodeManager 写入远端存储，输出是逐容器的 {@code Container: <id> on <nmHost>} 分段，
 *       本类按分段解析，保证每条样本文能追溯到 containerId 与 NM host。</li>
 *   <li><b>node-local</b>：聚合未完成（应用仍在运行、{@code logAggregationStatus=NOT_START} 等）时，
 *       {@code yarn logs} 只能看到空内容甚至报 “does not exist”。此时通过 RM
 *       {@code /ws/v1/cluster/nodes} → NM {@code /ws/v1/node/containers} 定位该应用的 containerId，
 *       再读 {@code node-log-root/application_x/container_y/stdout|stderr}。</li>
 * </ol>
 *
 * <p><b>平台限制（会出现在响应告警/不可用 reason 中，而不只是文档）：</b>
 * 聚合完成前只能走 node-local；NM 的容器日志目录没有挂载到本进程可见的文件系统时，
 * 该容器在聚合完成前不可获取（{@link CapabilityCode#LOG_SOURCE_UNAVAILABLE}）。
 * 本类不会用空字符串或 0 行伪造成功。</p>
 *
 * <p>CLI 约束：单次调用有超时，进程被 {@code destroyForcibly} 终止；stdout 直接读入内存并受
 * {@code maxBytes} 限制，不落临时文件；同时运行的子进程数由信号量限制，拿不到槽位返回
 * {@code RATE_LIMITED}。node-local 读取同样受总字节预算约束。</p>
 *
 * <p>YARN 容器日志没有统一的行首时间戳，也不支持游标分页；带时间范围或 cursor 的查询明确返回
 * {@code TIME_FILTER_UNSUPPORTED} / {@code INVALID_ARGUMENT}，不做静默裁剪。</p>
 */
public final class YarnLogBackend implements LogBackend {

    /** 便于单测替换命令执行；生产实现见 {@link ProcessCommandRunner}。 */
    public interface CommandRunner {
        CommandOutput run(List<String> command, Duration timeout, long maxBytes)
                throws CommandTimeoutException, IOException;
    }

    public static class CommandTimeoutException extends Exception {
        public CommandTimeoutException(String message) {
            super(message);
        }
    }

    public record CommandOutput(int exitCode, String stdout, String stderr,
                                boolean truncated, long stdoutBytes) {
    }

    private static final long STDERR_CAPTURE_BYTES = 8192L;
    private static final long NM_RESPONSE_BYTES = 4L * 1024 * 1024;

    private static final String WARN_OUTPUT_TRUNCATED = "OUTPUT_TRUNCATED";
    private static final String WARN_LOG_AGGREGATION_PARTIAL = "LOG_AGGREGATION_PARTIAL";
    private static final String WARN_LOG_AGGREGATION_NOT_COMPLETE = "LOG_AGGREGATION_NOT_COMPLETE";
    private static final String WARN_NODE_LOCAL_UNREADABLE = "NODE_LOCAL_UNREADABLE";
    private static final String WARN_NM_UNREACHABLE = "NM_UNREACHABLE";
    private static final String WARN_NM_SCAN_TRUNCATED = "NM_SCAN_TRUNCATED";

    private static final String PLATFORM_LIMIT =
            "聚合完成前只能走 node-local，NM 日志目录未挂载到本进程可见路径时该容器不可获取";

    private final String backendId;
    private final YarnConfig config;
    private final EngineRestClient rmClient;
    private final CommandRunner runner;
    private final Semaphore permits;
    private final String authorizationHeader;
    private final ObjectMapper objectMapper;

    public YarnLogBackend(String backendId, YarnConfig config, String authorizationHeader,
                          ObjectMapper objectMapper) {
        this(backendId, config,
                Strings.isBlank(config.getRmBaseUrl()) ? null
                        : new EngineRestClient(config.getRmBaseUrl(), authorizationHeader,
                        Duration.ofMillis(Math.max(config.getLimits().getTimeoutMs(), 1000)),
                        8388608L, objectMapper),
                new ProcessCommandRunner(), authorizationHeader, objectMapper);
    }

    YarnLogBackend(String backendId, YarnConfig config, EngineRestClient rmClient,
                   CommandRunner runner) {
        this(backendId, config, rmClient, runner, null, new ObjectMapper());
    }

    private YarnLogBackend(String backendId, YarnConfig config, EngineRestClient rmClient,
                           CommandRunner runner, String authorizationHeader,
                           ObjectMapper objectMapper) {
        this.backendId = backendId;
        this.config = config;
        this.rmClient = rmClient;
        this.runner = runner;
        this.authorizationHeader = authorizationHeader;
        this.objectMapper = objectMapper;
        this.permits = new Semaphore(Math.max(config.getLimits().getMaxConcurrent(), 1));
    }

    @Override
    public String backendId() {
        return backendId;
    }

    @Override
    public BackendType backendType() {
        return BackendType.YARN;
    }

    @Override
    public BackendCapability evaluate(LogQuerySpec spec) {
        if (spec.dataType() != LogDataType.TASK) {
            String actual = spec.dataType() == null ? "null" : spec.dataType().wire();
            return BackendCapability.unavailable(CapabilityCode.LOG_DATA_TYPE_MISMATCH,
                    "yarn 只承载 log.task，不能用于 " + actual, List.of());
        }
        if (!LogDataType.TASK.supportsStream(spec.stream())) {
            return BackendCapability.unavailable(CapabilityCode.STREAM_NOT_SUPPORTED,
                    "YARN 容器日志只支持 stdout / stderr，不支持 " + spec.stream(), List.of());
        }
        if (spec.cursor() != null && !spec.cursor().isBlank()) {
            return BackendCapability.unavailable(CapabilityCode.INVALID_ARGUMENT,
                    "yarn logs 不支持游标分页，cursor 必须为空", List.of());
        }
        if (spec.timeRange() != null
                && (spec.timeRange().start() != null || spec.timeRange().end() != null)) {
            return BackendCapability.unavailable(CapabilityCode.TIME_FILTER_UNSUPPORTED,
                    "YARN 容器日志没有统一的行首时间戳，不支持时间范围过滤",
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "telemetry.backends.yarn[].sources[].timestamp-pattern",
                            "如需时间过滤，请把容器日志接入 local-disk / ES 并配置时间戳格式")));
        }
        if (Strings.isBlank(config.getCommand())) {
            return BackendCapability.unavailable(CapabilityCode.NOT_CONFIGURED,
                    "未配置 yarn 可执行文件",
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "telemetry.backends.yarn[].command", "配置 yarn 命令或绝对路径")));
        }
        if (Strings.isBlank(taskId(spec)) && Strings.isBlank(jobName(spec))) {
            return BackendCapability.unavailable(CapabilityCode.INVALID_ARGUMENT,
                    "缺少必填引用: ref.taskId / ref.jobName（至少提供一个）", List.of());
        }
        if (Strings.isBlank(taskId(spec)) && rmClient == null) {
            return BackendCapability.unavailable(CapabilityCode.NOT_CONFIGURED,
                    "只给了 ref.jobName，但未配置 YARN RM 地址，无法解析 applicationId",
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "telemetry.backends.yarn[].rm-base-url",
                            "配置 RM REST 地址，或直接传 ref.taskId")));
        }
        if (!Strings.isBlank(taskId(spec)) && !taskId(spec).startsWith("application_")) {
            // Spark standalone / local[*] 没有 YARN applicationId，也没有 NM 聚合日志；
            // SHS /logs 是事件日志 zip，不能当文本日志解析，因此这里明确不可用。
            return BackendCapability.unavailable(CapabilityCode.LOG_SOURCE_UNAVAILABLE,
                    "ref.taskId=" + taskId(spec) + " 不是 YARN applicationId：standalone/local 模式"
                            + "没有聚合日志，SHS 的 /logs 只是事件日志 zip，不能作为 stdout/stderr 来源",
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "ref.taskId",
                            "Spark 任务日志只支持 YARN 上的 application_* ID；standalone 模式无可用文本日志通道")));
        }
        return BackendCapability.available();
    }

    @Override
    public LogQueryResult fetch(LogQuerySpec spec) {
        String appId = taskId(spec);
        if (Strings.isBlank(appId)) {
            appId = resolveByJobName(jobName(spec));
        }
        LogFilterEngine filter;
        try {
            filter = LogFilterEngine.compile(spec.filter());
        } catch (IllegalArgumentException e) {
            throw new BackendException(CapabilityCode.INVALID_ARGUMENT, e.getMessage());
        }

        int limit = spec.limit() > 0 ? spec.limit() : 500;
        long maxBytes = spec.maxBytes() > 0
                ? Math.min(spec.maxBytes(), config.getLimits().getMaxBytes())
                : config.getLimits().getMaxBytes();
        Duration timeout = spec.timeout() != null ? spec.timeout()
                : Duration.ofMillis(config.getLimits().getTimeoutMs());

        Collect aggregated = new Collect();
        String aggregationFailure = readAggregated(appId, spec.stream(), filter, limit, maxBytes,
                timeout, aggregated);
        if (aggregated.hasEntries()) {
            return aggregated.toResult();
        }

        Collect nodeLocal = new Collect();
        NodeLocalRead nodeLocalRead = readNodeLocal(appId, spec, filter, limit, maxBytes, timeout,
                aggregationFailure, nodeLocal);
        if (nodeLocal.hasEntries()) {
            return nodeLocal.toResult();
        }

        // 有内容但被 filter 过滤掉：明确的空结果，不是“取不到”。
        if (nodeLocalRead.readableFiles() > 0 || aggregated.lines > 0) {
            return aggregated.lines > 0 ? aggregated.toResult() : nodeLocal.toResult();
        }

        if (aggregated.blocksSeen && !aggregated.incomplete) {
            throw new BackendException(CapabilityCode.NO_LOG_CONTENT,
                    "YARN 应用 " + appId + " 的容器存在，但 stream=" + spec.stream()
                            + " 没有日志内容");
        }
        throw new BackendException(CapabilityCode.LOG_SOURCE_UNAVAILABLE,
                "无法获取 " + appId + " 的 " + spec.stream() + " 日志：聚合路径="
                        + orUnknown(aggregationFailure) + "；NM 本地路径="
                        + orUnknown(nodeLocalRead.failureReason()) + "。平台限制：" + PLATFORM_LIMIT);
    }

    // ------------------------------------------------------------------ aggregated

    /**
     * @return {@code null} 表示 yarn logs 正常返回（即使内容为空）；否则为降级原因。
     */
    private String readAggregated(String appId, String stream, LogFilterEngine filter, int limit,
                                  long maxBytes, Duration timeout, Collect out) {
        CommandOutput output;
        acquirePermit();
        try {
            output = runner.run(command(appId, stream), timeout, maxBytes);
        } catch (CommandTimeoutException e) {
            throw new BackendException(CapabilityCode.UPSTREAM_TIMEOUT, e.getMessage());
        } catch (IOException e) {
            throw new BackendException(CapabilityCode.IO_ERROR,
                    "执行 yarn logs 失败: " + e.getMessage());
        } finally {
            permits.release();
        }

        AggregatedParse parsed = parseAggregated(output.stdout());
        out.bytes = output.stdoutBytes();
        out.lines = parsed.lines();
        out.files = parsed.blocks().size();
        out.truncated = output.truncated();

        if (parsed.blocks().isEmpty()) {
            out.incomplete = true;
            Boolean exists = applicationExists(appId);
            if (Boolean.FALSE.equals(exists)) {
                throw new BackendException(CapabilityCode.NOT_FOUND,
                        "YARN 未找到应用 " + appId + "（RM 返回 404）");
            }
            if (exists == null && output.exitCode() != 0 && indicatesApplicationMissing(output.stderr())) {
                throw cliFailure(output);
            }
            return describeAggregatedFailure(output);
        }

        out.blocksSeen = true;
        for (ContainerBlock block : parsed.blocks()) {
            if (block.partial()) {
                out.incomplete = true;
            }
            for (String line : block.lines()) {
                if (line.isBlank() || !filter.matches(line)) {
                    continue;
                }
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("containerId", block.containerId());
                fields.put("nmHost", block.nmHost());
                fields.put("source", "aggregated");
                if (!block.aggregationType().isBlank()) {
                    fields.put("aggregationType", block.aggregationType());
                }
                out.add(new LogEntry(null, stream, block.nmHost(), line, fields), limit);
            }
        }
        if (out.incomplete) {
            out.warnings.add(BackendWarning.of(WARN_LOG_AGGREGATION_PARTIAL,
                    "yarn logs 返回的部分容器仍在运行或聚合未完成，内容不完整；"
                            + "需要完整日志请等待聚合完成，或挂载 NM 日志目录走 node-local"));
        }
        if (out.limitHit || out.truncated) {
            out.warnings.add(BackendWarning.of(WARN_OUTPUT_TRUNCATED,
                    "yarn logs 输出超过 maxBytes / limit，结果已截断且无法续页"));
        }
        return null;
    }

    /** 解析 {@code yarn logs} 的逐容器分段；每个分段自带 containerId / NM host。 */
    private static AggregatedParse parseAggregated(String stdout) {
        List<ContainerBlock> blocks = new ArrayList<>();
        ContainerBlock current = null;
        boolean inContents = false;
        long lines = 0;
        for (String raw : stdout.split("\\r?\\n", -1)) {
            if (raw.startsWith("Container: ")) {
                String body = raw.substring("Container: ".length()).trim();
                int on = body.lastIndexOf(" on ");
                String id = on < 0 ? body : body.substring(0, on).trim();
                String host = on < 0 ? "" : body.substring(on + 4).trim();
                current = new ContainerBlock(id, host);
                blocks.add(current);
                inContents = false;
                continue;
            }
            if (current == null) {
                continue;
            }
            if (raw.startsWith("LogAggregationType:")) {
                current.aggregationType = raw.substring("LogAggregationType:".length()).trim();
                inContents = false;
                continue;
            }
            if (raw.startsWith("LogType:") || raw.startsWith("LogLastModifiedTime:")
                    || raw.startsWith("LogLength:") || raw.startsWith("*")) {
                inContents = false;
                continue;
            }
            if ("LogContents:".equals(raw.trim())) {
                inContents = true;
                continue;
            }
            if (raw.startsWith("End of LogType:")) {
                if (raw.contains("belongs to a running container")) {
                    current.partial = true;
                }
                inContents = false;
                continue;
            }
            if (inContents && !raw.isBlank()) {
                current.lines.add(raw);
                lines++;
            }
        }
        return new AggregatedParse(blocks, lines);
    }

    private static String describeAggregatedFailure(CommandOutput output) {
        for (String source : List.of(output.stdout(), output.stderr())) {
            if (source == null) {
                continue;
            }
            for (String line : source.split("\\r?\\n")) {
                String text = line.trim();
                if (text.isEmpty() || text.startsWith("WARNING")
                        || text.startsWith("log4j") || text.contains("NativeCodeLoader")
                        || text.contains("DefaultNoHARMFailover") || text.startsWith("20")) {
                    continue;
                }
                return "yarn logs 未返回聚合内容（exit=" + output.exitCode() + "）：" + text;
            }
        }
        return "yarn logs 未返回聚合内容（exit=" + output.exitCode() + "）";
    }

    // ------------------------------------------------------------------ node-local

    private NodeLocalRead readNodeLocal(String appId, LogQuerySpec spec, LogFilterEngine filter,
                                        int limit, long maxBytes, Duration timeout,
                                        String aggregationFailure, Collect out) {
        String stream = spec.stream();
        if (rmClient == null) {
            return new NodeLocalRead(
                    "未配置 RM（telemetry.backends.yarn[].rm-base-url），无法定位 NM", 0, 0);
        }
        if (Strings.isBlank(config.getNodeLogRoot())) {
            return new NodeLocalRead(
                    "未配置 NM 容器日志根目录（telemetry.backends.yarn[].node-log-root）", 0, 0);
        }
        Path root = Paths.get(config.getNodeLogRoot());
        if (!Files.isDirectory(root)) {
            return new NodeLocalRead(
                    "NM 日志根目录不存在或不可读（未挂载到本进程）：" + root, 0, 0);
        }

        List<JsonNode> nodes;
        try {
            nodes = rmNodes();
        } catch (BackendException e) {
            return new NodeLocalRead("RM /ws/v1/cluster/nodes 不可用：" + e.getMessage(), 0, 0);
        }
        if (nodes.isEmpty()) {
            return new NodeLocalRead("RM 未返回任何 NM 节点", 0, 0);
        }

        out.warnings.add(BackendWarning.of(WARN_LOG_AGGREGATION_NOT_COMPLETE,
                "YARN 聚合未完成（" + appInfo(appId) + (aggregationFailure == null ? ""
                        : "，yarn logs=" + aggregationFailure) + "），已回退到 NM 容器本地日志；"
                        + "平台限制：" + PLATFORM_LIMIT + "（node-log-root=" + root + "）"));

        int maxNodes = Math.max(config.getLimits().getMaxNodes(), 1);
        if (nodes.size() > maxNodes) {
            nodes = new ArrayList<>(nodes.subList(0, maxNodes));
            out.warnings.add(BackendWarning.of(WARN_NM_SCAN_TRUNCATED,
                    "NM 节点超过 " + maxNodes + " 个，仅扫描前 " + maxNodes
                            + " 个；其余节点上的容器未参与本次查询"));
        }

        int containers = 0;
        int readableFiles = 0;
        long remaining = maxBytes;
        for (JsonNode node : nodes) {
            String nodeHttpAddress = node.path("nodeHTTPAddress").asText("");
            String nmHost = node.path("nodeHostName").asText("");
            if (nmHost.isBlank()) {
                nmHost = hostOf(nodeHttpAddress);
            }
            String baseUrl = nmBaseUrl(nodeHttpAddress);
            if (Strings.isBlank(baseUrl)) {
                continue;
            }
            EngineRestClient nmClient = new EngineRestClient(baseUrl, authorizationHeader,
                    timeout, NM_RESPONSE_BYTES, objectMapper);
            JsonNode containersNode;
            try {
                containersNode = nmClient.getJson("/ws/v1/node/containers");
            } catch (BackendException e) {
                out.warnings.add(BackendWarning.of(WARN_NM_UNREACHABLE,
                        "NM " + nmHost + "（" + baseUrl + "）不可达：" + e.getMessage()));
                continue;
            }
            for (JsonNode container : iterable(containersNode.path("containers").path("container"))) {
                String containerId = container.path("id").asText("");
                if (!belongsToApp(containerId, appId)) {
                    continue;
                }
                containers++;
                String state = container.path("state").asText("");
                Path file = root.resolve(appId).resolve(containerId).resolve(stream);
                if (!Files.isRegularFile(file)) {
                    out.warnings.add(BackendWarning.of(WARN_NODE_LOCAL_UNREADABLE,
                            "containerId=" + containerId + ", nmHost=" + nmHost
                                    + ", state=" + orUnknown(state)
                                    + ", reason=本地日志文件不存在（尚未产生或聚合完成后已被回收）："
                                    + file));
                    continue;
                }
                if (remaining <= 0) {
                    out.truncated = true;
                    break;
                }
                Bounded content;
                try {
                    content = readBounded(Files.newInputStream(file), remaining);
                } catch (IOException e) {
                    out.warnings.add(BackendWarning.of(WARN_NODE_LOCAL_UNREADABLE,
                            "containerId=" + containerId + ", nmHost=" + nmHost
                                    + ", reason=读取失败：" + e.getMessage()));
                    continue;
                }
                remaining -= content.bytes().length;
                if (content.truncated()) {
                    out.truncated = true;
                }
                readableFiles++;
                out.files++;
                String text = new String(content.bytes(), StandardCharsets.UTF_8);
                for (String line : text.split("\\r?\\n", -1)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    out.lines++;
                    if (!filter.matches(line)) {
                        continue;
                    }
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("containerId", containerId);
                    fields.put("nmHost", nmHost);
                    fields.put("source", "node-local");
                    if (!state.isBlank()) {
                        fields.put("containerState", state);
                    }
                    out.add(new LogEntry(null, stream, nmHost, line, fields), limit);
                }
            }
        }

        if (out.limitHit || out.truncated) {
            out.warnings.add(BackendWarning.of(WARN_OUTPUT_TRUNCATED,
                    "node-local 日志超过 maxBytes / limit，结果已截断"));
        }
        if (containers == 0) {
            return new NodeLocalRead("RM 各 NM 上未发现应用 " + appId
                    + " 的容器（应用可能已结束且本地日志已回收）", containers, readableFiles);
        }
        if (readableFiles == 0) {
            return new NodeLocalRead("发现 " + containers + " 个容器，但本地日志均不可读（详见 "
                    + WARN_NODE_LOCAL_UNREADABLE + " 告警）", containers, readableFiles);
        }
        return new NodeLocalRead(null, containers, readableFiles);
    }

    private List<JsonNode> rmNodes() {
        JsonNode root = rmClient.getJson("/ws/v1/cluster/nodes");
        JsonNode nodes = root.path("nodes").path("node");
        List<JsonNode> list = new ArrayList<>();
        if (nodes.isArray()) {
            nodes.forEach(list::add);
        } else if (nodes.isObject()) {
            list.add(nodes);
        }
        return list;
    }

    /** 聚合状态只用于告警语义，取不到不影响降级。 */
    private String appInfo(String appId) {
        if (rmClient == null) {
            return "logAggregationStatus=UNKNOWN";
        }
        try {
            JsonNode app = rmClient.getJson("/ws/v1/cluster/apps/" + Urls.encode(appId)).path("app");
            String state = app.path("state").asText("UNKNOWN");
            String aggregation = app.path("logAggregationStatus").asText("UNKNOWN");
            return "state=" + state + ", logAggregationStatus=" + aggregation;
        } catch (BackendException e) {
            return "logAggregationStatus=UNKNOWN";
        }
    }

    /** @return true / false（RM 明确 404）；null 表示无法判定。 */
    private Boolean applicationExists(String appId) {
        if (rmClient == null) {
            return null;
        }
        try {
            rmClient.getJson("/ws/v1/cluster/apps/" + Urls.encode(appId));
            return Boolean.TRUE;
        } catch (BackendException e) {
            return e.getCode() == CapabilityCode.NOT_FOUND ? Boolean.FALSE : null;
        }
    }

    private String nmBaseUrl(String nodeHttpAddress) {
        if (!Strings.isBlank(config.getNmBaseUrl())) {
            return config.getNmBaseUrl().trim();
        }
        if (Strings.isBlank(nodeHttpAddress)) {
            return "";
        }
        return nodeHttpAddress.startsWith("http") ? nodeHttpAddress : "http://" + nodeHttpAddress;
    }

    private static Iterable<JsonNode> iterable(JsonNode node) {
        if (node.isArray()) {
            return node;
        }
        return node.isObject() ? List.of(node) : List.of();
    }

    private static boolean belongsToApp(String containerId, String appId) {
        String prefix = "application_";
        if (containerId == null || !appId.startsWith(prefix)) {
            return false;
        }
        return containerId.startsWith("container_" + appId.substring(prefix.length()) + "_");
    }

    // ------------------------------------------------------------------ 内部

    private void acquirePermit() {
        try {
            if (!permits.tryAcquire(config.getLimits().getAcquireTimeoutMs(), TimeUnit.MILLISECONDS)) {
                throw new BackendException(CapabilityCode.RATE_LIMITED,
                        "并发查询已满（max-concurrent=" + config.getLimits().getMaxConcurrent() + "）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackendException(CapabilityCode.IO_ERROR, "等待 yarn logs 并发槽位时被中断");
        }
    }

    private String resolveByJobName(String jobName) {
        JsonNode root = rmClient.getJson("/ws/v1/cluster/apps?name=" + Urls.encode(jobName));
        JsonNode apps = root.path("apps").path("app");
        List<JsonNode> candidates = new ArrayList<>();
        if (apps.isArray()) {
            apps.forEach(candidates::add);
        } else if (apps.isObject()) {
            candidates.add(apps);
        }
        JsonNode best = null;
        long bestStart = Long.MIN_VALUE;
        for (JsonNode app : candidates) {
            if (!jobName.equals(app.path("name").asText())) {
                continue;
            }
            long started = app.path("startedTime").asLong(0L);
            if (started > bestStart) {
                best = app;
                bestStart = started;
            }
        }
        String id = best == null ? "" : best.path("id").asText("");
        if (id.isBlank()) {
            throw new BackendException(CapabilityCode.NOT_FOUND,
                    "YARN 未找到名称为 " + jobName + " 的任务，请改用 ref.taskId");
        }
        return id;
    }

    private BackendException cliFailure(CommandOutput output) {
        String stderr = output.stderr() == null ? "" : output.stderr().trim();
        String lower = stderr.toLowerCase(Locale.ROOT);
        CapabilityCode code;
        if (lower.contains("applicationnotfound") || lower.contains("application not found")
                || lower.contains("not found")) {
            code = CapabilityCode.NOT_FOUND;
        } else if (lower.contains("accesscontrol") || lower.contains("unauthorized")
                || lower.contains("permission denied") || lower.contains("403")) {
            code = CapabilityCode.UPSTREAM_UNAUTHORIZED;
        } else {
            code = CapabilityCode.UPSTREAM_ERROR;
        }
        String detail = stderr.length() > 300 ? stderr.substring(0, 300) : stderr;
        return new BackendException(code, "yarn logs 失败（exit=" + output.exitCode()
                + "）: " + (detail.isBlank() ? "无 stderr 输出" : detail));
    }

    private static boolean indicatesApplicationMissing(String stderr) {
        String lower = stderr == null ? "" : stderr.toLowerCase(Locale.ROOT);
        return lower.contains("applicationnotfound") || lower.contains("application not found");
    }

    private List<String> command(String appId, String stream) {
        List<String> command = new ArrayList<>();
        command.add(config.getCommand());
        if (config.getExtraArgs() != null) {
            command.addAll(config.getExtraArgs());
        }
        command.add("logs");
        command.add("-applicationId");
        command.add(appId);
        command.add("-log_files");
        command.add(stream);
        return command;
    }

    private static String taskId(LogQuerySpec spec) {
        return spec.refs().get("taskId");
    }

    private static String jobName(LogQuerySpec spec) {
        return spec.refs().get("jobName");
    }


    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "UNAVAILABLE" : value;
    }

    private static String hostOf(String nodeHttpAddress) {
        if (Strings.isBlank(nodeHttpAddress)) {
            return "";
        }
        String value = nodeHttpAddress;
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        int colon = value.indexOf(':');
        return colon < 0 ? value : value.substring(0, colon);
    }

    private static Bounded readBounded(InputStream input, long max) throws IOException {
        try (InputStream in = input) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            long total = 0;
            boolean truncated = false;
            int read;
            while ((read = in.read(chunk)) >= 0) {
                if (total >= max) {
                    truncated = true;
                    continue;
                }
                int allowed = (int) Math.min(read, max - total);
                buffer.write(chunk, 0, allowed);
                total += allowed;
                if (allowed < read) {
                    truncated = true;
                }
            }
            return new Bounded(buffer.toByteArray(), truncated);
        }
    }

    // ------------------------------------------------------------------ 数据结构

    private static final class Collect {
        private final List<LogEntry> entries = new ArrayList<>();
        private final List<BackendWarning> warnings = new ArrayList<>();
        private long lines;
        private long bytes;
        private int files;
        private boolean truncated;
        private boolean blocksSeen;
        private boolean incomplete;
        private boolean limitHit;

        private boolean hasEntries() {
            return !entries.isEmpty();
        }

        private void add(LogEntry entry, int limit) {
            if (entries.size() >= limit) {
                limitHit = true;
                return;
            }
            entries.add(entry);
        }

        private LogQueryResult toResult() {
            return new LogQueryResult(entries, null, truncated || limitHit,
                    new LogQueryResult.ScanStats(files, bytes, lines, entries.size()), warnings);
        }
    }

    private record NodeLocalRead(String failureReason, int containers, int readableFiles) {
    }

    private record AggregatedParse(List<ContainerBlock> blocks, long lines) {
    }

    private static final class ContainerBlock {
        private final String containerId;
        private final String nmHost;
        private final List<String> lines = new ArrayList<>();
        private String aggregationType = "";
        private boolean partial;

        private ContainerBlock(String containerId, String nmHost) {
            this.containerId = containerId;
            this.nmHost = nmHost;
        }

        private String containerId() {
            return containerId;
        }

        private String nmHost() {
            return nmHost;
        }

        private String aggregationType() {
            return aggregationType;
        }

        private boolean partial() {
            return partial;
        }

        private List<String> lines() {
            return lines;
        }
    }

    private record Bounded(byte[] bytes, boolean truncated) {
    }

    /** 生产实现：直接读取子进程 stdout，受字节上限与超时约束，不写临时文件。 */
    static final class ProcessCommandRunner implements CommandRunner {

        @Override
        public CommandOutput run(List<String> command, Duration timeout, long maxBytes)
                throws CommandTimeoutException, IOException {
            Process process;
            try {
                process = new ProcessBuilder(command).redirectErrorStream(false).start();
            } catch (IOException e) {
                throw new IOException("无法启动命令 " + command.get(0) + ": " + e.getMessage(), e);
            }
            ExecutorService pool = Executors.newFixedThreadPool(2, task -> {
                Thread thread = new Thread(task, "yarn-logs-io");
                thread.setDaemon(true);
                return thread;
            });
            try {
                Future<Bounded> stdout = pool.submit(
                        () -> readBounded(process.getInputStream(), maxBytes));
                Future<Bounded> stderr = pool.submit(
                        () -> readBounded(process.getErrorStream(), STDERR_CAPTURE_BYTES));
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                    throw new CommandTimeoutException(
                            "yarn logs 超时 " + timeout.toMillis() + "ms（max-bytes=" + maxBytes + "）");
                }
                Bounded out = stdout.get(5, TimeUnit.SECONDS);
                Bounded err = stderr.get(5, TimeUnit.SECONDS);
                return new CommandOutput(process.exitValue(),
                        new String(out.bytes(), StandardCharsets.UTF_8),
                        new String(err.bytes(), StandardCharsets.UTF_8),
                        out.truncated(), out.bytes().length);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("执行 yarn logs 时被中断", e);
            } catch (ExecutionException | TimeoutException e) {
                throw new IOException("读取 yarn logs 输出失败: " + e.getMessage(), e);
            } finally {
                pool.shutdownNow();
                process.destroy();
            }
        }
    }
}
