package com.bigdata.modules.integration.service.submit;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.integration.config.IntegrationClusterProperties;
import com.bigdata.modules.integration.enums.ClusterType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Flink Standalone 集群提交适配器（命令行实现）。
 *
 * <p>提交链路：任务先翻译为 Flink SQL，落盘为 .sql 文件，再调用
 * {@code {flink.home}/bin/sql-client.sh -f job.sql} 提交到远端集群
 * （通过 {@code -Dexecution.target=remote -Drest.address=... -Drest.port=...} 指向上线时配置的集群）。</p>
 *
 * <p>取消 / 状态查询：{@code {flink.home}/bin/flink cancel|list}。
 * 命令行能力边界：
 * <ul>
 *   <li>{@code flink list -r} 只能看到「会话集群 / 远端集群」上的作业，application 模式下需要
 *       自行在 {@code status-args} / {@code cancel-args} 中补充 {@code -t yarn-application} 等参数；</li>
 *   <li>作业被清理后命令行查不到，此时状态返回 NOT_FOUND（而不是伪造成功）。</li>
 * </ul>
 *
 * <p>安全：命令以 argv 列表直接 exec，不经过 shell；可执行文件路径只来自配置，
 * 任务参数（表名、连接串等）只会进入生成的 SQL 文件与固定的参数位置，不会被当作命令解析。</p>
 */
@Component
public class FlinkStandaloneSubmitter implements DataIntegrationSubmitter {

    private static final Logger log = LoggerFactory.getLogger(FlinkStandaloneSubmitter.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final String SQL_CLIENT = "sql-client";

    @Autowired
    private IntegrationClusterProperties properties;

    @Autowired
    private CliCommandRunner runner;

    @Override
    public ClusterType clusterType() {
        return ClusterType.FLINK_STANDALONE;
    }

    @Override
    public SubmitResult submit(SubmitContext context) {
        IntegrationClusterProperties.Flink config = properties.getFlink();
        int readParallelism = resolveParallelism(context.getReadParallelism(), config.getDefaultParallelism());
        int writeParallelism = resolveParallelism(context.getWriteParallelism(), config.getDefaultParallelism());
        String sql = FlinkSqlBuilder.build(context, config, readParallelism, writeParallelism);
        context.setGeneratedConfig(sql);

        String bin = resolveSubmitBin(config);
        Path workDir = resolveWorkDir(config);
        Path sqlFile = workDir.resolve("dataforge-" + safeName(context.getTaskCode()) + "-"
                + LocalDateTime.now().format(TS) + ".sql");
        try {
            Files.createDirectories(workDir);
            Files.writeString(sqlFile, sql, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException(500, "写入 Flink SQL 文件失败：" + sqlFile + "，" + e.getMessage());
        }

        List<String> command = new ArrayList<>();
        command.add(bin);
        List<String> submitArgs = config.getSubmitArgs() == null ? List.of() : config.getSubmitArgs();
        if (submitArgs.isEmpty()) {
            if (!isSqlClient(bin)) {
                throw new BusinessException(503,
                        "使用非 sql-client 的可执行文件提交 Flink SQL 时，必须显式配置 integration.flink.submit-args"
                                + "（当前可执行文件：" + bin + "）");
            }
            command.add("-f");
            command.add(sqlFile.toString());
        } else {
            command.addAll(CliCommandRunner.expand(submitArgs, Map.of("sqlFile", sqlFile.toString())));
        }
        command.addAll(remoteTargetArgs(config, bin));

        CliResult result = runner.run(command, workDir, config.getSubmitTimeoutMs());
        StringBuilder submitLog = new StringBuilder();
        submitLog.append("===== 生成的 Flink SQL =====\n").append(sql).append('\n');
        submitLog.append("===== 执行命令 =====\n").append(result.getCommandLine()).append('\n');
        submitLog.append("===== 执行结果 =====\n");
        submitLog.append("exitCode=").append(result.getExitCode())
                .append(", timedOut=").append(result.isTimedOut())
                .append(", durationMs=").append(result.getDurationMs()).append('\n');
        if (!result.getStdout().isBlank()) {
            submitLog.append("----- stdout -----\n").append(result.getStdout().trim()).append('\n');
        }
        if (!result.getStderr().isBlank()) {
            submitLog.append("----- stderr -----\n").append(result.getStderr().trim()).append('\n');
        }

        if (!result.isSuccess()) {
            throw new BusinessException(502, "Flink 作业提交失败：" + result.failureSummary(4000));
        }
        // sql-client 在 -f 模式下遇到 SQL 语句错误（如 connector 参数不支持）仍会以退出码 0 结束，
        // 必须显式识别，否则会把「一条语句都没提交」误判为上线成功。
        String output = result.combinedOutput();
        int sqlError = output.indexOf("Could not execute SQL statement");
        if (sqlError >= 0) {
            throw new BusinessException(502, "Flink SQL 执行失败："
                    + output.substring(sqlError, Math.min(output.length(), sqlError + 1500)).trim());
        }
        String jobId = JobIdParser.parseFlink(result.combinedOutput());
        if (jobId == null) {
            log.warn("Flink 提交命令执行成功，但未从输出解析到 jobId: task={}, cmd={}",
                    context.getTaskCode(), result.getCommandLine());
            submitLog.append("===== 提示 =====\n命令执行成功，但未从输出解析到 jobId；"
                    + "请到 Flink Web UI / flink list 确认作业是否已运行。\n");
            return new SubmitResult(null, "已提交至 Flink（未解析到 jobId，请到集群侧确认）", submitLog.toString());
        }
        submitLog.append("===== 解析结果 =====\njobId=").append(jobId).append('\n');
        log.info("Flink 作业已通过命令行提交: task={}, jobId={}", context.getTaskCode(), jobId);
        return new SubmitResult(jobId, "已通过命令行提交至 Flink Standalone 集群", submitLog.toString());
    }

    @Override
    public SubmitResult cancel(SubmitContext context) {
        if (isBlank(context.getJobId())) {
            throw new BusinessException(400, "任务没有已提交的 jobId，无法下线");
        }
        IntegrationClusterProperties.Flink config = properties.getFlink();
        String bin = resolveManageBin(config);

        List<String> template = config.getCancelArgs() == null ? List.of() : config.getCancelArgs();
        if (template.isEmpty()) {
            throw new BusinessException(503,
                    "未配置 Flink 取消命令参数（integration.flink.cancel-args），无法通过命令行取消作业");
        }
        Map<String, String> vars = Map.of("jobId", context.getJobId());
        List<String> args = CliCommandRunner.expand(template, vars);
        if (template.stream().noneMatch(item -> item.contains("{jobId}"))) {
            args.add(context.getJobId());
        }

        List<String> command = new ArrayList<>();
        command.add(bin);
        command.addAll(withClusterManager(config, args));

        CliResult result = runner.run(command, resolveWorkDir(config), config.getTimeoutMs());
        String logText = "===== 执行命令 =====\n" + result.getCommandLine() + "\n"
                + "===== 执行结果 =====\nexitCode=" + result.getExitCode()
                + ", timedOut=" + result.isTimedOut() + "\n"
                + (result.combinedOutput().isEmpty() ? "" : result.combinedOutput() + "\n");
        if (!result.isSuccess()) {
            throw new BusinessException(502, "Flink 作业取消失败：" + result.failureSummary(4000));
        }
        log.info("Flink 作业已通过命令行取消: jobId={}", context.getJobId());
        return new SubmitResult(context.getJobId(), "已通过命令行取消 Flink 作业", logText);
    }

    @Override
    public ClusterJobStatus queryStatus(SubmitContext context) {
        if (isBlank(context.getJobId())) {
            return ClusterJobStatus.reachable(null, "任务尚未提交，无运行实例");
        }
        IntegrationClusterProperties.Flink config = properties.getFlink();
        String bin = resolveManageBin(config);
        List<String> template = config.getStatusArgs() == null ? List.of() : config.getStatusArgs();
        if (template.isEmpty()) {
            return ClusterJobStatus.unreachable(
                    "未配置 Flink 状态查询命令参数（integration.flink.status-args），无法查询运行状态");
        }
        List<String> command = new ArrayList<>();
        command.add(bin);
        command.addAll(withClusterManager(config,
                CliCommandRunner.expand(template, Map.of("jobId", context.getJobId()))));

        CliResult result = runner.run(command, resolveWorkDir(config), config.getTimeoutMs());
        if (!result.isStarted()) {
            return ClusterJobStatus.unreachable("执行状态查询命令失败：" + result.failureSummary(1200));
        }
        if (result.isTimedOut()) {
            return ClusterJobStatus.unreachable("状态查询命令超时：" + result.getCommandLine());
        }
        if (result.getExitCode() != 0) {
            return ClusterJobStatus.unreachable("状态查询命令返回非 0：" + result.failureSummary(1200));
        }
        String state = JobIdParser.findFlinkState(result.combinedOutput(), context.getJobId());
        if (state == null) {
            return ClusterJobStatus.reachable("NOT_FOUND",
                    "flink list 中未找到该作业（可能已结束或被清理），jobId=" + context.getJobId());
        }
        return ClusterJobStatus.reachable(state, "Flink 作业状态: " + state + "（通过 flink list 获取）");
    }

    /** sql-client 需要显式指向远端集群；其他可执行文件按用户配置的参数执行 */
    private List<String> remoteTargetArgs(IntegrationClusterProperties.Flink config, String bin) {
        if (!isSqlClient(bin) || isBlank(config.getRestUrl())) {
            return List.of();
        }
        URI uri = parseUri(config.getRestUrl());
        if (uri == null) {
            return List.of();
        }
        String target = isBlank(config.getExecutionTarget()) ? "remote" : config.getExecutionTarget().trim();
        List<String> args = new ArrayList<>();
        args.add("-Dexecution.target=" + target);
        args.add("-Drest.address=" + uri.getHost());
        if (uri.getPort() > 0) {
            args.add("-Drest.port=" + uri.getPort());
        }
        return args;
    }

    /**
     * flink CLI 要求第一个参数是动作（run/list/cancel/...），-m 必须放在动作之后，
     * 否则会报「"-m" is not a valid action」。这里把 -m host:port 插到动作后面。
     */
    private List<String> withClusterManager(IntegrationClusterProperties.Flink config, List<String> actionArgs) {
        List<String> managerArgs = clusterManagerArgs(config);
        List<String> merged = new ArrayList<>();
        if (actionArgs.isEmpty()) {
            merged.addAll(managerArgs);
            return merged;
        }
        merged.add(actionArgs.get(0));
        merged.addAll(managerArgs);
        merged.addAll(actionArgs.subList(1, actionArgs.size()));
        return merged;
    }

    /** flink cancel / list 通过 -m host:port 指定 JobManager */
    private List<String> clusterManagerArgs(IntegrationClusterProperties.Flink config) {
        if (isBlank(config.getRestUrl())) {
            return List.of();
        }
        URI uri = parseUri(config.getRestUrl());
        if (uri == null || uri.getHost() == null) {
            return List.of();
        }
        return List.of("-m", uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost());
    }

    private String resolveSubmitBin(IntegrationClusterProperties.Flink config) {
        String bin = config.getSubmitBin();
        if (isBlank(bin) && !isBlank(config.getHome())) {
            bin = Paths.get(config.getHome(), "bin", "sql-client.sh").toString();
        }
        return requireExecutable(bin,
                "未配置 Flink 提交命令（integration.flink.submit-bin 或 integration.flink.home）");
    }

    private String resolveManageBin(IntegrationClusterProperties.Flink config) {
        String bin = config.getManageBin();
        if (isBlank(bin) && !isBlank(config.getHome())) {
            bin = Paths.get(config.getHome(), "bin", "flink").toString();
        }
        return requireExecutable(bin,
                "未配置 Flink 管理命令（integration.flink.manage-bin 或 integration.flink.home）");
    }

    private String requireExecutable(String bin, String missingHint) {
        if (isBlank(bin)) {
            throw new BusinessException(503, missingHint + "，无法通过命令行操作 Flink 作业");
        }
        Path path = Paths.get(bin);
        if (!Files.isExecutable(path)) {
            throw new BusinessException(503, "Flink 可执行文件不存在或不可执行：" + bin
                    + "（请检查 integration.flink.home / submit-bin / manage-bin）");
        }
        return path.toString();
    }

    private Path resolveWorkDir(IntegrationClusterProperties.Flink config) {
        String dir = config.getWorkDir();
        if (isBlank(dir)) {
            dir = isBlank(config.getHome())
                    ? Paths.get(System.getProperty("java.io.tmpdir"), "dataforge-integration").toString()
                    : Paths.get(config.getHome(), "tmp").toString();
        }
        return Paths.get(dir);
    }

    private URI parseUri(String url) {
        try {
            return URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String safeName(String value) {
        if (value == null || value.isBlank()) {
            return "job";
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private int resolveParallelism(Integer value, int fallback) {
        return value != null && value > 0 ? value : Math.max(1, fallback);
    }

    private boolean isSqlClient(String bin) {
        return bin != null && bin.toLowerCase().contains(SQL_CLIENT);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
