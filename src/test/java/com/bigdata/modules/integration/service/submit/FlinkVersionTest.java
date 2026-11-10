package com.bigdata.modules.integration.service.submit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flink 版本解析：数据集成要求 2.2.0 及以上，版本来自安装目录 lib/flink-dist-*.jar。
 */
class FlinkVersionTest {

    @TempDir
    Path tempDir;

    @Test
    void 解析命令行版本输出与x_y格式() {
        assertEquals("2.2.0", FlinkVersion.parse("Version: 2.2.0, Commit ID: 9f0a1b2").toString());
        assertEquals("2.2.0", FlinkVersion.parse("2.2").toString());
        assertEquals("2.10.1", FlinkVersion.parse("2.10.1").toString());
        assertNull(FlinkVersion.parse("no version here"));
        assertNull(FlinkVersion.parse(null));
    }

    @Test
    void 从安装目录的dist包检测版本() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("flink-home"));
        Path lib = Files.createDirectories(home.resolve("lib"));
        Files.createFile(lib.resolve("flink-dist-2.2.0.jar"));
        Files.createFile(lib.resolve("flink-dist-2.1.0.jar"));

        assertEquals("2.2.0", FlinkVersion.detect(home).toString());
    }

    @Test
    void 兼容1_x带scala后缀的dist包名() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("flink-home"));
        Path lib = Files.createDirectories(home.resolve("lib"));
        // 1.x 的包名是 flink-dist_2.12-1.20.0.jar，不能把 Scala 版本 2.12 当成 Flink 版本
        Files.createFile(lib.resolve("flink-dist_2.12-1.20.0.jar"));

        assertEquals("1.20.0", FlinkVersion.detect(home).toString());
    }

    @Test
    void 没有dist包时返回null() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("flink-home"));
        Files.createDirectories(home.resolve("lib"));
        assertNull(FlinkVersion.detect(home));
        assertNull(FlinkVersion.detect(null));
    }

    @Test
    void 版本比较覆盖主次修订号() {
        FlinkVersion required = FlinkVersion.parse("2.2.0");
        assertTrue(FlinkVersion.parse("2.2.0").isAtLeast(required));
        assertTrue(FlinkVersion.parse("2.2.1").isAtLeast(required));
        assertTrue(FlinkVersion.parse("2.10.0").isAtLeast(required));
        assertFalse(FlinkVersion.parse("2.1.9").isAtLeast(required));
        assertFalse(FlinkVersion.parse("1.20.0").isAtLeast(required));
    }
}
