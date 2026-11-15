package com.bigdata.modules.devops.filesystem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地文件系统的路径越界防护。
 *
 * <p>接口语义是「以 basePath 为根的相对路径」，因此 {@code ..}、盘符、UNC、软链接
 * 都不能把最终路径带出根目录。修复前 normalizePath 只压缩重复的 "/"，这些输入会直接
 * 拼到 basePath 后面，由操作系统解释成越界路径。</p>
 */
@DisplayName("DevOps模块 - 本地文件系统路径越界")
class LocalFileSystemPathTraversalTest {

    @TempDir
    Path tempDir;

    private Path baseDir;
    private Path outsideDir;
    private LocalFileSystem fileSystem;

    @BeforeEach
    void setUp() throws Exception {
        baseDir = Files.createDirectories(tempDir.resolve("base"));
        outsideDir = Files.createDirectories(tempDir.resolve("outside"));
        Files.writeString(outsideDir.resolve("secret.txt"), "TOP-SECRET");

        fileSystem = new LocalFileSystem();
        fileSystem.initialize(Map.of("basePath", baseDir.toString()));
    }

    @Test
    @DisplayName("正常子路径仍可读取（修复不能误伤）")
    void readsNestedFileInsideBase() throws Exception {
        Files.createDirectories(baseDir.resolve("dir"));
        Files.writeString(baseDir.resolve("dir/ok.txt"), "hello");

        try (InputStream in = fileSystem.readFile("/dir/ok.txt")) {
            assertEquals("hello", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("POSIX 前导斜杠被当作根内相对路径")
    void leadingSlashStaysInsideBase() throws Exception {
        Files.writeString(baseDir.resolve("a.txt"), "A");

        assertTrue(fileSystem.exists("/a.txt"));
    }

    @Test
    @DisplayName(".. 越界读取必须被拒绝")
    void rejectsParentTraversal() {
        assertThrows(IOException.class, () -> fileSystem.readFile("../outside/secret.txt"));
        assertThrows(IOException.class, () -> fileSystem.readFile("/../../outside/secret.txt"));
    }

    @Test
    @DisplayName("Windows 分隔符形式的越界必须被拒绝")
    void rejectsWindowsStyleTraversal() {
        assertThrows(IOException.class, () -> fileSystem.readFile("..\\outside\\secret.txt"));
    }

    @Test
    @DisplayName("盘符与 UNC 形式必须被拒绝")
    void rejectsAbsoluteForms() {
        assertThrows(IOException.class, () -> fileSystem.readFile("C:\\Windows\\win.ini"));
        assertThrows(IOException.class, () -> fileSystem.readFile("//server/share/secret.txt"));
    }

    @Test
    @DisplayName("软链接指向根目录之外时必须被拒绝")
    void rejectsSymlinkEscape() throws Exception {
        Files.createSymbolicLink(baseDir.resolve("link"), outsideDir);

        assertThrows(IOException.class, () -> fileSystem.readFile("/link/secret.txt"));
    }

    @Test
    @DisplayName("越界删除同样必须被拒绝，且不能删掉根外文件")
    void rejectsTraversalOnDelete() throws Exception {
        assertThrows(IOException.class, () -> fileSystem.deleteFile("../outside/secret.txt"));
        assertTrue(Files.exists(outsideDir.resolve("secret.txt")), "根目录外的文件不能被删除");
    }
}
