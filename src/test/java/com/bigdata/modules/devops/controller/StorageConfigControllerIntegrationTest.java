package com.bigdata.modules.devops.controller;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.security.CustomUserDetails;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.devops.dto.StorageConfigDTO;
import com.bigdata.modules.devops.entity.StorageConfig;
import com.bigdata.modules.devops.mapper.StorageConfigMapper;
import com.bigdata.modules.devops.vo.StorageConfigVO;
import com.bigdata.modules.devops.vo.StorageConnectionTestVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.annotation.Rollback;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
@Rollback
@DisplayName("DevOps模块 - StorageConfigController存储配置集成测试")
class StorageConfigControllerIntegrationTest {

    @Autowired
    private StorageConfigController storageConfigController;

    @Autowired
    private StorageConfigMapper storageConfigMapper;

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

    private void setUpUserSecurityContext() {
        List<SimpleGrantedAuthority> authorities = Arrays.asList(
                new SimpleGrantedAuthority("ROLE_USER")
        );
        CustomUserDetails userDetails = new CustomUserDetails(
                2L, "normaluser", 1L, Arrays.asList("ROLE_USER"), false, authorities, true);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private StorageConfigDTO buildValidDTO(String name, String type) {
        StorageConfigDTO dto = new StorageConfigDTO();
        dto.setStorageName(name);
        dto.setStorageType(type);
        switch (type) {
            case "hdfs":
                dto.setEndpoint("hdfs://localhost:9000");
                dto.setRootPath("/user/hadoop");
                break;
            case "s3":
                dto.setEndpoint("https://s3.amazonaws.com");
                dto.setBucketName("test-bucket");
                dto.setAccessKey("testAccessKey");
                dto.setSecretKey("testSecretKey");
                dto.setRootPath("/data");
                break;
            case "juicefs":
                dto.setEndpoint("jfs://myjfs/");
                dto.setLocalMountPath("/mnt/juicefs");
                dto.setRootPath("/data");
                break;
            case "local":
                dto.setRootPath("/tmp/storage");
                break;
            default:
                dto.setEndpoint("http://localhost:9000");
        }
        return dto;
    }

    private Long createStorageInDB(String name, String type) {
        StorageConfig config = new StorageConfig();
        config.setStorageName(name);
        config.setStorageType(type);
        config.setEndpoint("http://localhost:9000");
        config.setStatus(1);
        config.setDeleted(0);
        storageConfigMapper.insert(config);
        return config.getId();
    }

    @Nested
    @DisplayName("场景1: 存储配置CRUD操作")
    class CrudTests {

        @Test
        @DisplayName("✅ 创建HDFS存储配置 - ADMIN用户成功创建")
        void testCreateHDFS_Success() {
            System.out.println("\n=== 场景1.1: 创建HDFS存储配置 ===");
            setUpAdminSecurityContext();

            StorageConfigDTO dto = buildValidDTO("TestHDFS", "hdfs");

            Result<Void> result = storageConfigController.save(dto);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "创建应返回200");
            System.out.println("  创建HDFS存储配置成功 ✓");
        }

        @Test
        @DisplayName("✅ 创建S3存储配置 - ADMIN用户成功创建")
        void testCreateS3_Success() {
            System.out.println("\n=== 场景1.2: 创建S3存储配置 ===");
            setUpAdminSecurityContext();

            StorageConfigDTO dto = buildValidDTO("TestS3", "s3");

            Result<Void> result = storageConfigController.save(dto);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "创建应返回200");
            System.out.println("  创建S3存储配置成功 ✓");
        }

        @Test
        @DisplayName("✅ 创建JuiceFS存储配置 - ADMIN用户成功创建")
        void testCreateJuiceFS_Success() {
            System.out.println("\n=== 场景1.3: 创建JuiceFS存储配置 ===");
            setUpAdminSecurityContext();

            StorageConfigDTO dto = buildValidDTO("TestJuiceFS", "juicefs");

            Result<Void> result = storageConfigController.save(dto);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "创建应返回200");
            System.out.println("  创建JuiceFS存储配置成功 ✓");
        }

        @Test
        @DisplayName("✅ 创建Local存储配置 - ADMIN用户成功创建")
        void testCreateLocal_Success() {
            System.out.println("\n=== 场景1.4: 创建Local存储配置 ===");
            setUpAdminSecurityContext();

            StorageConfigDTO dto = buildValidDTO("TestLocal", "local");

            Result<Void> result = storageConfigController.save(dto);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "创建应返回200");
            System.out.println("  创建Local存储配置成功 ✓");
        }

        @Test
        @DisplayName("✅ 更新存储配置 - ADMIN用户成功更新")
        void testUpdate_Success() {
            System.out.println("\n=== 场景1.5: 更新存储配置 ===");
            setUpAdminSecurityContext();

            Long id = createStorageInDB("UpdateTestHDFS", "hdfs");

            StorageConfigDTO dto = new StorageConfigDTO();
            dto.setId(id);
            dto.setStorageName("UpdatedHDFS");
            dto.setStorageType("hdfs");
            dto.setEndpoint("hdfs://new-host:9000");
            dto.setRootPath("/user/updated");

            Result<Void> result = storageConfigController.update(dto);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "更新应返回200");

            StorageConfig updated = storageConfigMapper.selectById(id);
            assertEquals("UpdatedHDFS", updated.getStorageName(), "存储名称应已更新");
            assertEquals("hdfs://new-host:9000", updated.getEndpoint(), "endpoint应已更新");

            System.out.println("  更新后名称: " + updated.getStorageName());
            System.out.println("  更新后endpoint: " + updated.getEndpoint());
            System.out.println("  更新存储配置成功 ✓");
        }

        @Test
        @DisplayName("✅ 查询配置列表 - 分页+名称筛选")
        void testGetList_WithFilter() {
            System.out.println("\n=== 场景1.6: 查询配置列表（分页+筛选） ===");
            setUpAdminSecurityContext();

            createStorageInDB("FilterTestHDFS", "hdfs");
            createStorageInDB("FilterTestS3", "s3");

            Result<Page<StorageConfigVO>> result = storageConfigController.getList(1, 10, "FilterTest", null);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "查询应返回200");
            assertNotNull(result.getData(), "分页数据不应为null");
            assertTrue(result.getData().getRecords().size() >= 2, "筛选结果应至少2条");

            System.out.println("  名称筛选结果数: " + result.getData().getRecords().size());
            result.getData().getRecords().forEach(vo ->
                    System.out.println("    - " + vo.getStorageName() + " (" + vo.getStorageType() + ")"));
            System.out.println("  名称筛选查询成功 ✓");
        }

        @Test
        @DisplayName("✅ 查询配置列表 - 类型筛选")
        void testGetList_FilterByType() {
            System.out.println("\n=== 场景1.7: 查询配置列表（类型筛选） ===");
            setUpAdminSecurityContext();

            createStorageInDB("TypeFilterHDFS", "hdfs");
            createStorageInDB("TypeFilterS3", "s3");

            Result<Page<StorageConfigVO>> result = storageConfigController.getList(1, 10, null, "hdfs");

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "查询应返回200");
            assertNotNull(result.getData(), "分页数据不应为null");

            result.getData().getRecords().forEach(vo ->
                    assertEquals("hdfs", vo.getStorageType(), "筛选结果类型应为hdfs"));

            System.out.println("  hdfs类型筛选结果数: " + result.getData().getRecords().size());
            System.out.println("  类型筛选查询成功 ✓");
        }

        @Test
        @DisplayName("✅ 查询所有存储配置 - 仅活跃的")
        void testGetAllStorages_OnlyActive() {
            System.out.println("\n=== 场景1.8: 查询所有活跃存储配置 ===");
            setUpAdminSecurityContext();

            createStorageInDB("ActiveStorage1", "hdfs");
            createStorageInDB("ActiveStorage2", "s3");

            Result<List<StorageConfigVO>> result = storageConfigController.getAllStorages();

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "查询应返回200");
            assertNotNull(result.getData(), "存储配置列表不应为null");

            result.getData().forEach(vo ->
                    assertEquals(1, vo.getStatus(), "返回的配置应均为活跃状态(status=1)"));

            System.out.println("  活跃存储配置数量: " + result.getData().size());
            System.out.println("  查询活跃存储配置成功 ✓");
        }

        @Test
        @DisplayName("✅ 删除配置 - ADMIN用户成功删除")
        void testDelete_Success() {
            System.out.println("\n=== 场景1.9: 删除存储配置 ===");
            setUpAdminSecurityContext();

            Long id = createStorageInDB("DeleteTestStorage", "hdfs");

            Result<Void> result = storageConfigController.delete(id);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "删除应返回200");

            System.out.println("  删除存储配置成功 ✓");
        }

        @Test
        @DisplayName("❌ 不存在ID删除抛BusinessException")
        void testDelete_NonExistentId() {
            System.out.println("\n=== 场景1.10: 删除不存在的存储配置 ===");
            setUpAdminSecurityContext();

            Long nonExistentId = 99999999L;

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                storageConfigController.delete(nonExistentId);
            }, "删除不存在的配置应抛出BusinessException");

            assertTrue(exception.getMessage().contains("不存在"),
                    "异常消息应包含'不存在'");
            System.out.println("  异常消息: " + exception.getMessage());
            System.out.println("  删除不存在配置被正确拒绝 ✓");
        }
    }

    @Nested
    @DisplayName("场景2: 连接测试")
    class ConnectionTests {

        @Test
        @DisplayName("✅ 测试已有配置的连接（testConnection）")
        void testConnection_ExistingConfig() {
            System.out.println("\n=== 场景2.1: 测试已有配置的连接 ===");
            setUpAdminSecurityContext();

            Long id = createStorageInDB("ConnectionTestHDFS", "hdfs");

            Result<StorageConnectionTestVO> result = storageConfigController.testConnection(id);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "测试连接应返回200");
            assertNotNull(result.getData(), "连接测试结果不应为null");

            StorageConnectionTestVO testResult = result.getData();
            assertNotNull(testResult.getMessage(), "消息不应为null");
            assertNotNull(testResult.getStorageType(), "存储类型不应为null");

            System.out.println("  连接成功: " + testResult.isSuccess());
            System.out.println("  消息: " + testResult.getMessage());
            System.out.println("  存储类型: " + testResult.getStorageType());
            System.out.println("  测试连接返回结果正确 ✓");
        }

        @Test
        @DisplayName("❌ 不存在ID抛BusinessException")
        void testConnection_NonExistentId() {
            System.out.println("\n=== 场景2.2: 测试连接 - 不存在的ID ===");
            setUpAdminSecurityContext();

            Long nonExistentId = 99999999L;

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                storageConfigController.testConnection(nonExistentId);
            }, "不存在的配置ID应抛出BusinessException");

            assertTrue(exception.getMessage().contains("不存在"),
                    "异常消息应包含'不存在'");
            System.out.println("  异常消息: " + exception.getMessage());
            System.out.println("  不存在ID测试连接被正确拒绝 ✓");
        }
    }

    @Nested
    @DisplayName("场景3: 获取存储类型")
    class StorageTypesTests {

        @Test
        @DisplayName("✅ 返回 [hdfs, s3, juicefs]")
        void testGetStorageTypes() {
            System.out.println("\n=== 场景3.1: 获取存储类型列表 ===");

            Result<List<String>> result = storageConfigController.getStorageTypes();

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "应返回200");
            assertNotNull(result.getData(), "存储类型列表不应为null");

            List<String> types = result.getData();
            assertTrue(types.contains("hdfs"), "应包含hdfs");
            assertTrue(types.contains("s3"), "应包含s3");
            assertTrue(types.contains("juicefs"), "应包含juicefs");
            assertEquals(3, types.size(), "应有3种存储类型");

            System.out.println("  支持的存储类型: " + types);
            System.out.println("  存储类型列表正确 ✓");
        }
    }

    @Nested
    @DisplayName("场景4: 权限控制")
    class PermissionControlTests {

        @Test
        @DisplayName("❌ 普通用户创建存储配置 - 抛AccessDeniedException")
        void testCreate_NormalUser_Denied() {
            System.out.println("\n=== 场景4.1: 普通用户创建存储配置 ===");
            setUpUserSecurityContext();

            StorageConfigDTO dto = buildValidDTO("UserCreateTest", "hdfs");

            assertThrows(AccessDeniedException.class, () -> {
                storageConfigController.save(dto);
            }, "普通用户创建存储配置应抛出AccessDeniedException");

            System.out.println("  普通用户创建被拒绝(AccessDeniedException) ✓");
        }

        @Test
        @DisplayName("❌ 普通用户更新存储配置 - 抛AccessDeniedException")
        void testUpdate_NormalUser_Denied() {
            System.out.println("\n=== 场景4.2: 普通用户更新存储配置 ===");
            setUpAdminSecurityContext();
            Long id = createStorageInDB("UserUpdateTest", "hdfs");

            setUpUserSecurityContext();

            StorageConfigDTO dto = new StorageConfigDTO();
            dto.setId(id);
            dto.setStorageName("UserUpdated");
            dto.setStorageType("hdfs");

            assertThrows(AccessDeniedException.class, () -> {
                storageConfigController.update(dto);
            }, "普通用户更新存储配置应抛出AccessDeniedException");

            System.out.println("  普通用户更新被拒绝(AccessDeniedException) ✓");
        }

        @Test
        @DisplayName("❌ 普通用户删除存储配置 - 抛AccessDeniedException")
        void testDelete_NormalUser_Denied() {
            System.out.println("\n=== 场景4.3: 普通用户删除存储配置 ===");
            setUpAdminSecurityContext();
            Long id = createStorageInDB("UserDeleteTest", "hdfs");

            setUpUserSecurityContext();

            assertThrows(AccessDeniedException.class, () -> {
                storageConfigController.delete(id);
            }, "普通用户删除存储配置应抛出AccessDeniedException");

            System.out.println("  普通用户删除被拒绝(AccessDeniedException) ✓");
        }

        @Test
        @DisplayName("✅ 普通用户可以查看配置列表")
        void testGetList_NormalUser_Allowed() {
            System.out.println("\n=== 场景4.4: 普通用户查看配置列表 ===");
            setUpUserSecurityContext();

            Result<Page<StorageConfigVO>> result = storageConfigController.getList(1, 10, null, null);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "普通用户查看列表应返回200");
            System.out.println("  普通用户查看配置列表成功 ✓");
        }

        @Test
        @DisplayName("✅ 普通用户可以查看所有存储配置")
        void testGetAllStorages_NormalUser_Allowed() {
            System.out.println("\n=== 场景4.5: 普通用户查看所有存储配置 ===");
            setUpUserSecurityContext();

            Result<List<StorageConfigVO>> result = storageConfigController.getAllStorages();

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "普通用户查看所有存储配置应返回200");
            System.out.println("  普通用户查看所有存储配置成功 ✓");
        }

        @Test
        @DisplayName("✅ 普通用户可以查看存储类型")
        void testGetStorageTypes_NormalUser_Allowed() {
            System.out.println("\n=== 场景4.6: 普通用户查看存储类型 ===");
            setUpUserSecurityContext();

            Result<List<String>> result = storageConfigController.getStorageTypes();

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "普通用户查看存储类型应返回200");
            System.out.println("  普通用户查看存储类型成功 ✓");
        }

        @Test
        @DisplayName("✅ 普通用户可以测试连接")
        void testTestConnection_NormalUser_Allowed() {
            System.out.println("\n=== 场景4.7: 普通用户测试连接 ===");
            setUpAdminSecurityContext();
            Long id = createStorageInDB("UserConnTest", "hdfs");

            setUpUserSecurityContext();

            Result<StorageConnectionTestVO> result = storageConfigController.testConnection(id);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "普通用户测试连接应返回200");
            System.out.println("  普通用户测试连接成功 ✓");
        }

        @Test
        @DisplayName("✅ ADMIN用户可以执行所有操作")
        void testAdmin_AllOperations() {
            System.out.println("\n=== 场景4.8: ADMIN用户执行所有操作 ===");
            setUpAdminSecurityContext();

            StorageConfigDTO createDto = buildValidDTO("AdminFullTest", "hdfs");
            Result<Void> createResult = storageConfigController.save(createDto);
            assertEquals(200, createResult.getCode(), "ADMIN创建应返回200");
            System.out.println("  创建: 200 ✓");

            Result<Page<StorageConfigVO>> listResult = storageConfigController.getList(1, 10, null, null);
            assertEquals(200, listResult.getCode(), "ADMIN查询列表应返回200");
            System.out.println("  查询列表: 200 ✓");

            Result<List<StorageConfigVO>> allResult = storageConfigController.getAllStorages();
            assertEquals(200, allResult.getCode(), "ADMIN查询所有应返回200");
            System.out.println("  查询所有: 200 ✓");

            Result<List<String>> typesResult = storageConfigController.getStorageTypes();
            assertEquals(200, typesResult.getCode(), "ADMIN查询类型应返回200");
            System.out.println("  查询类型: 200 ✓");

            System.out.println("  ADMIN用户执行所有操作成功 ✓");
        }
    }
}
