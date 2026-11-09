package com.bigdata.modules.integration.service.submit;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 执行集群命令行的统一入口。
 *
 * <p>安全约定：命令以 <b>argv 列表</b> 形式交给 {@link ProcessBuilder} 直接 exec，
 * 不经过 shell（没有 {@code sh -c}），因此任务参数里即使包含 {@code ;}、{@code |}、{@code $()}
 * 也不会被解释；可执行文件路径只来自配置，不会被任务参数覆盖。</p>
 *
 * <p>stdout / stderr 分别采集（各自上限 256KB），超时则强制终止进程并把 timedOut 置位，
 * 保证调用方能把「命令、退出码、关键输出」原样写进提交日志。</p>
 */
@Component
public class CliCommandRunner {

    private static final int MAX_CAPTURE_CHARS = 256 * 1024;
    private static final long STREAM_DRAIN_TIMEOUT_MS = 3000L;

    public CliResult run(List<String> command, Path workDir, long timeoutMs) {
        long startedAt = System.currentTimeMillis();
        if (command == null || command.isEmpty()) {
            return CliResult.notStarted(List.of(), "命令行为空", 0);
        }
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        if (workDir != null) {
            builder.directory(workDir.toFile());
        }
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return CliResult.notStarted(command, e.getMessage(), System.currentTimeMillis() - startedAt);
        }

        CompletableFuture<String> stdout = drain(process.getInputStream());
        CompletableFuture<String> stderr = drain(process.getErrorStream());

        boolean finished;
        try {
            if (timeoutMs > 0) {
                finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            } else {
                process.waitFor();
                finished = true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return CliResult.notStarted(command, "命令执行被中断", System.currentTimeMillis() - startedAt);
        }
        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        int exitCode = finished ? process.exitValue() : -1;
        String out = awaitQuietly(stdout);
        String err = awaitQuietly(stderr);
        return CliResult.executed(command, exitCode, !finished, out, err,
                System.currentTimeMillis() - startedAt);
    }

    /** 把参数模板里的占位符替换掉（逐个元素替换，不拼接成一条 shell 字符串） */
    public static List<String> expand(List<String> template, Map<String, String> variables) {
        List<String> args = new ArrayList<>();
        if (template == null) {
            return args;
        }
        for (String item : template) {
            String value = item;
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                value = value.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
            }
            args.add(value);
        }
        return args;
    }

    /** 日志里展示用的命令行（带引号，避免空格参数看不清） */
    public static String describe(List<String> command) {
        StringBuilder sb = new StringBuilder();
        for (String arg : command) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (arg == null) {
                sb.append("\"\"");
            } else if (arg.isEmpty() || arg.chars().anyMatch(Character::isWhitespace)) {
                sb.append('"').append(arg).append('"');
            } else {
                sb.append(arg);
            }
        }
        return sb.toString();
    }

    private CompletableFuture<String> drain(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    if (sb.length() < MAX_CAPTURE_CHARS) {
                        sb.append(buffer, 0, read);
                    }
                }
            } catch (IOException ignored) {
                // 进程被强杀时流会中断，已采集到的内容仍然有效
            }
            return sb.toString();
        });
    }

    private String awaitQuietly(CompletableFuture<String> future) {
        try {
            return future.get(STREAM_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return "";
        }
    }
}
