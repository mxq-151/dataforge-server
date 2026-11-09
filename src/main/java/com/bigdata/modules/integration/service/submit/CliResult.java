package com.bigdata.modules.integration.service.submit;

import java.util.List;

/**
 * 一次命令行执行的结果（命令行、退出码、stdout/stderr、是否超时）。
 *
 * <p>stdout / stderr 各自最多保留 256KB，避免异常输出把内存打满；
 * 提交日志与错误信息都基于这里的内容拼装。</p>
 */
public class CliResult {

    private final List<String> command;
    private final boolean started;
    private final int exitCode;
    private final boolean timedOut;
    private final String stdout;
    private final String stderr;
    private final String startError;
    private final long durationMs;

    private CliResult(List<String> command, boolean started, int exitCode, boolean timedOut,
                      String stdout, String stderr, String startError, long durationMs) {
        this.command = command;
        this.started = started;
        this.exitCode = exitCode;
        this.timedOut = timedOut;
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
        this.startError = startError;
        this.durationMs = durationMs;
    }

    public static CliResult executed(List<String> command, int exitCode, boolean timedOut,
                                     String stdout, String stderr, long durationMs) {
        return new CliResult(command, true, exitCode, timedOut, stdout, stderr, null, durationMs);
    }

    public static CliResult notStarted(List<String> command, String error, long durationMs) {
        return new CliResult(command, false, -1, false, "", "", error, durationMs);
    }

    public List<String> getCommand() {
        return command;
    }

    public boolean isStarted() {
        return started;
    }

    public int getExitCode() {
        return exitCode;
    }

    public boolean isTimedOut() {
        return timedOut;
    }

    public String getStdout() {
        return stdout;
    }

    public String getStderr() {
        return stderr;
    }

    public String getStartError() {
        return startError;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public boolean isSuccess() {
        return started && !timedOut && exitCode == 0;
    }

    /** 便于阅读的命令行表示（含引号，仅用于日志/错误信息，不参与执行） */
    public String getCommandLine() {
        return CliCommandRunner.describe(command);
    }

    /** stdout + stderr 合并后的可读输出 */
    public String combinedOutput() {
        StringBuilder sb = new StringBuilder();
        if (!stdout.isBlank()) {
            sb.append(stdout.trim());
        }
        if (!stderr.isBlank()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("[stderr]\n").append(stderr.trim());
        }
        return sb.toString();
    }

    /** 失败时的可读摘要：命令 + 退出码 + 关键输出 */
    public String failureSummary(int maxOutput) {
        StringBuilder sb = new StringBuilder();
        sb.append("命令: ").append(getCommandLine());
        if (!started) {
            sb.append("\n启动失败: ").append(startError == null ? "未知原因" : startError);
            return sb.toString();
        }
        sb.append("\n退出码: ").append(exitCode);
        if (timedOut) {
            sb.append("（执行超时，已强制终止）");
        }
        String output = combinedOutput();
        if (!output.isEmpty()) {
            sb.append("\n输出:\n").append(tail(output, maxOutput));
        }
        return sb.toString();
    }

    private static String tail(String text, int max) {
        if (max <= 0 || text.length() <= max) {
            return text;
        }
        return "...(截断)\n" + text.substring(text.length() - max);
    }
}
