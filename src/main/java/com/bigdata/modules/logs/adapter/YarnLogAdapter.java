package com.bigdata.modules.logs.adapter;

import com.bigdata.modules.logs.model.ComponentRole;
import com.bigdata.modules.logs.model.LogKind;
import com.bigdata.modules.logs.model.LogSource;
import com.bigdata.modules.logs.model.ServiceType;
import com.bigdata.modules.logs.service.LogExecutorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * YARN 聚合日志适配器：{@code yarn logs -applicationId ...} 异步拉取。
 *
 * <h3>为什么要异步 + 超时</h3>
 * <p>聚合日志需要 RM / NM 侧合并多个容器日志，耗时常超过普通查询预算；同步调用会把
 * 整个请求线程拖住。这里提交到<b>该日志源专属线程池</b>，最多等待
 * {@code min(20s 查询预算, options.command-timeout-ms)}，超时立即取消并返回降级结果。</p>
 *
 * <p>命令与参数模板全部来自配置，默认 {@code yarn logs -applicationId {appId}}，
 * 不写死任何集群地址。</p>
 */
@Component
public class YarnLogAdapter implements LogAdapter {

    private static final Logger log = LoggerFactory.getLogger(YarnLogAdapter.class);

    private final CommandRunner commandRunner;
    private final LogExecutorRegistry executorRegistry;

    public YarnLogAdapter(CommandRunner commandRunner, LogExecutorRegistry executorRegistry) {
        this.commandRunner = commandRunner;
        this.executorRegistry = executorRegistry;
    }

    @Override
    public boolean supports(ServiceType serviceType, LogKind logKind, ComponentRole role) {
        if (serviceType != ServiceType.YARN) {
            return false;
        }
        if (logKind != LogKind.AGGREGATE && logKind != LogKind.RUNTIME) {
            return false;
        }
        return role == ComponentRole.RESOURCE_MANAGER || role == ComponentRole.APPLICATION_MASTER
                || role == ComponentRole.GENERIC;
    }

    @Override
    public AdapterResult query(LogQueryContext context) {
        String applicationId = context.ref("applicationId", "appId", "application");
        if (applicationId == null) {
            throw new LogAdapterException(LogAdapterException.Code.BAD_REQUEST,
                    "缺少 ref.applicationId");
        }
        if (context.getOffset() > 0) {
            throw new LogAdapterException(LogAdapterException.Code.BAD_REQUEST,
                    "YARN 聚合日志不支持游标分页，请一次性获取或缩小时间范围后重查");
        }

        LogSource source = context.getSource();
        List<String> command = buildCommand(source, context, applicationId);
        long timeoutMs = Math.min(context.getTimeoutMs(),
                source.getConfig().getOptions().getCommandTimeoutMs());

        Future<CommandResult> future = executorRegistry.get(source.getId())
                .submit(() -> commandRunner.run(command, Duration.ofMillis(Math.max(1000L, timeoutMs))));

        CommandResult result;
        try {
            result = future.get(Math.max(1000L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return AdapterResult.degraded("TIMEOUT",
                    "YARN 聚合日志拉取超时（" + timeoutMs + "ms）：聚合需要 RM/NM 合并日志，"
                            + "可稍后重试或改为按容器读取 NodeManager 日志");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return AdapterResult.degraded("TIMEOUT", "YARN 聚合日志拉取被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("YARN 聚合日志执行失败: {}", cause.getMessage());
            return AdapterResult.degraded("UNAVAILABLE",
                    "YARN 聚合日志执行失败: " + cause.getMessage());
        }

        if (result.isTimedOut()) {
            return AdapterResult.degraded("TIMEOUT",
                    "YARN 聚合日志拉取超时（" + timeoutMs + "ms）");
        }
        if (!result.isSuccess()) {
            String stderr = result.getStderr() == null ? "" : result.getStderr().trim();
            String lower = stderr.toLowerCase(Locale.ROOT);
            if (lower.contains("not completed") || lower.contains("aggregation")) {
                return AdapterResult.degraded("AGGREGATION_NOT_READY",
                        "YARN 日志聚合尚未完成，请稍后重试: " + abbreviate(stderr));
            }
            throw new LogAdapterException(LogAdapterException.Code.FAILED,
                    "yarn logs 执行失败 (exit=" + result.getExitCode() + "): " + abbreviate(stderr));
        }

        BudgetLimiter.Result budget = BudgetLimiter.limitText(result.getStdout(),
                context.getMaxLines(), context.getMaxBytes());
        AdapterResult adapterResult = AdapterResult.of(budget.getContent(), budget.getLines().size(), 0L,
                budget.isTruncated());
        adapterResult.setTruncated(budget.isTruncated());
        return adapterResult;
    }

    private List<String> buildCommand(LogSource source, LogQueryContext context, String applicationId) {
        List<String> command = new ArrayList<>();
        String executable = source.getConfig().getOptions().getCommand();
        command.add(executable == null || executable.isBlank() ? "yarn" : executable);

        List<String> templates = source.getConfig().getOptions().getCommandArgs();
        if (templates == null || templates.isEmpty()) {
            templates = List.of("logs", "-applicationId", "{appId}");
        }
        String containerId = context.ref("containerId");
        String logFiles = context.ref("logFiles", "logFile");
        for (String template : templates) {
            String value = template == null ? "" : template;
            value = value.replace("{appId}", applicationId);
            value = value.replace("{applicationId}", applicationId);
            if (containerId != null) {
                value = value.replace("{containerId}", containerId);
            }
            if (logFiles != null) {
                value = value.replace("{logFiles}", logFiles);
            }
            if (!value.isBlank()) {
                command.add(value);
            }
        }
        return command;
    }

    private String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }
}
