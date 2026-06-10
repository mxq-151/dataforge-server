package com.bigdata.modules.devops.controller;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.security.CustomUserDetails;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.devops.vo.FileInfoVO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.annotation.Rollback;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
@Rollback
@DisplayName("DevOps模块 - FileExplorerController文件浏览集成测试")
class FileExplorerControllerIntegrationTest {

    @Autowired
    private FileExplorerController fileExplorerController;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setUpAdminSecurityContext() {
        List<SimpleGrantedAuthority> authorities = Arrays.asList(
                new SimpleGrantedAuthority("ROLE_ADMIN")
        );
        CustomUserDetails userDetails = new CustomUserDetails(
                1L, "admin", 1L, Arrays.asList("ROLE_ADMIN"), false, authorities, true);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private void setUpNormalUserSecurityContext() {
        List<SimpleGrantedAuthority> authorities = Arrays.asList(
                new SimpleGrantedAuthority("ROLE_USER")
        );
        CustomUserDetails userDetails = new CustomUserDetails(
                2L, "normaluser", 1L, Arrays.asList("ROLE_USER"), false, authorities, true);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private void setUpGuestSecurityContext() {
        List<SimpleGrantedAuthority> authorities = Arrays.asList(
                new SimpleGrantedAuthority("ROLE_GUEST")
        );
        CustomUserDetails userDetails = new CustomUserDetails(
                99L, "guest", 1L, Arrays.asList("ROLE_GUEST"), false, authorities, true);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private String uniqueName(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Nested
    @DisplayName("场景1: 文件浏览（listFiles）")
    class ListFilesTests {

        @Test
        @DisplayName("✅ 列出本地根目录内容 - ADMIN用户")
        void testListFiles_RootDir_Admin() {
            System.out.println("\n=== 场景1.1: 列出本地根目录内容 ===");
            setUpAdminSecurityContext();

            Result<List<FileInfoVO>> result = fileExplorerController.listFiles("local", "/", null);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "应返回200");
            assertNotNull(result.getData(), "文件列表不应为null");

            System.out.println("  根目录文件数量: " + result.getData().size());
            result.getData().forEach(f -> System.out.println("    - " + f.getName() + (f.getIsDir() ? "/" : "")));
            System.out.println("  列出根目录内容成功 ✓");
        }

        @Test
        @DisplayName("✅ 列出子目录内容 - ADMIN用户")
        void testListFiles_SubDir_Admin() {
            System.out.println("\n=== 场景1.2: 列出子目录内容 ===");
            setUpAdminSecurityContext();

            String dirName = uniqueName("subdir_test");
            fileExplorerController.createDirectory("local", "/", dirName, null);

            Result<List<FileInfoVO>> result = fileExplorerController.listFiles("local", "/" + dirName, null);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "子目录列表应返回200");
            assertNotNull(result.getData(), "子目录文件列表不应为null");
            System.out.println("  子目录内容数量: " + result.getData().size());
            System.out.println("  列出子目录内容成功 ✓");
        }

        @Test
        @DisplayName("❌ 非ADMIN用户无文件权限抛BusinessException(403)")
        void testListFiles_NonAdmin_NoFilePermission() {
            System.out.println("\n=== 场景1.3: 非ADMIN用户无文件权限 ===");
            setUpNormalUserSecurityContext();

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                fileExplorerController.listFiles("local", "/", null);
            }, "无文件权限的非ADMIN用户应抛出BusinessException");

            assertEquals(403, exception.getCode(), "异常code应为403");
            System.out.println("  异常消息: " + exception.getMessage());
            System.out.println("  非ADMIN用户无文件权限被正确拒绝 ✓");
        }

        @Test
        @DisplayName("✅ ADMIN用户看到所有文件")
        void testListFiles_Admin_SeesAll() {
            System.out.println("\n=== 场景1.4: ADMIN用户看到所有文件 ===");
            setUpAdminSecurityContext();

            Result<List<FileInfoVO>> result = fileExplorerController.listFiles("local", "/", null);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "应返回200");
            assertNotNull(result.getData(), "文件列表不应为null");

            System.out.println("  ADMIN用户可见文件数量: " + result.getData().size());
            System.out.println("  ADMIN用户可查看所有文件 ✓");
        }

        @Test
        @DisplayName("❌ 无读权限抛BusinessException(403)")
        void testListFiles_NoReadPermission_Throws403() {
            System.out.println("\n=== 场景1.5: 无读权限抛BusinessException(403) ===");
            setUpGuestSecurityContext();

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                fileExplorerController.listFiles("local", "/", null);
            }, "无读权限应抛出BusinessException");

            assertEquals(403, exception.getCode(), "异常code应为403");
            System.out.println("  异常消息: " + exception.getMessage());
            System.out.println("  异常code: " + exception.getCode() + " ✓");
        }
    }

    @Nested
    @DisplayName("场景2: 创建目录（createDirectory）")
    class CreateDirectoryTests {

        @Test
        @DisplayName("✅ 在本地文件系统创建目录")
        void testCreateDirectory_Success() {
            System.out.println("\n=== 场景2.1: 创建目录成功 ===");
            setUpAdminSecurityContext();

            String dirName = uniqueName("new_dir");
            Result<Void> result = fileExplorerController.createDirectory("local", "/", dirName, null);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "创建目录应返回200");
            System.out.println("  创建目录 '" + dirName + "' 成功 ✓");
        }

        @Test
        @DisplayName("❌ 目录名包含非法字符被拒绝")
        void testCreateDirectory_IllegalCharacters() {
            System.out.println("\n=== 场景2.2: 目录名包含非法字符 ===");
            setUpAdminSecurityContext();

            String[] illegalNames = {":", "*", "?", "\"", "<", ">", "|"};

            for (String illegalName : illegalNames) {
                BusinessException exception = assertThrows(BusinessException.class, () -> {
                    fileExplorerController.createDirectory("local", "/", illegalName, null);
                }, "目录名为非法字符'" + illegalName + "'应被拒绝");

                assertTrue(exception.getMessage().contains("非法字符"),
                        "异常消息应包含'非法字符'");
                System.out.println("  非法字符'" + illegalName + "'被拒绝: " + exception.getMessage());
            }
            System.out.println("  所有非法字符目录名被拒绝 ✓");
        }

        @Test
        @DisplayName("❌ 目录已存在抛BusinessException")
        void testCreateDirectory_AlreadyExists() {
            System.out.println("\n=== 场景2.3: 目录已存在 ===");
            setUpAdminSecurityContext();

            String dirName = uniqueName("existing_dir");
            fileExplorerController.createDirectory("local", "/", dirName, null);

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                fileExplorerController.createDirectory("local", "/", dirName, null);
            }, "创建已存在的目录应抛出BusinessException");

            assertTrue(exception.getMessage().contains("已存在"),
                    "异常消息应包含'已存在'");
            System.out.println("  异常消息: " + exception.getMessage());
            System.out.println("  目录已存在被正确拒绝 ✓");
        }

        @Test
        @DisplayName("❌ 无写权限抛BusinessException(403)")
        void testCreateDirectory_NoWritePermission_Throws403() {
            System.out.println("\n=== 场景2.4: 无写权限创建目录 ===");
            setUpGuestSecurityContext();

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                fileExplorerController.createDirectory("local", "/", "unauthorized_dir", null);
            }, "无写权限应抛出BusinessException");

            assertEquals(403, exception.getCode(), "异常code应为403");
            System.out.println("  异常消息: " + exception.getMessage());
            System.out.println("  异常code: " + exception.getCode() + " ✓");
        }
    }

    @Nested
    @DisplayName("场景3: 删除文件/目录（deleteFile）")
    class DeleteFileTests {

        @Test
        @DisplayName("✅ 删除存在的文件")
        void testDeleteFile_Success() {
            System.out.println("\n=== 场景3.1: 删除存在的文件 ===");
            setUpAdminSecurityContext();

            String dirName = uniqueName("dir_to_delete");
            fileExplorerController.createDirectory("local", "/", dirName, null);

            Result<Void> result = fileExplorerController.deleteFile("local", "/" + dirName, null);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "删除应返回200");
            System.out.println("  删除文件成功 ✓");
        }

        @Test
        @DisplayName("❌ 删除不存在的文件抛BusinessException")
        void testDeleteFile_NotExists() {
            System.out.println("\n=== 场景3.2: 删除不存在的文件 ===");
            setUpAdminSecurityContext();

            String nonExistent = "/non_existent_" + UUID.randomUUID().toString().substring(0, 8);
            BusinessException exception = assertThrows(BusinessException.class, () -> {
                fileExplorerController.deleteFile("local", nonExistent, null);
            }, "删除不存在的文件应抛出BusinessException");

            assertTrue(exception.getMessage().contains("不存在"),
                    "异常消息应包含'不存在'");
            System.out.println("  异常消息: " + exception.getMessage());
            System.out.println("  删除不存在文件被正确拒绝 ✓");
        }

        @Test
        @DisplayName("❌ 无写权限抛BusinessException(403)")
        void testDeleteFile_NoWritePermission_Throws403() {
            System.out.println("\n=== 场景3.3: 无写权限删除文件 ===");
            setUpGuestSecurityContext();

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                fileExplorerController.deleteFile("local", "/some_file", null);
            }, "无写权限应抛出BusinessException");

            assertEquals(403, exception.getCode(), "异常code应为403");
            System.out.println("  异常消息: " + exception.getMessage());
            System.out.println("  异常code: " + exception.getCode() + " ✓");
        }
    }

    @Nested
    @DisplayName("场景4: 重命名文件（renameFile）")
    class RenameFileTests {

        @Test
        @DisplayName("✅ 重命名成功")
        void testRenameFile_Success() {
            System.out.println("\n=== 场景4.1: 重命名成功 ===");
            setUpAdminSecurityContext();

            String oldName = uniqueName("before_rename");
            String newName = uniqueName("after_rename");
            fileExplorerController.createDirectory("local", "/", oldName, null);

            Result<Void> result = fileExplorerController.renameFile("local", "/" + oldName, newName, null);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "重命名应返回200");
            System.out.println("  重命名 '" + oldName + "' -> '" + newName + "' 成功 ✓");
        }

        @Test
        @DisplayName("❌ 新名称包含非法字符被拒绝")
        void testRenameFile_IllegalNewName() {
            System.out.println("\n=== 场景4.2: 新名称包含非法字符 ===");
            setUpAdminSecurityContext();

            String dirName = uniqueName("rename_test");
            fileExplorerController.createDirectory("local", "/", dirName, null);

            String[] illegalNames = {":", "*", "?", "\"", "<", ">", "|"};

            for (String illegalName : illegalNames) {
                BusinessException exception = assertThrows(BusinessException.class, () -> {
                    fileExplorerController.renameFile("local", "/" + dirName, illegalName, null);
                }, "新名称包含非法字符'" + illegalName + "'应被拒绝");

                assertTrue(exception.getMessage().contains("非法字符"),
                        "异常消息应包含'非法字符'");
                System.out.println("  非法新名称'" + illegalName + "'被拒绝: " + exception.getMessage());
            }
            System.out.println("  所有非法新名称被拒绝 ✓");
        }

        @Test
        @DisplayName("❌ 无写权限抛BusinessException(403)")
        void testRenameFile_NoWritePermission_Throws403() {
            System.out.println("\n=== 场景4.3: 无写权限重命名 ===");
            setUpGuestSecurityContext();

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                fileExplorerController.renameFile("local", "/some_file", "new_name", null);
            }, "无写权限应抛出BusinessException");

            assertEquals(403, exception.getCode(), "异常code应为403");
            System.out.println("  异常消息: " + exception.getMessage());
            System.out.println("  异常code: " + exception.getCode() + " ✓");
        }
    }

    @Nested
    @DisplayName("场景5: 上传文件（uploadFile）")
    class UploadFileTests {

        @Test
        @DisplayName("✅ 上传成功")
        void testUploadFile_Success() {
            System.out.println("\n=== 场景5.1: 上传文件成功 ===");
            setUpAdminSecurityContext();

            String fileName = uniqueName("upload") + ".txt";
            MockMultipartFile mockFile = new MockMultipartFile(
                    "file", fileName, "text/plain", "Hello World".getBytes());

            Result<Void> result = fileExplorerController.uploadFile("local", "/", null, mockFile);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "上传应返回200");
            System.out.println("  上传文件 '" + fileName + "' 成功 ✓");
        }

        @Test
        @DisplayName("❌ 文件名为空返回错误")
        void testUploadFile_EmptyFileName() {
            System.out.println("\n=== 场景5.2: 文件名为空 ===");
            setUpAdminSecurityContext();

            MockMultipartFile mockFile = new MockMultipartFile(
                    "file", "", "text/plain", "content".getBytes());

            Result<Void> result = fileExplorerController.uploadFile("local", "/", null, mockFile);

            assertNotEquals(200, result.getCode(), "文件名为空应返回非200");
            System.out.println("  返回code: " + result.getCode());
            System.out.println("  返回消息: " + result.getMessage());
            System.out.println("  文件名为空被正确拒绝 ✓");
        }

        @Test
        @DisplayName("❌ 无写权限抛BusinessException(403)")
        void testUploadFile_NoWritePermission_Throws403() {
            System.out.println("\n=== 场景5.3: 无写权限上传文件 ===");
            setUpGuestSecurityContext();

            MockMultipartFile mockFile = new MockMultipartFile(
                    "file", "unauthorized_upload.txt", "text/plain", "content".getBytes());

            Result<Void> result = fileExplorerController.uploadFile("local", "/", null, mockFile);

            assertNotEquals(200, result.getCode(), "无写权限上传应返回非200");
            System.out.println("  返回code: " + result.getCode());
            System.out.println("  返回消息: " + result.getMessage());
            System.out.println("  无写权限上传被拒绝 ✓");
        }

        @Test
        @DisplayName("❌ 文件已存在抛BusinessException")
        void testUploadFile_FileAlreadyExists() {
            System.out.println("\n=== 场景5.4: 文件已存在 ===");
            setUpAdminSecurityContext();

            String fileName = uniqueName("duplicate") + ".txt";
            MockMultipartFile mockFile = new MockMultipartFile(
                    "file", fileName, "text/plain", "First upload".getBytes());

            fileExplorerController.uploadFile("local", "/", null, mockFile);

            MockMultipartFile duplicateFile = new MockMultipartFile(
                    "file", fileName, "text/plain", "Second upload".getBytes());

            Result<Void> result = fileExplorerController.uploadFile("local", "/", null, duplicateFile);

            assertNotEquals(200, result.getCode(), "上传已存在文件应返回非200");
            System.out.println("  返回code: " + result.getCode());
            System.out.println("  返回消息: " + result.getMessage());
            System.out.println("  文件已存在被正确拒绝 ✓");
        }
    }

    @Nested
    @DisplayName("场景6: 获取支持的存储类型")
    class StorageTypesTests {

        @Test
        @DisplayName("✅ 返回 [local, hdfs, s3]")
        void testGetStorageTypes() {
            System.out.println("\n=== 场景6.1: 获取支持的存储类型 ===");

            Result<List<String>> result = fileExplorerController.getStorageTypes();

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "应返回200");
            assertNotNull(result.getData(), "存储类型列表不应为null");

            List<String> types = result.getData();
            assertTrue(types.contains("local"), "应包含local");
            assertTrue(types.contains("hdfs"), "应包含hdfs");
            assertTrue(types.contains("s3"), "应包含s3");
            assertEquals(3, types.size(), "应有3种存储类型");

            System.out.println("  支持的存储类型: " + types);
            System.out.println("  存储类型列表正确 ✓");
        }
    }

    @Nested
    @DisplayName("场景7: 权限控制")
    class PermissionControlTests {

        @Test
        @DisplayName("❌ 所有写操作需要写权限 - 非ADMIN无权限用户")
        void testWriteOperations_RequireWritePermission() {
            System.out.println("\n=== 场景7.1: 所有写操作需要写权限 ===");
            setUpGuestSecurityContext();

            BusinessException createEx = assertThrows(BusinessException.class, () -> {
                fileExplorerController.createDirectory("local", "/", "no_perm_dir", null);
            }, "创建目录应需要写权限");
            assertEquals(403, createEx.getCode(), "创建目录异常code应为403");
            System.out.println("  createDirectory: 403 ✓");

            BusinessException deleteEx = assertThrows(BusinessException.class, () -> {
                fileExplorerController.deleteFile("local", "/no_perm_file", null);
            }, "删除文件应需要写权限");
            assertEquals(403, deleteEx.getCode(), "删除文件异常code应为403");
            System.out.println("  deleteFile: 403 ✓");

            BusinessException renameEx = assertThrows(BusinessException.class, () -> {
                fileExplorerController.renameFile("local", "/no_perm_file", "new_name", null);
            }, "重命名应需要写权限");
            assertEquals(403, renameEx.getCode(), "重命名异常code应为403");
            System.out.println("  renameFile: 403 ✓");

            MockMultipartFile mockFile = new MockMultipartFile(
                    "file", "upload.txt", "text/plain", "data".getBytes());
            Result<Void> uploadResult = fileExplorerController.uploadFile("local", "/", null, mockFile);
            assertNotEquals(200, uploadResult.getCode(), "上传应需要写权限");
            System.out.println("  uploadFile: " + uploadResult.getCode() + " ✓");

            System.out.println("  所有写操作需要写权限 ✓");
        }

        @Test
        @DisplayName("❌ 所有读操作需要读权限 - 非ADMIN无权限用户")
        void testReadOperations_RequireReadPermission() {
            System.out.println("\n=== 场景7.2: 所有读操作需要读权限 ===");
            setUpGuestSecurityContext();

            BusinessException listEx = assertThrows(BusinessException.class, () -> {
                fileExplorerController.listFiles("local", "/", null);
            }, "列出文件应需要读权限");
            assertEquals(403, listEx.getCode(), "列出文件异常code应为403");
            System.out.println("  listFiles: 403 ✓");

            System.out.println("  所有读操作需要读权限 ✓");
        }

        @Test
        @DisplayName("✅ ADMIN绕过权限检查")
        void testAdmin_BypassPermissionCheck() {
            System.out.println("\n=== 场景7.3: ADMIN绕过权限检查 ===");
            setUpAdminSecurityContext();

            assertDoesNotThrow(() -> {
                fileExplorerController.listFiles("local", "/", null);
            }, "ADMIN列出文件不应抛出异常");
            System.out.println("  listFiles: ADMIN通过 ✓");

            String adminDir = uniqueName("admin_dir");
            assertDoesNotThrow(() -> {
                fileExplorerController.createDirectory("local", "/", adminDir, null);
            }, "ADMIN创建目录不应抛出异常");
            System.out.println("  createDirectory: ADMIN通过 ✓");

            assertDoesNotThrow(() -> {
                fileExplorerController.deleteFile("local", "/" + adminDir, null);
            }, "ADMIN删除文件不应抛出异常");
            System.out.println("  deleteFile: ADMIN通过 ✓");

            System.out.println("  ADMIN绕过所有权限检查 ✓");
        }
    }
}
