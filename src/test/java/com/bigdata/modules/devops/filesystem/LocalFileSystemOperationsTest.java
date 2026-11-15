package com.bigdata.modules.devops.filesystem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地文件系统的常规操作与错误语义（建目录 / 重命名 / 删除 / 上传 / 预览上限）。
 */
@DisplayName("DevOps模块 - 本地文件系统操作")
class LocalFileSystemOperationsTest {

    @TempDir
    Path tempDir;

    private Path baseDir;
    private LocalFileSystem fileSystem;

    @BeforeEach
    void setUp() throws Exception {
        baseDir = Files.createDirectories(tempDir.resolve("base"));
        fileSystem = new LocalFileSystem();
        fileSystem.initialize(Map.of("basePath", baseDir.toString()));
    }

    @Test
    @DisplayName("建目录后 exists 为真")
    void createsDirectory() throws Exception {
        fileSystem.createDirectory("/data/sub");

        assertTrue(Files.isDirectory(baseDir.resolve("data/sub")));
    }

    @Test
    @DisplayName("目录已存在：抛 FileAlreadyExistsException")
    void rejectsDuplicateDirectory() throws Exception {
        fileSystem.createDirectory("/data");

        assertThrows(FileAlreadyExistsException.class, () -> fileSystem.createDirectory("/data"));
    }

    @Test
    @DisplayName("重命名成功")
    void renamesFile() throws Exception {
        Files.writeString(baseDir.resolve("a.txt"), "A");

        fileSystem.renameFile("/a.txt", "/b.txt");

        assertFalse(Files.exists(baseDir.resolve("a.txt")));
        assertEquals("A", Files.readString(baseDir.resolve("b.txt")));
    }

    @Test
    @DisplayName("重命名：源不存在、目标已存在都拒绝")
    void renameRejectsInvalidSourceOrTarget() throws Exception {
        Files.writeString(baseDir.resolve("exists.txt"), "E");

        assertThrows(FileNotFoundException.class, () -> fileSystem.renameFile("/nope.txt", "/x.txt"));
        assertThrows(FileAlreadyExistsException.class,
                () -> fileSystem.renameFile("/exists.txt", "/exists.txt"));
    }

    @Test
    @DisplayName("删除目录：递归清理子文件")
    void deletesDirectoryRecursively() throws Exception {
        Files.createDirectories(baseDir.resolve("dir/sub"));
        Files.writeString(baseDir.resolve("dir/sub/f.txt"), "x");

        fileSystem.deleteFile("/dir");

        assertFalse(Files.exists(baseDir.resolve("dir")));
    }

    @Test
    @DisplayName("删除不存在的文件：抛 FileNotFoundException")
    void deleteRejectsMissingFile() {
        assertThrows(FileNotFoundException.class, () -> fileSystem.deleteFile("/nope.txt"));
    }

    @Test
    @DisplayName("上传自动创建父目录并写入内容")
    void uploadCreatesParentDirectories() throws Exception {
        byte[] content = "hello-upload".getBytes(StandardCharsets.UTF_8);

        try (InputStream in = new ByteArrayInputStream(content)) {
            fileSystem.uploadFile("/deep/dir/file.txt", in, content.length);
        }

        assertEquals("hello-upload", Files.readString(baseDir.resolve("deep/dir/file.txt")));
    }

    @Test
    @DisplayName("预览超过 10MB 的文件：拒绝")
    void readRejectsOversizedFile() throws Exception {
        Path big = baseDir.resolve("big.bin");
        try (RandomAccessFile raf = new RandomAccessFile(big.toFile(), "rw")) {
            raf.setLength(10L * 1024 * 1024 + 1);
        }

        IOException ex = assertThrows(IOException.class, () -> fileSystem.readFile("/big.bin"));
        assertTrue(ex.getMessage().contains("File too large"));
    }

    @Test
    @DisplayName("预览目录：按文件不存在处理")
    void readRejectsDirectory() throws Exception {
        Files.createDirectories(baseDir.resolve("d"));

        assertThrows(FileNotFoundException.class, () -> fileSystem.readFile("/d"));
    }

    @Test
    @DisplayName("列表：目录排在文件前，且忽略根外内容")
    void listsDirectoriesBeforeFiles() throws Exception {
        Files.createDirectories(baseDir.resolve("zzz_dir"));
        Files.writeString(baseDir.resolve("aaa.txt"), "a");

        List<Map<String, Object>> entries = fileSystem.listFiles("/");

        assertEquals(2, entries.size());
        assertEquals("zzz_dir", entries.get(0).get("name"));
        assertTrue((Boolean) entries.get(0).get("isDir"));
        assertEquals("aaa.txt", entries.get(1).get("name"));
    }
}
