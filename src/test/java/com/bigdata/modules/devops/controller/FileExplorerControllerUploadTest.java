package com.bigdata.modules.devops.controller;

import com.bigdata.modules.devops.service.FileExplorerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FileExplorerControllerTest {

    @Mock
    private FileExplorerService fileExplorerService;

    @InjectMocks
    private FileExplorerController fileExplorerController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    @DisplayName("测试上传文件 - 成功场景")
    void testUploadFile_Success() throws Exception {
        String storageType = "local";
        String path = "/test";
        Long configId = 1L;

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.txt",
                "text/plain",
                "Hello, World!".getBytes()
        );

        doNothing().when(fileExplorerService).uploadFile(
                eq(storageType),
                eq("/test/test.txt"),
                any(InputStream.class),
                eq(configId),
                eq(file.getSize())
        );

        var result = fileExplorerController.uploadFile(storageType, path, configId, file);

        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertNull(result.getMessage());

        verify(fileExplorerService, times(1)).uploadFile(
                eq(storageType),
                eq("/test/test.txt"),
                any(InputStream.class),
                eq(configId),
                eq(file.getSize())
        );
    }

    @Test
    @DisplayName("测试上传文件 - 文件名为空")
    void testUploadFile_EmptyFileName() {
        String storageType = "local";
        String path = "/test";

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "",
                "text/plain",
                "test".getBytes()
        );

        var result = fileExplorerController.uploadFile(storageType, path, null, file);

        assertNotNull(result);
        assertNotEquals(200, result.getCode());
        assertTrue(result.getMessage().contains("文件名不能为空"));

        verify(fileExplorerService, never()).uploadFile(anyString(), anyString(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("测试上传文件 - 路径不带斜杠")
    void testUploadFile_PathWithoutTrailingSlash() throws Exception {
        String storageType = "hdfs";
        String path = "/user/data";
        Long configId = 2L;

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "data.csv",
                "text/csv",
                "1,2,3".getBytes()
        );

        doNothing().when(fileExplorerService).uploadFile(
                eq(storageType),
                eq("/user/data/data.csv"),
                any(InputStream.class),
                eq(configId),
                eq(file.getSize())
        );

        var result = fileExplorerController.uploadFile(storageType, path, configId, file);

        assertEquals(200, result.getCode());

        verify(fileExplorerService).uploadFile(
                eq(storageType),
                eq("/user/data/data.csv"),
                any(InputStream.class),
                eq(configId),
                eq(file.getSize())
        );
    }

    @Test
    @DisplayName("测试上传文件 - 路径带斜杠")
    void testUploadFile_PathWithTrailingSlash() throws Exception {
        String storageType = "s3";
        String path = "/uploads/";
        Long configId = 3L;

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "image.png",
                "image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}
        );

        doNothing().when(fileExplorerService).uploadFile(
                eq(storageType),
                eq("/uploads/image.png"),
                any(InputStream.class),
                eq(configId),
                eq(file.getSize())
        );

        var result = fileExplorerController.uploadFile(storageType, path, configId, file);

        assertEquals(200, result.getCode());

        verify(fileExplorerService).uploadFile(
                eq(storageType),
                eq("/uploads/image.png"),
                any(InputStream.class),
                eq(configId),
                eq(file.getSize())
        );
    }

    @Test
    @DisplayName("测试上传文件 - 服务端异常")
    void testUploadFile_ServiceException() throws Exception {
        String storageType = "local";
        String path = "/test";
        Long configId = 1L;

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.txt",
                "text/plain",
                "test content".getBytes()
        );

        doThrow(new RuntimeException("磁盘空间不足"))
                .when(fileExplorerService).uploadFile(anyString(), anyString(), any(), anyLong(), anyLong());

        var result = fileExplorerController.uploadFile(storageType, path, configId, file);

        assertNotNull(result);
        assertNotEquals(200, result.getCode());
        assertTrue(result.getMessage().contains("上传文件失败"));
    }

    @Test
    @DisplayName("测试上传文件 - 大文件")
    void testUploadFile_LargeFile() throws Exception {
        String storageType = "hdfs";
        String path = "/data";
        Long configId = 1L;

        byte[] largeContent = new byte[10 * 1024 * 1024];
        for (int i = 0; i < largeContent.length; i++) {
            largeContent[i] = (byte) (i % 256);
        }

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "large_file.bin",
                "application/octet-stream",
                largeContent
        );

        doNothing().when(fileExplorerService).uploadFile(
                eq(storageType),
                eq("/data/large_file.bin"),
                any(InputStream.class),
                eq(configId),
                eq(largeContent.length)
        );

        var result = fileExplorerController.uploadFile(storageType, path, configId, file);

        assertEquals(200, result.getCode());
        assertEquals(largeContent.length, file.getSize());
    }
}
