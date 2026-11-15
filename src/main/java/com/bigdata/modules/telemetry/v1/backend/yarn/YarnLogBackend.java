package com.bigdata.modules.telemetry.v1.backend.yarn;

import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.Requirement;
import com.bigdata.modules.telemetry.model.RequirementKind;
import com.bigdata.modules.telemetry.v1.backend.engine.EngineRestClient;
import com.bigdata.modules.telemetry.v1.config.TelemetryBackendProperties.YarnConfig;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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

/**
 * YARN 聚合日志后端：只承载 {@code log.task}。
 *
 * <p>实现选择：日志正文走 {@code yarn logs -applicationId ... -log_files stdout|stderr} CLI，
 * 因为 YARN 聚合日志由 NodeManager 写入远端存储，RM 只有元数据、NM 的 {@code /logs} 只覆盖本地
 * 未聚合文件，HTTP 没有等价的聚合日志读取接口。{@code ref.jobName} 到 applicationId 的解析
 * 走 RM REST，复用 {@link EngineRestClient} 的错误映射（404 / 401 / 429 / 5xx 等）。</p>
 *
 * <p>CLI 约束：单次调用有超时，进程被 {@code destroyForcibly} 终止；stdout 直接读入内存并受
 * {@code maxBytes} 限制，不落临时文件；同时运行的子进程数由信号量限制，拿不到槽位返回
 * {@code RATE_LIMITED}。</p>
 *
 * <p>YARN 聚合日志没有统一的行首时间戳，也不支持游标分页；带时间范围或 cursor 的查询明确返回
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

    private final String backendId;
    private final YarnConfig config;
    private final EngineRestClient rmClient;
    private final CommandRunner runner;
    private final Semaphore permits;

    public YarnLogBackend(String backendId, YarnConfig config, String authorizationHeader,
                          ObjectMapper objectMapper) {
        this(backendId, config,
                isBlank(config.getRmBaseUrl()) ? null
                        : new EngineRestClient(config.getRmBaseUrl(), authorizationHeader,
                        Duration.ofMillis(Math.max(config.getLimits().getTimeoutMs(), 1000)),
                        8388608L, objectMapper),
                new ProcessCommandRunner());
    }

    YarnLogBackend(String backendId, YarnConfig config, EngineRestClient rmClient,
                   CommandRunner runner) {
        this.backendId = backendId;
        this.config = config;
        this.rmClient = rmClient;
        this.runner = runner;
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
                    "YARN 聚合日志只支持 stdout / stderr，不支持 " + spec.stream(), List.of());
        }
        if (spec.cursor() != null && !spec.cursor().isBlank()) {
            return BackendCapability.unavailable(CapabilityCode.INVALID_ARGUMENT,
                    "yarn logs 不支持游标分页，cursor 必须为空", List.of());
        }
        if (spec.timeRange() != null
                && (spec.timeRange().start() != null || spec.timeRange().end() != null)) {
            return BackendCapability.unavailable(CapabilityCode.TIME_FILTER_UNSUPPORTED,
                    "YARN 聚合日志没有统一的行首时间戳，不支持时间范围过滤",
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "telemetry.backends.yarn[].sources[].timestamp-pattern",
                            "如需时间过滤，请把聚合日志接入 local-disk / ES 并配置时间戳格式")));
        }
        if (isBlank(config.getCommand())) {
            return BackendCapability.unavailable(CapabilityCode.NOT_CONFIGURED,
                    "未配置 yarn 可执行文件",
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "telemetry.backends.yarn[].command", "配置 yarn 命令或绝对路径")));
        }
        if (isBlank(taskId(spec)) && isBlank(jobName(spec))) {
            return BackendCapability.unavailable(CapabilityCode.INVALID_ARGUMENT,
                    "缺少必填引用: ref.taskId / ref.jobName（至少提供一个）", List.of());
        }
        if (isBlank(taskId(spec)) && rmClient == null) {
            return BackendCapability.unavailable(CapabilityCode.NOT_CONFIGURED,
                    "只给了 ref.jobName，但未配置 YARN RM 地址，无法解析 applicationId",
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "telemetry.backends.yarn[].rm-base-url",
                            "配置 RM REST 地址，或直接传 ref.taskId")));
        }
        return BackendCapability.available();
    }

    @Override
    public LogQueryResult fetch(LogQuerySpec spec) {
        String appId = taskId(spec);
        if (isBlank(appId)) {
            appId = resolveByJobName(jobName(spec));
        }
        LogFilterEngine filter;
        try {
            filter = LogFilterEngine.compile(spec.filter());
        } catch (IllegalArgumentException e) {
            throw new BackendException(CapabilityCode.INVALID_ARGUMENT, e.getMessage());
        }

        long maxBytes = spec.maxBytes() > 0
                ? Math.min(spec.maxBytes(), config.getLimits().getMaxBytes())
                : config.getLimits().getMaxBytes();
        Duration timeout = spec.timeout() != null ? spec.timeout()
                : Duration.ofMillis(config.getLimits().getTimeoutMs());

        acquirePermit();
        CommandOutput output;
        try {
            output = runner.run(command(appId, spec.stream()), timeout, maxBytes);
        } catch (CommandTimeoutException e) {
            throw new BackendException(CapabilityCode.UPSTREAM_TIMEOUT, e.getMessage());
        } catch (IOException e) {
            throw new BackendException(CapabilityCode.IO_ERROR,
                    "执行 yarn logs 失败: " + e.getMessage());
        } finally {
            permits.release();
        }
        if (output.exitCode() != 0) {
            throw cliFailure(output);
        }

        int limit = spec.limit() > 0 ? spec.limit() : 500;
        List<LogEntry> entries = new ArrayList<>();
        long lines = 0;
        for (String line : output.stdout().split("\\r?\\n", -1)) {
            if (line.isBlank()) {
                continue;
            }
            lines++;
            if (filter.matches(line)) {
                entries.add(new LogEntry(null, spec.stream(), spec.target().hostId(), line, Map.of()));
            }
        }
        boolean truncated = output.truncated() || entries.size() > limit;
        if (entries.size() > limit) {
            entries = new ArrayList<>(entries.subList(0, limit));
        }
        List<BackendWarning> warnings = truncated
                ? List.of(BackendWarning.of("OUTPUT_TRUNCATED",
                "yarn logs 输出超过 maxBytes / limit，结果已截断且无法续页"))
                : List.of();
        return new LogQueryResult(entries, null, truncated,
                new LogQueryResult.ScanStats(0, output.stdoutBytes(), lines, entries.size()), warnings);
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
        JsonNode root = rmClient.getJson("/ws/v1/cluster/apps?name=" + encode(jobName));
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

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
                        () -> read(process.getInputStream(), maxBytes));
                Future<Bounded> stderr = pool.submit(
                        () -> read(process.getErrorStream(), STDERR_CAPTURE_BYTES));
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

        private static Bounded read(InputStream input, long max) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            long total = 0;
            boolean truncated = false;
            int read;
            while ((read = input.read(chunk)) >= 0) {
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

    private record Bounded(byte[] bytes, boolean truncated) {
    }
}
