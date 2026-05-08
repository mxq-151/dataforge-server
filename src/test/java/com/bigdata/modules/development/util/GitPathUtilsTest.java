package com.bigdata.modules.development.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

class GitPathUtilsTest {

    @Test
    @DisplayName("规范化路径 - 移除前导斜杠")
    void testNormalizePathLeadingSlash() {
        assertEquals("src/main", GitPathUtils.normalizePath("/src/main"));
        assertEquals("file.txt", GitPathUtils.normalizePath("/file.txt"));
    }

    @Test
    @DisplayName("规范化路径 - 移除尾部斜杠")
    void testNormalizePathTrailingSlash() {
        assertEquals("src/main", GitPathUtils.normalizePath("src/main/"));
        assertEquals("folder", GitPathUtils.normalizePath("folder/"));
    }

    @Test
    @DisplayName("规范化路径 - 移除前后斜杠")
    void testNormalizePathBothSlashes() {
        assertEquals("src/main", GitPathUtils.normalizePath("/src/main/"));
    }

    @Test
    @DisplayName("规范化路径 - 空路径")
    void testNormalizeEmptyPath() {
        assertEquals("", GitPathUtils.normalizePath(null));
        assertEquals("", GitPathUtils.normalizePath(""));
        assertEquals("", GitPathUtils.normalizePath("/"));
    }

    @Test
    @DisplayName("规范化路径 - 去除空格")
    void testNormalizeTrimSpaces() {
        assertEquals("src/main", GitPathUtils.normalizePath("  src/main  "));
    }

    @Test
    @DisplayName("转换为Git路径")
    void testToGitPath() {
        assertEquals("src/Main.scala", GitPathUtils.toGitPath("/src/Main.scala"));
        assertEquals("file.txt", GitPathUtils.toGitPath("file.txt"));
        assertEquals("", GitPathUtils.toGitPath("/"));
        assertEquals("", GitPathUtils.toGitPath(null));
    }

    @Test
    @DisplayName("构建相对路径")
    void testToRelativePath() {
        assertEquals("src/Main.scala", GitPathUtils.toRelativePath("/src", "Main.scala"));
        assertEquals("file.txt", GitPathUtils.toRelativePath("", "file.txt"));
        assertEquals("folder/file.txt", GitPathUtils.toRelativePath("folder", "file.txt"));
        assertEquals("name", GitPathUtils.toRelativePath(null, "name"));
    }

    @Test
    @DisplayName("判断是否为根路径")
    void testIsRootPath() {
        assertTrue(GitPathUtils.isRootPath(null));
        assertTrue(GitPathUtils.isRootPath(""));
        assertTrue(GitPathUtils.isRootPath("/"));
        assertFalse(GitPathUtils.isRootPath("/src"));
        assertFalse(GitPathUtils.isRootPath("src"));
    }
}
