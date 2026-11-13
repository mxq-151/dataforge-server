package com.bigdata.modules.logs.adapter;

import com.bigdata.modules.logs.LogTestSupport;
import com.bigdata.modules.logs.config.LogAccessProperties;
import com.bigdata.modules.logs.model.ComponentRole;
import com.bigdata.modules.logs.model.LogKind;
import com.bigdata.modules.logs.model.LogSource;
import com.bigdata.modules.logs.model.ServiceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Flink 文件适配器：多 TM 合并排序、目录缺失、stdout")
class FlinkFileLogAdapterTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("多 TM 日志按时间戳合并排序（含跨目录）")
    void mergesAndSortsMultipleTaskManagers() throws Exception {
        Path jmDir = Files.createDirectories(root.resolve("jm"));
        Path tm1 = Files.createDirectories(root.resolve("tm-1"));
        Path tm2 = Files.createDirectories(root.resolve("tm-2"));

        Files.writeString(jmDir.resolve("flink-1-jobmanager-1.log"),
                "2026-11-13 10:00:03,000 INFO  LATE jm\n", StandardCharsets.UTF_8);
        Files.writeString(tm1.resolve("flink-1-taskmanager-1.log"),
                "2026-11-13 10:00:02,000 INFO  MID tm1\n", StandardCharsets.UTF_8);
        Files.writeString(tm2.resolve("flink-2-taskmanager-2.log"),
                "2026-11-13 10:00:01,000 INFO  EARLY tm2\n", StandardCharsets.UTF_8);

        FlinkFileLogAdapter adapter = new FlinkFileLogAdapter();
        AdapterResult result = adapter.query(context(jmDir, tm1, tm2, LogKind.RUNTIME, ComponentRole.GENERIC));

        List<String> lines = result.getContent().lines().toList();
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("EARLY"));
        assertTrue(lines.get(1).contains("MID"));
        assertTrue(lines.get(2).contains("LATE"));
        assertTrue(result.getResolvedPath().contains("3 个文件"));
    }

    @Test
    @DisplayName("按角色只读 TaskManager 目录，并支持时间范围过滤")
    void filtersByRoleAndTimeRange() throws Exception {
        Path jmDir = Files.createDirectories(root.resolve("jm"));
        Path tmDir = Files.createDirectories(root.resolve("tm"));
        Files.writeString(jmDir.resolve("flink-1-jobmanager-1.log"),
                "2026-11-13 10:00:01,000 INFO  jm\n", StandardCharsets.UTF_8);
        Files.writeString(tmDir.resolve("flink-1-taskmanager-1.log"),
                "2026-11-13 10:00:01,000 INFO  early tm\n"
                        + "2026-11-13 10:05:00,000 INFO  late tm\n", StandardCharsets.UTF_8);

        FlinkFileLogAdapter adapter = new FlinkFileLogAdapter();
        LogQueryContext context = context(jmDir, tmDir, null, LogKind.RUNTIME, ComponentRole.TASK_MANAGER);
        context.setStartTime(java.time.LocalDateTime.of(2026, 11, 13, 10, 4));
        context.setEndTime(java.time.LocalDateTime.of(2026, 11, 13, 10, 6));

        AdapterResult result = adapter.query(context);

        assertTrue(result.getContent().contains("late tm"));
        assertTrue(!result.getContent().contains("early tm"));
        assertTrue(!result.getContent().contains(" jm"));
    }

    @Test
    @DisplayName("日志目录不存在：明确报错而不是空结果")
    void missingDirectory() {
        LogAccessProperties.Source config = LogTestSupport.sourceConfig("flink-default", "flink", "");
        config.getOptions().getTaskManagerLogDirs().add(root.resolve("not-exist").toString());
        LogSource source = LogTestSupport.source(config, ServiceType.FLINK);
        LogQueryContext context = LogTestSupport.context(source, 2000, 262144, Map.of());
        context.setRole(ComponentRole.TASK_MANAGER);
        context.setLogKind(LogKind.RUNTIME);

        FlinkFileLogAdapter adapter = new FlinkFileLogAdapter();
        LogAdapterException error = assertThrows(LogAdapterException.class, () -> adapter.query(context));

        assertEquals(LogAdapterException.Code.NOT_FOUND, error.getCode());
        assertTrue(error.getMessage().contains("不存在"));
    }

    @Test
    @DisplayName("stdout 无时间戳时按文件顺序返回，并使用 .out 模板")
    void readsStdoutWithoutTimestamps() throws Exception {
        Path jmDir = Files.createDirectories(root.resolve("jm"));
        Files.writeString(jmDir.resolve("flink-1-jobmanager-1.out"),
                "starting jobmanager\nready\n", StandardCharsets.UTF_8);

        FlinkFileLogAdapter adapter = new FlinkFileLogAdapter();
        AdapterResult result = adapter.query(context(jmDir, null, null, LogKind.STDOUT, ComponentRole.JOB_MANAGER));

        assertTrue(result.getContent().contains("starting jobmanager"));
        assertTrue(result.getContent().contains("ready"));
    }

    private LogQueryContext context(Path jmDir, Path tmDir1, Path tmDir2,
                                    LogKind kind, ComponentRole role) {
        LogAccessProperties.Source config = LogTestSupport.sourceConfig("flink-default", "flink", "");
        if (jmDir != null) {
            config.getOptions().getJobManagerLogDirs().add(jmDir.toString());
        }
        if (tmDir1 != null) {
            config.getOptions().getTaskManagerLogDirs().add(tmDir1.toString());
        }
        if (tmDir2 != null) {
            config.getOptions().getTaskManagerLogDirs().add(tmDir2.toString());
        }
        LogSource source = LogTestSupport.source(config, ServiceType.FLINK);
        LogQueryContext context = LogTestSupport.context(source, 2000, 262144, Map.of());
        context.setRole(role);
        context.setLogKind(kind);
        return context;
    }
}
