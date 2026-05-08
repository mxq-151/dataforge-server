package com.bigdata.modules.devops.service;

import com.bigdata.modules.devops.filesystem.FileSystem;
import com.bigdata.modules.devops.filesystem.FileSystemFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileExplorerServiceTest {

    @Mock
    private FileSystemFactory fileSystemFactory;

    @Mock
    private FileSystem fileSystem;

    @InjectMocks
    private FileExplorerService fileExplorerService;

    @BeforeEach
    void setUp() {
        when(fileSystemFactory.getFileSystem(anyString(), anyLong())).thenReturn(fileSystem);
    }

    @Test
    @DisplayName("测试上传文件 - 成功场景")
    void testUploadFile_Success() throws Exception {
        String storageType = "local";
        String filePath = "/test/upload.txt";
        Long configId = 1L;
        String content = "test content";
        InputStream inputStream = new ByteArrayInputStream(content.getBytes());

        when(fileSystem.exists("/test")).thenReturn(false);
        when(fileSystem.exists(filePath)).thenReturn(false);

        assertDoesNotThrow(() ->
            fileExplorerService.uploadFile(storageType, filePath, inputStream, configId, content.length())
        );

        verify(fileSystem).createDirectory("/test");
        verify(fileSystem).uploadFile(eq(filePath), eq(inputStream), eq((long) content.length()));
    }

    @Test
    @DisplayName("测试上传文件 - 父目录已存在")
    void testUploadFile_ParentDirectoryExists() throws Exception {
        String storageType = "hdfs";
        String filePath = "/existing/dir/file.txt";
        Long configId = 2L;
        InputStream inputStream = new ByteArrayInputStream("content".getBytes());

        when(fileSystem.exists("/existing/dir")).thenReturn(true);
        when(fileSystem.exists(filePath)).thenReturn(false);

        fileExplorerService.uploadFile(storageType, filePath, inputStream, configId, 7);

        verify(fileSystem, never()).createDirectory(anyString());
        verify(fileSystem).uploadFile(filePath, inputStream, 7);
    }

    @Test
    @DisplayName("测试上传文件 - 文件已存在应失败")
    void testUploadFile_FileAlreadyExists() throws Exception {
        String storageType = "local";
        String filePath = "/test/existing.txt";
        Long configId = 1L;
        InputStream inputStream = new ByteArrayInputStream("content".getBytes());

        when(fileSystem.exists("/test")).thenReturn(true);
        when(fileSystem.exists(filePath)).thenReturn(true);

        Exception exception = assertThrows(Exception.class, () ->
            fileExplorerService.uploadFile(storageType, filePath, inputStream, configId, 7)
        );

        assertTrue(exception.getMessage().contains("文件已存在"));
        verify(fileSystem, never()).uploadFile(anyString(), any(InputStream.class), anyLong());
    }

    @Test
    @DisplayName("测试读取文件 - 成功场景")
    void testReadFile_Success() throws Exception {
        String storageType = "local";
        String filePath = "/test/file.txt";
        Long configId = 1L;
        String content = "file content";
        InputStream expectedStream = new ByteArrayInputStream(content.getBytes());

        when(fileSystem.exists(filePath)).thenReturn(true);
        when(fileSystem.readFile(filePath)).thenReturn(expectedStream);

        InputStream result = fileExplorerService.readFile(storageType, filePath, configId);

        assertNotNull(result);
        assertEquals(expectedStream, result);

        verify(fileSystem).exists(filePath);
        verify(fileSystem).readFile(filePath);
    }

    @Test
    @DisplayName("测试读取文件 - 文件不存在应失败")
    void testReadFile_NotFound() throws Exception {
        String storageType = "hdfs";
        String filePath = "/nonexistent.txt";
        Long configId = 2L;

        when(fileSystem.exists(filePath)).thenReturn(false);

        Exception exception = assertThrows(Exception.class, () ->
            fileExplorerService.readFile(storageType, filePath, configId)
        );

        assertTrue(exception.getMessage().contains("文件不存在"));
        verify(fileSystem, never()).readFile(anyString());
    }
}
