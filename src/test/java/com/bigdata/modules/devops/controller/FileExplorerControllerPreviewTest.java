package com.bigdata.modules.devops.controller;

import com.bigdata.modules.devops.service.FileExplorerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FileExplorerControllerPreviewTest {

    @Mock
    private FileExplorerService fileExplorerService;

    @InjectMocks
    private FileExplorerController fileExplorerController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    @DisplayName("测试预览文本文件 - TXT格式")
    void testPreviewFile_TextFile() throws Exception {
        String storageType = "local";
        String path = "/test/document.txt";
        Long configId = 1L;
        String content = "这是测试文件内容";

        when(fileExplorerService.readFile(storageType, path, configId))
                .thenReturn(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<byte[]> response = fileExplorerController.previewFile(storageType, path, configId);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(content, new String(response.getBody(), StandardCharsets.UTF_8));

        HttpHeaders headers = response.getHeaders();
        assertEquals(MediaType.TEXT_PLAIN, headers.getContentType());
        assertEquals(content.getBytes(StandardCharsets.UTF_8).length, headers.getContentLength());

        verify(fileExplorerService, times(1)).readFile(storageType, path, configId);
    }

    @Test
    @DisplayName("测试预览JSON文件")
    void testPreviewFile_JsonFile() throws Exception {
        String storageType = "hdfs";
        String path = "/data/config.json";
        Long configId = 2L;
        String jsonContent = "{\"name\": \"test\", \"version\": 1}";

        when(fileExplorerService.readFile(storageType, path, configId))
                .thenReturn(new ByteArrayInputStream(jsonContent.getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<byte[]> response = fileExplorerController.previewFile(storageType, path, configId);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
        assertArrayEquals(jsonContent.getBytes(StandardCharsets.UTF_8), response.getBody());
    }

    @Test
    @DisplayName("测试预览HTML文件")
    void testPreviewFile_HtmlFile() throws Exception {
        String storageType = "s3";
        String path = "/web/index.html";
        Long configId = 3L;
        String htmlContent = "<html><body>Test</body></html>";

        when(fileExplorerService.readFile(storageType, path, configId))
                .thenReturn(new ByteArrayInputStream(htmlContent.getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<byte[]> response = fileExplorerController.previewFile(storageType, path, configId);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(MediaType.TEXT_HTML, response.getHeaders().getContentType());
    }

    @Test
    @DisplayName("测试预览PNG图片文件")
    void testPreviewFile_PngImage() throws Exception {
        String storageType = "local";
        String path = "/images/photo.png";
        Long configId = 1L;

        byte[] pngSignature = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

        when(fileExplorerService.readFile(storageType, path, configId))
                .thenReturn(new ByteArrayInputStream(pngSignature));

        ResponseEntity<byte[]> response = fileExplorerController.previewFile(storageType, path, configId);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertArrayEquals(pngSignature, response.getBody());
    }

    @Test
    @DisplayName("测试预览JPEG图片文件")
    void testPreviewFile_JpegImage() throws Exception {
        String storageType = "local";
        String path = "/photos/image.jpg";
        Long configId = 1L;

        byte[] jpegContent = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};

        when(fileExplorerService.readFile(storageType, path, configId))
                .thenReturn(new ByteArrayInputStream(jpegContent));

        ResponseEntity<byte[]> response = fileExplorerController.previewFile(storageType, path, configId);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(MediaType.IMAGE_JPEG, response.getHeaders().getContentType());
    }

    @Test
    @DisplayName("测试预览PDF文件")
    void testPreviewFile_PdfFile() throws Exception {
        String storageType = "hdfs";
        String path = "/docs/report.pdf";
        Long configId = 2L;

        byte[] pdfContent = {0x25, 0x50, 0x44, 0x46};

        when(fileExplorerService.readFile(storageType, path, configId))
                .thenReturn(new ByteArrayInputStream(pdfContent));

        ResponseEntity<byte[]> response = fileExplorerController.previewFile(storageType, path, configId);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType());
    }

    @Test
    @DisplayName("测试预览CSV文件")
    void testPreviewFile_CsvFile() throws Exception {
        String storageType = "local";
        String path = "/data/export.csv";
        Long configId = 1L;
        String csvContent = "name,age,city\nAlice,30,NYC\nBob,25,LA";

        when(fileExplorerService.readFile(storageType, path, configId))
                .thenReturn(new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<byte[]> response = fileExplorerController.previewFile(storageType, path, configId);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("text/csv", response.getHeaders().getContentType().toString());
    }

    @Test
    @DisplayName("测试预览未知类型文件 - 默认OCTET_STREAM")
    void testPreviewFile_UnknownFileType() throws Exception {
        String storageType = "local";
        String path = "/data/unknown.xyz";
        Long configId = 1L;
        byte[] content = {0x01, 0x02, 0x03, 0x04};

        when(fileExplorerService.readFile(storageType, path, configId))
                .thenReturn(new ByteArrayInputStream(content));

        ResponseEntity<byte[]> response = fileExplorerController.previewFile(storageType, path, configId);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_OCTET_STREAM, response.getHeaders().getContentType());
    }

    @Test
    @DisplayName("测试预览无扩展名文件 - 默认OCTET_STREAM")
    void testPreviewFile_NoExtension() throws Exception {
        String storageType = "local";
        String path = "/data/README";
        Long configId = 1L;
        byte[] content = "Content".getBytes(StandardCharsets.UTF_8);

        when(fileExplorerService.readFile(storageType, path, configId))
                .thenReturn(new ByteArrayInputStream(content));

        ResponseEntity<byte[]> response = fileExplorerController.previewFile(storageType, path, configId);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_OCTET_STREAM, response.getHeaders().getContentType());
    }

    @Test
    @DisplayName("测试预览文件 - 文件不存在异常")
    void testPreviewFile_FileNotFound() throws Exception {
        String storageType = "local";
        String path = "/nonexistent/file.txt";
        Long configId = 1L;

        when(fileExplorerService.readFile(storageType, path, configId))
                .thenThrow(new RuntimeException("文件不存在"));

        ResponseEntity<byte[]> response = fileExplorerController.previewFile(storageType, path, configId);

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    @DisplayName("测试预览文件 - 响应头包含Cache-Control")
    void testPreviewFile_CacheControlHeader() throws Exception {
        String storageType = "local";
        String path = "/test/cache.txt";
        Long configId = 1L;
        String content = "cache test";

        when(fileExplorerService.readFile(storageType, path, configId))
                .thenReturn(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<byte[]> response = fileExplorerController.previewFile(storageType, path, configId);

        HttpHeaders headers = response.getHeaders();
        assertTrue(headers.containsKey("Cache-Control"));
        assertEquals("no-cache", headers.getFirst("Cache-Control"));
    }

    @Test
    @DisplayName("测试预览空文件")
    void testPreviewFile_EmptyFile() throws Exception {
        String storageType = "local";
        String path = "/test/empty.txt";
        Long configId = 1L;

        when(fileExplorerService.readFile(storageType, path, configId))
                .thenReturn(new ByteArrayInputStream(new byte[0]));

        ResponseEntity<byte[]> response = fileExplorerController.previewFile(storageType, path, configId);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(0, response.getBody().length);
        assertEquals(0, response.getHeaders().getContentLength());
    }

    @Test
    @DisplayName("测试预览大文件")
    void testPreviewFile_LargeFile() throws Exception {
        String storageType = "hdfs";
        String path = "/data/large.log";
        Long configId = 2L;

        byte[] largeContent = new byte[5 * 1024 * 1024];
        for (int i = 0; i < largeContent.length; i++) {
            largeContent[i] = (byte) ('A' + (i % 26));
        }

        when(fileExplorerService.readFile(storageType, path, configId))
                .thenReturn(new ByteArrayInputStream(largeContent));

        ResponseEntity<byte[]> response = fileExplorerController.previewFile(storageType, path, configId);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(largeContent.length, response.getBody().length);
        assertEquals(largeContent.length, response.getHeaders().getContentLength());
    }
}
