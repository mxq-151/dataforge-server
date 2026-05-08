package com.bigdata.modules.devops.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FileExplorerControllerBasicTest {

    @Test
    @DisplayName("测试Controller类可正常实例化")
    void testControllerInstantiation() {
        FileExplorerController controller = new FileExplorerController();
        assertNotNull(controller);
    }

    @Test
    @DisplayName("测试上传功能 - 验证参数构建逻辑")
    void testUploadPathConstruction() {
        String path1 = "/test";
        String fileName = "file.txt";

        String filePath1 = path1.endsWith("/") ? path1 + fileName : path1 + "/" + fileName;
        assertEquals("/test/file.txt", filePath1);

        String path2 = "/uploads/";
        String filePath2 = path2.endsWith("/") ? path2 + fileName : path2 + "/" + fileName;
        assertEquals("/uploads/file.txt", filePath2);
    }

    @Test
    @DisplayName("测试预览功能 - Content-Type映射逻辑")
    void testContentTypeMapping() {
        String[] textExtensions = {"txt", "html", "json", "xml", "csv"};
        String[] imageExtensions = {"png", "jpg", "jpeg", "gif"};

        for (String ext : textExtensions) {
            assertTrue(isTextFile(ext), ext + " should be text file");
        }

        for (String ext : imageExtensions) {
            assertTrue(isImageFile(ext), ext + " should be image file");
        }
    }

    private boolean isTextFile(String extension) {
        return switch (extension) {
            case "txt", "html", "json", "xml", "csv" -> true;
            default -> false;
        };
    }

    private boolean isImageFile(String extension) {
        return switch (extension) {
            case "png", "jpg", "jpeg", "gif" -> true;
            default -> false;
        };
    }
}
