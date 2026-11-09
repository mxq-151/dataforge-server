package com.bigdata.modules.integration.service.submit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令行执行器的最小验证：不依赖真实 Flink 集群，仅确认
 * 「不走 shell、能拿到退出码与输出、超时可控、占位符替换正确」。
 */
class CliCommandRunnerTest {

    private final CliCommandRunner runner = new CliCommandRunner();

    @Test
    void 参数按字面量传递_不被shell解释(@TempDir Path dir) {
        CliResult result = runner.run(
                List.of("/bin/echo", "a;echo hacked", "$(whoami)"), dir, 5000);

        assertTrue(result.isSuccess(), result.failureSummary(500));
        // 如果是通过 shell 执行，"$(whoami)" 会被展开、";" 会把 echo 拆成两条命令
        assertTrue(result.getStdout().contains("a;echo hacked"), result.getStdout());
        assertTrue(result.getStdout().contains("$(whoami)"), result.getStdout());
    }

    @Test
    void 非0退出码与stderr被完整采集(@TempDir Path dir) throws Exception {
        Path script = script(dir.resolve("fail.sh"), "#!/bin/sh\necho 'boom-detail' >&2\nexit 3\n");

        CliResult result = runner.run(List.of(script.toString()), dir, 5000);

        assertFalse(result.isSuccess());
        assertFalse(result.isTimedOut());
        assertEquals(3, result.getExitCode());
        assertTrue(result.getStderr().contains("boom-detail"), result.getStderr());

        String summary = result.failureSummary(1000);
        assertTrue(summary.contains("命令:"), summary);
        assertTrue(summary.contains("退出码: 3"), summary);
        assertTrue(summary.contains("boom-detail"), summary);
    }

    @Test
    void 超时会强制终止进程(@TempDir Path dir) throws Exception {
        Path script = script(dir.resolve("slow.sh"), "#!/bin/sh\nsleep 5\necho done\n");

        long startedAt = System.currentTimeMillis();
        CliResult result = runner.run(List.of(script.toString()), dir, 500);

        assertTrue(result.isTimedOut());
        assertFalse(result.isSuccess());
        assertTrue(System.currentTimeMillis() - startedAt < 4000, "超时后应立即返回");
    }

    @Test
    void 可执行文件不存在时返回启动失败而不是抛异常(@TempDir Path dir) {
        CliResult result = runner.run(List.of("/definitely/not/exists/flink"), dir, 1000);

        assertFalse(result.isStarted());
        assertFalse(result.isSuccess());
        assertTrue(result.failureSummary(500).contains("启动失败"));
    }

    @Test
    void 占位符逐个参数替换() {
        List<String> expanded = CliCommandRunner.expand(
                List.of("--config", "{configFile}", "-n", "{jobName}"),
                Map.of("configFile", "/tmp/a b.conf", "jobName", "job-1"));

        assertEquals(List.of("--config", "/tmp/a b.conf", "-n", "job-1"), expanded);
    }

    private Path script(Path path, String content) throws Exception {
        Files.writeString(path, content, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(path, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        return path;
    }
}
