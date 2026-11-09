package com.bigdata.modules.integration.service.submit;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.integration.config.IntegrationClusterProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flink 命令行提交端到端验证：用可执行的假 {@code sql-client.sh} / {@code flink} 脚本，
 * 在真实进程执行链路上（不是 mock）验证 jobId 解析、日志采集、失败信息与取消 / 状态分支。
 */
class FlinkStandaloneSubmitterTest {

    private static final String JOB_ID = "9d3f1a2b3c4d5e6f7a8b9c0d1e2f3a4b";

    @Test
    void 提交成功时解析jobId并把stdout与stderr写入日志(@TempDir Path dir) throws Exception {
        Path bin = script(dir.resolve("sql-client.sh"),
                "#!/bin/sh\n"
                        + "echo \"Job has been submitted with JobID " + JOB_ID + "\"\n"
                        + "echo 'checkpoint storage disabled' >&2\n"
                        + "exit 0\n");
        Path workDir = Files.createDirectories(dir.resolve("work"));

        FlinkStandaloneSubmitter submitter = submitter(bin, bin, workDir);
        SubmitResult result = submitter.submit(context());

        assertEquals(JOB_ID, result.getJobId());
        String log = result.getLog();
        assertTrue(log.contains("===== 生成的 Flink SQL ====="), log);
        assertTrue(log.contains("INSERT INTO `snk_0`"), log);
        assertTrue(log.contains("===== 执行命令 ====="), log);
        assertTrue(log.contains("-f"), log);
        assertTrue(log.contains("----- stdout -----"), log);
        assertTrue(log.contains("Job has been submitted with JobID " + JOB_ID), log);
        assertTrue(log.contains("----- stderr -----"), log);
        assertTrue(log.contains("checkpoint storage disabled"), log);
        assertTrue(log.contains("exitCode=0"), log);

        try (Stream<Path> files = Files.list(workDir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().endsWith(".sql")),
                    "生成的 SQL 文件应落在配置的工作目录");
        }
    }

    @Test
    void 提交失败时错误信息包含命令退出码与关键输出(@TempDir Path dir) throws Exception {
        Path bin = script(dir.resolve("sql-client.sh"),
                "#!/bin/sh\n"
                        + "echo 'org.apache.flink.util.FlinkException: could not connect to JobManager' >&2\n"
                        + "exit 1\n");
        Path workDir = Files.createDirectories(dir.resolve("work"));

        FlinkStandaloneSubmitter submitter = submitter(bin, bin, workDir);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> submitter.submit(context()));

        assertEquals(502, ex.getCode());
        assertTrue(ex.getMessage().contains("Flink 作业提交失败"), ex.getMessage());
        assertTrue(ex.getMessage().contains("命令: " + bin), ex.getMessage());
        assertTrue(ex.getMessage().contains("退出码: 1"), ex.getMessage());
        assertTrue(ex.getMessage().contains("could not connect to JobManager"), ex.getMessage());
    }

    @Test
    void 提交命令成功但未解析到jobId时如实返回空并提示(@TempDir Path dir) throws Exception {
        Path bin = script(dir.resolve("sql-client.sh"), "#!/bin/sh\necho 'no id here'\nexit 0\n");
        Path workDir = Files.createDirectories(dir.resolve("work"));

        SubmitResult result = submitter(bin, bin, workDir).submit(context());

        assertNull(result.getJobId());
        assertTrue(result.getMessage().contains("未解析到 jobId"), result.getMessage());
        assertTrue(result.getLog().contains("未从输出解析到 jobId"), result.getLog());
    }

    @Test
    void 取消作业时执行flink_cancel命令(@TempDir Path dir) throws Exception {
        Path bin = script(dir.resolve("flink"),
                "#!/bin/sh\n"
                        + "echo \"Canceled job " + JOB_ID + "\"\n"
                        + "exit 0\n");
        Path workDir = Files.createDirectories(dir.resolve("work"));

        DataIntegrationSubmitter.SubmitContext ctx = context();
        ctx.setJobId(JOB_ID);
        SubmitResult result = submitter(bin, bin, workDir).cancel(ctx);

        assertEquals(JOB_ID, result.getJobId());
        assertTrue(result.getLog().contains("cancel"), result.getLog());
        assertTrue(result.getLog().contains(JOB_ID), result.getLog());
        assertTrue(result.getLog().contains("Canceled job"), result.getLog());
    }

    @Test
    void 状态查询从flink_list输出解析RUNNING(@TempDir Path dir) throws Exception {
        Path bin = script(dir.resolve("flink"),
                "#!/bin/sh\n"
                        + "echo '------------------ Running Jobs ------------------'\n"
                        + "echo ' : " + JOB_ID + " : RUNNING : dataforge-job'\n"
                        + "exit 0\n");
        Path workDir = Files.createDirectories(dir.resolve("work"));

        DataIntegrationSubmitter.SubmitContext ctx = context();
        ctx.setJobId(JOB_ID);
        ClusterJobStatus status = submitter(bin, bin, workDir).queryStatus(ctx);

        assertTrue(status.isReachable());
        assertEquals("RUNNING", status.getClusterStatus());
    }

    @Test
    void 状态查询找不到作业时返回NOT_FOUND而不是伪造成功(@TempDir Path dir) throws Exception {
        Path bin = script(dir.resolve("flink"),
                "#!/bin/sh\necho 'No running jobs.'\nexit 0\n");
        Path workDir = Files.createDirectories(dir.resolve("work"));

        DataIntegrationSubmitter.SubmitContext ctx = context();
        ctx.setJobId(JOB_ID);
        ClusterJobStatus status = submitter(bin, bin, workDir).queryStatus(ctx);

        assertTrue(status.isReachable());
        assertEquals("NOT_FOUND", status.getClusterStatus());
    }

    @Test
    void 可执行文件缺失时给出明确的配置错误(@TempDir Path dir) throws Exception {
        Path workDir = Files.createDirectories(dir.resolve("work"));
        Path missing = dir.resolve("not-installed").resolve("sql-client.sh");

        FlinkStandaloneSubmitter submitter = submitter(missing, missing, workDir);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> submitter.submit(context()));

        assertEquals(503, ex.getCode());
        assertTrue(ex.getMessage().contains("不存在或不可执行"), ex.getMessage());
        assertFalse(ex.getMessage().contains("java.io.IOException"), ex.getMessage());
    }

    private FlinkStandaloneSubmitter submitter(Path submitBin, Path manageBin, Path workDir) {
        IntegrationClusterProperties properties = new IntegrationClusterProperties();
        IntegrationClusterProperties.Flink flink = properties.getFlink();
        flink.setSubmitBin(submitBin.toString());
        flink.setManageBin(manageBin.toString());
        flink.setWorkDir(workDir.toString());
        flink.setRestUrl("http://127.0.0.1:8081");

        FlinkStandaloneSubmitter submitter = new FlinkStandaloneSubmitter();
        ReflectionTestUtils.setField(submitter, "properties", properties);
        ReflectionTestUtils.setField(submitter, "runner", new CliCommandRunner());
        return submitter;
    }

    private DataIntegrationSubmitter.SubmitContext context() {
        return new DataIntegrationSubmitter.SubmitContext(
                1L, "订单同步", "order-sync", "BATCH",
                "mysql",
                Map.of("url", "jdbc:mysql://127.0.0.1:3306/ods",
                        "username", "root",
                        "password", "123456",
                        "columns", "id BIGINT, name STRING"),
                "postgresql",
                Map.of("url", "jdbc:postgresql://127.0.0.1:5432/dwd",
                        "username", "postgres",
                        "password", "123456",
                        "schema", "public"),
                java.util.List.of(new TableSpec("ods.orders", java.util.List.of())),
                Map.of(),
                2, 2);
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
