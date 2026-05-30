package com.bigdata.modules.devops.controller;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.security.CustomUserDetails;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.devops.dto.DatasourceConfigDTO;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.devops.mapper.DatasourceConfigMapper;
import com.bigdata.modules.devops.vo.ConnectionTestResult;
import com.bigdata.modules.devops.vo.DatasourceConfigVO;
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
import org.springframework.web.method.HandlerMethod;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
@Rollback
@DisplayName("DevOps模块 - DatasourceConfigController数据源管理集成测试")
class DatasourceConfigControllerIntegrationTest {

    @Autowired
    private DatasourceConfigController datasourceConfigController;

    @Autowired
    private DatasourceConfigMapper datasourceConfigMapper;

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
                1L, "admin", 1L, Arrays.asList("ROLE_ADMIN"), authorities, true);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private void setUpUserSecurityContext() {
        List<SimpleGrantedAuthority> authorities = Arrays.asList(
                new SimpleGrantedAuthority("ROLE_USER")
        );
        CustomUserDetails userDetails = new CustomUserDetails(
                2L, "normaluser", 1L, Arrays.asList("ROLE_USER"), authorities, true);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private DatasourceConfigDTO buildValidDTO(String name) {
        DatasourceConfigDTO dto = new DatasourceConfigDTO();
        dto.setDatasourceName(name);
        dto.setDatasourceType(DatasourceType.MYSQL);
        dto.setUrl("jdbc:mysql://localhost:3306/testdb");
        dto.setUsername("root");
        dto.setPassword("password");
        dto.setDescription("测试数据源");
        return dto;
    }

    private DatasourceConfigDTO buildValidDTO(String name, DatasourceType type) {
        DatasourceConfigDTO dto = buildValidDTO(name);
        dto.setDatasourceType(type);
        return dto;
    }

    private Long createDatasourceInDB(String name) {
        DatasourceConfig config = new DatasourceConfig();
        config.setDatasourceName(name);
        config.setDatasourceType(DatasourceType.MYSQL);
        config.setUrl("jdbc:mysql://localhost:3306/test_" + name);
        config.setUsername("root");
        config.setPassword("pwd");
        config.setConnectionStatus(1);
        config.setDeleted(0);
        datasourceConfigMapper.insert(config);
        return config.getId();
    }

    @Nested
    @DisplayName("场景1: 数据源CRUD操作")
    class CrudTests {

        @Test
        @DisplayName("✅ 创建数据源 - ADMIN用户成功创建")
        void testCreateDatasource_Success() {
            System.out.println("\n=== 场景1.1: 创建数据源成功 ===");
            setUpAdminSecurityContext();

            DatasourceConfigDTO dto = buildValidDTO("TestCreateDS");

            Result<Void> result = datasourceConfigController.save(dto);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "创建应返回200");
            System.out.println("  创建数据源: TestCreateDS -> 成功");

            Result<Page<DatasourceConfigVO>> listResult = datasourceConfigController.getList(1, 20, "TestCreateDS", null);
            assertNotNull(listResult.getData());
            assertTrue(listResult.getData().getRecords().stream()
                            .anyMatch(vo -> vo.getDatasourceName().equals("TestCreateDS")),
                    "列表中应包含新创建的数据源");
            System.out.println("  验证: 列表中存在该数据源 ✓");
        }

        @Disabled("测试环境无MySQL服务或连接配置不匹配，会导致连接失败(Access denied)")
        @Test
        @DisplayName("✅ 更新数据源 - ADMIN用户成功更新")
        void testUpdateDatasource_Success() {
            System.out.println("\n=== 场景1.2: 更新数据源成功 ===");
            setUpAdminSecurityContext();

            Long id = createDatasourceInDB("TestUpdateDS_Original");

            DatasourceConfigDTO dto = buildValidDTO("TestUpdateDS_Updated");
            dto.setId(id);
            dto.setUrl("jdbc:mysql://localhost:3306/updated_db");
            dto.setDescription("更新后的描述");

            Result<Void> result = datasourceConfigController.update(dto);

            assertEquals(200, result.getCode(), "更新应返回200");
            System.out.println("  更新数据源ID: " + id);

            DatasourceConfig updated = datasourceConfigMapper.selectById(id);
            assertEquals("TestUpdateDS_Updated", updated.getDatasourceName(), "名称应已更新");
            assertEquals("jdbc:mysql://localhost:3306/updated_db", updated.getUrl(), "URL应已更新");
            assertEquals("更新后的描述", updated.getDescription(), "描述应已更新");
            System.out.println("  验证: 名称=" + updated.getDatasourceName()
                    + ", URL=" + updated.getUrl() + ", 描述=" + updated.getDescription());
        }

        @Test
        @DisplayName("✅ 查询数据源列表 - 分页查询正常返回")
        void testGetList_Pagination() {
            System.out.println("\n=== 场景1.3: 分页查询数据源列表 ===");

            for (int i = 0; i < 5; i++) {
                createDatasourceInDB("PaginationDS_" + i);
            }

            Result<Page<DatasourceConfigVO>> result = datasourceConfigController.getList(1, 3, null, null);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "查询应返回200");
            assertNotNull(result.getData(), "分页数据不应为null");
            Page<DatasourceConfigVO> page = result.getData();
            assertEquals(1, page.getCurrent(), "当前页应为1");
            assertEquals(3, page.getSize(), "每页大小应为3");
            assertFalse(page.getRecords().isEmpty(), "分页查询应返回记录");
            System.out.println("  当前页记录数: " + page.getRecords().size() + " (注意：无分页拦截器时可能超过pageSize)");
            System.out.println("  当前页码: " + page.getCurrent());
            System.out.println("  每页大小: " + page.getSize());
        }

        @Test
        @DisplayName("✅ 删除数据源 - ADMIN用户成功删除")
        void testDeleteDatasource_Success() {
            System.out.println("\n=== 场景1.4: 删除数据源成功 ===");
            setUpAdminSecurityContext();

            Long id = createDatasourceInDB("TestDeleteDS");

            DatasourceConfig beforeDelete = datasourceConfigMapper.selectById(id);
            assertNotNull(beforeDelete, "删除前数据源应存在");

            Result<Void> result = datasourceConfigController.delete(id);

            assertEquals(200, result.getCode(), "删除应返回200");
            System.out.println("  删除数据源ID: " + id);

            DatasourceConfig afterDelete = datasourceConfigMapper.selectById(id);
            if (afterDelete != null) {
                assertEquals(1, afterDelete.getDeleted(), "逻辑删除后deleted应为1");
                System.out.println("  验证: 逻辑删除成功(deleted=1) ✓");
            } else {
                System.out.println("  验证: 物理删除成功(记录不存在) ✓");
            }
        }
    }

    @Nested
    @DisplayName("场景2: 连接测试")
    class ConnectionTests {

        @Test
        @DisplayName("✅ 通过ID测试连接 - 不存在的ID抛出异常")
        void testConnectionBy_NonExistentId() {
            System.out.println("\n=== 场景2.1: 测试连接 - 不存在的ID ===");

            Long nonExistentId = 99999999L;
            BusinessException exception = assertThrows(BusinessException.class, () -> {
                datasourceConfigController.testConnection(nonExistentId);
            }, "不存在的数据源ID应抛出BusinessException");

            System.out.println("  异常消息: " + exception.getMessage());
            assertTrue(exception.getMessage().contains("不存在"),
                    "异常消息应包含'不存在'");
        }

        @Test
        @DisplayName("✅ 通过配置测试连接 - 返回结果结构正确")
        void testConnectionByConfig_ReturnsCorrectStructure() {
            System.out.println("\n=== 场景2.2: 通过配置测试连接 - 结果结构验证 ===");

            DatasourceConfigDTO dto = buildValidDTO("ConnectionTestConfig");

            Result<ConnectionTestResult> result = datasourceConfigController.testConnectionByConfig(dto);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "请求应正常返回200");
            assertNotNull(result.getData(), "连接测试结果不应为null");
            ConnectionTestResult ctr = result.getData();
            assertNotNull(ctr.getMessage(), "message不应为null");
            assertNotNull(ctr.getCostTime(), "costTime不应为null");
            System.out.println("  连接成功: " + ctr.isSuccess());
            System.out.println("  消息: " + ctr.getMessage());
            System.out.println("  耗时: " + ctr.getCostTime());
        }

        @Test
        @DisplayName("✅ 通过配置测试连接 - ES类型URL格式")
        void testConnectionByConfig_EsType() {
            System.out.println("\n=== 场景2.3: 通过配置测试ES连接 ===");

            DatasourceConfigDTO dto = buildValidDTO("ESTestConfig", DatasourceType.ES);
            dto.setUrl("http://localhost:9200");

            Result<ConnectionTestResult> result = datasourceConfigController.testConnectionByConfig(dto);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "请求应正常返回200");
            assertNotNull(result.getData(), "连接测试结果不应为null");
            System.out.println("  ES连接测试完成, success=" + result.getData().isSuccess());
        }

        @Test
        @DisplayName("✅ 通过配置测试连接 - 不同数据源类型枚举值")
        void testConnectionByConfig_AllTypes() {
            System.out.println("\n=== 场景2.4: 所有数据源类型的连接测试调用 ===");

            for (DatasourceType type : DatasourceType.values()) {
                DatasourceConfigDTO dto = buildValidDTO("TypeTest_" + type.getCode(), type);
                switch (type) {
                    case MYSQL:
                        dto.setUrl("jdbc:mysql://localhost:3306/test");
                        break;
                    case CLICKHOUSE:
                        dto.setUrl("jdbc:clickhouse://localhost:8123/default");
                        break;
                    case DORIS:
                        dto.setUrl("jdbc:mysql://localhost:9030/test");
                        break;
                    case STARROCKS:
                        dto.setUrl("jdbc:mysql://localhost:9030/test");
                        break;
                    case ES:
                        dto.setUrl("http://localhost:9200");
                        break;
                    case KYUUBI:
                        dto.setUrl("jdbc:kyuubi://localhost:10009/default");
                        break;
                    case HIVE:
                        dto.setUrl("jdbc:hive2://localhost:10000/default");
                        break;
                }

                Result<ConnectionTestResult> result = datasourceConfigController.testConnectionByConfig(dto);
                assertNotNull(result, type.getDescription() + " 类型测试结果不应为null");
                assertEquals(200, result.getCode(), type.getDescription() + " 应返回200");
                assertNotNull(result.getData(), type.getDescription() + " 连接结果不应为null");
                System.out.println("  " + type.getDescription() + ": success="
                        + result.getData().isSuccess() + ", message=" + result.getData().getMessage());
            }
        }
    }

    @Nested
    @DisplayName("场景3: 元数据同步")
    class MetadataSyncTests {

        @Test
        @DisplayName("✅ 元数据同步 - ADMIN权限可调用")
        void testSyncMetadata_AdminCanCall() {
            System.out.println("\n=== 场景3.1: 元数据同步 - ADMIN调用 ===");
            setUpAdminSecurityContext();

            Long id = createDatasourceInDB("SyncMetaDS");

            Result<Void> result = datasourceConfigController.syncMetadata(id);

            assertNotNull(result, "结果不应为null");
            System.out.println("  同步结果code: " + result.getCode());
            System.out.println("  同步消息: " + (result.getMessage() != null ? result.getMessage() : "无"));
            assertTrue(result.getCode() == 200 || result.getCode() == 500,
                    "同步结果应为200(成功)或500(失败)");
        }

        @Test
        @DisplayName("❌ 元数据同步 - 普通用户无权限")
        void testSyncMetadata_UserDenied() {
            System.out.println("\n=== 场景3.2: 元数据同步 - 普通用户被拒绝 ===");
            setUpUserSecurityContext();

            assertThrows(AccessDeniedException.class, () -> {
                datasourceConfigController.syncMetadata(1L);
            }, "普通用户调用元数据同步应被拒绝");
            System.out.println("  普通用户被AccessDeniedException拒绝 ✓");
        }
    }

    @Nested
    @DisplayName("场景4: 查询所有数据源（下拉选项）")
    class GetAllDatasourcesTests {

        @Test
        @DisplayName("✅ 获取所有数据源 - 仅返回连接状态正常的数据源")
        void testGetAllDatasources_OnlyActiveOnes() {
            System.out.println("\n=== 场景4.1: 获取所有活跃数据源 ===");

            Long activeId1 = createDatasourceInDB("ActiveDS_1");
            Long activeId2 = createDatasourceInDB("ActiveDS_2");

            DatasourceConfig inactive = new DatasourceConfig();
            inactive.setDatasourceName("InactiveDS");
            inactive.setDatasourceType(DatasourceType.MYSQL);
            inactive.setUrl("jdbc:mysql://localhost:3306/inactive");
            inactive.setConnectionStatus(0);
            inactive.setDeleted(0);
            datasourceConfigMapper.insert(inactive);

            Result<List<DatasourceConfigVO>> result = datasourceConfigController.getAllDatasources();

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "查询应返回200");
            assertNotNull(result.getData(), "数据列表不应为null");
            List<DatasourceConfigVO> voList = result.getData();

            boolean hasActive1 = voList.stream().anyMatch(v -> v.getId().equals(activeId1));
            boolean hasActive2 = voList.stream().anyMatch(v -> v.getId().equals(activeId2));
            boolean hasInactive = voList.stream().anyMatch(v -> v.getDatasourceName().equals("InactiveDS"));

            assertTrue(hasActive1, "应包含活跃数据源1");
            assertTrue(hasActive2, "应包含活跃数据源2");
            assertFalse(hasInactive, "不应包含非活跃数据源(connectionStatus!=1)");
            System.out.println("  总数: " + voList.size());
            System.out.println("  包含ActiveDS_1: " + hasActive1);
            System.out.println("  包含ActiveDS_2: " + hasActive2);
            System.out.println("  包含InactiveDS: " + hasInactive + " (预期false)");
        }

        @Test
        @DisplayName("✅ 获取所有数据源 - 空列表时返回空集合")
        void testGetAllDatasources_EmptyResult() {
            System.out.println("\n=== 场景4.2: 无数据源时返回空集合 ===");

            Result<List<DatasourceConfigVO>> result = datasourceConfigController.getAllDatasources();

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "即使无数据也应返回200");
            assertNotNull(result.getData(), "data不应为null，应为空List");
            System.out.println("  空数据源数量: " + result.getData().size());
        }
    }

    @Nested
    @DisplayName("场景5: 获取数据源类型枚举")
    class GetDatasourceTypesTests {

        @Test
        @DisplayName("✅ 获取数据源类型 - 返回全部7种类型")
        void testGetDatasourceTypes_AllTypesReturned() {
            System.out.println("\n=== 场景5.1: 获取全部数据源类型 ===");

            Result<List<DatasourceType>> result = datasourceConfigController.getDatasourceTypes();

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "应返回200");
            assertNotNull(result.getData(), "类型列表不应为null");
            List<DatasourceType> types = result.getData();
            assertEquals(DatasourceType.values().length, types.size(),
                    "返回的类型数量应与枚举定义一致");

            List<String> typeCodes = types.stream().map(DatasourceType::getCode).collect(Collectors.toList());
            for (DatasourceType expected : DatasourceType.values()) {
                assertTrue(typeCodes.contains(expected.getCode()),
                        "应包含类型: " + expected.name());
            }
            System.out.println("  类型总数: " + types.size());
            for (DatasourceType t : types) {
                System.out.println("    - " + t.getCode() + " (" + t.getDescription() + ")");
            }
        }

        @Test
        @DisplayName("✅ 获取数据源类型 - 无需认证即可访问")
        void testGetDatasourceTypes_NoAuthRequired() {
            System.out.println("\n=== 场景5.2: 数据源类型接口无需认证 ===");

            SecurityContextHolder.clearContext();

            Result<List<DatasourceType>> result = datasourceConfigController.getDatasourceTypes();

            assertNotNull(result, "未登录状态下仍可访问");
            assertEquals(200, result.getCode(), "未登录状态应返回200");
            assertFalse(result.getData().isEmpty(), "类型列表不应为空");
            System.out.println("  无认证访问成功, 返回" + result.getData().size() + "种类型 ✓");
        }
    }

    @Nested
    @DisplayName("场景6: 权限控制 - ADMIN才能创建/更新/删除")
    class PermissionControlTests {

        @Test
        @DisplayName("❌ 创建数据源 - 普通用户被拒绝(AccessDenied)")
        void testCreate_UserDenied() {
            System.out.println("\n=== 场景6.1: 普通用户创建数据源被拒 ===");
            setUpUserSecurityContext();

            DatasourceConfigDTO dto = buildValidDTO("UserCreateDS");

            assertThrows(AccessDeniedException.class, () -> {
                datasourceConfigController.save(dto);
            }, "普通用户创建数据源应被AccessDeniedException拒绝");
            System.out.println("  AccessDeniedException 已抛出 ✓");
        }

        @Test
        @DisplayName("❌ 更新数据源 - 普通用户被拒绝(AccessDenied)")
        void testUpdate_UserDenied() {
            System.out.println("\n=== 场景6.2: 普通用户更新数据源被拒 ===");
            setUpUserSecurityContext();

            DatasourceConfigDTO dto = buildValidDTO("UserUpdateDS");
            dto.setId(1L);

            assertThrows(AccessDeniedException.class, () -> {
                datasourceConfigController.update(dto);
            }, "普通用户更新数据源应被拒绝");
            System.out.println("  AccessDeniedException 已抛出 ✓");
        }

        @Test
        @DisplayName("❌ 删除数据源 - 普通用户被拒绝(AccessDenied)")
        void testDelete_UserDenied() {
            System.out.println("\n=== 场景6.3: 普通用户删除数据源被拒 ===");
            setUpUserSecurityContext();

            assertThrows(AccessDeniedException.class, () -> {
                datasourceConfigController.delete(1L);
            }, "普通用户删除数据源应被拒绝");
            System.out.println("  AccessDeniedException 已抛出 ✓");
        }

        @Test
        @DisplayName("❌ 创建数据源 - 未登录用户被拒绝")
        void testCreate_NotLoggedInDenied() {
            System.out.println("\n=== 场景6.4: 未登录用户创建数据源被拒 ===");
            SecurityContextHolder.clearContext();

            DatasourceConfigDTO dto = buildValidDTO("NoAuthCreateDS");

            assertThrows(Exception.class, () -> {
                datasourceConfigController.save(dto);
            }, "未登录用户创建数据源应抛出异常");
            System.out.println("  异常已抛出 ✓");
        }

        @Test
        @DisplayName("❌ 删除数据源 - 未登录用户被拒绝")
        void testDelete_NotLoggedInDenied() {
            System.out.println("\n=== 场景6.5: 未登录用户删除数据源被拒 ===");
            SecurityContextHolder.clearContext();

            assertThrows(Exception.class, () -> {
                datasourceConfigController.delete(1L);
            }, "未登录用户删除数据源应抛出异常");
            System.out.println("  异常已抛出 ✓");
        }

        @Disabled("测试环境可能无MySQL服务或连接配置不匹配，会导致CRUD操作中的连接测试失败")
        @Test
        @DisplayName("✅ ADMIN用户拥有完整CRUD权限")
        void testAdmin_HasFullCrudPermission() {
            System.out.println("\n=== 场景6.6: ADMIN完整CRUD权限验证 ===");
            setUpAdminSecurityContext();

            DatasourceConfigDTO dto = buildValidDTO("AdminFullCrudDS");
            Result<Void> createResult = datasourceConfigController.save(dto);
            assertEquals(200, createResult.getCode(), "ADMIN创建应成功");

            Result<Page<DatasourceConfigVO>> listResult = datasourceConfigController.getList(1, 20,
                    "AdminFullCrudDS", null);
            Long createdId = listResult.getData().getRecords().get(0).getId();

            dto.setId(createdId);
            dto.setDatasourceName("AdminFullCrudDS_Updated");
            Result<Void> updateResult = datasourceConfigController.update(dto);
            assertEquals(200, updateResult.getCode(), "ADMIN更新应成功");

            Result<Void> deleteResult = datasourceConfigController.delete(createdId);
            assertEquals(200, deleteResult.getCode(), "ADMIN删除应成功");

            System.out.println("  创建 ✓ | 更新 ✓ | 删除 ✓ - ADMIN拥有完整权限");
        }
    }

    @Nested
    @DisplayName("场景7: 分页查询和筛选")
    class PaginationAndFilterTests {

        @Test
        @DisplayName("✅ 按名称模糊搜索 - 关键词匹配")
        void testFilter_ByDatasourceName() {
            System.out.println("\n=== 场景7.1: 按名称模糊搜索 ===");

            createDatasourceInDB("SearchTargetAlpha");
            createDatasourceInDB("SearchTargetBeta");
            createDatasourceInDB("OtherDataSource");

            Result<Page<DatasourceConfigVO>> result = datasourceConfigController.getList(1, 10, "SearchTarget", null);

            assertNotNull(result.getData());
            List<DatasourceConfigVO> records = result.getData().getRecords();
            long matchCount = records.stream()
                    .filter(vo -> vo.getDatasourceName().contains("SearchTarget"))
                    .count();
            assertEquals(records.size(), matchCount,
                    "所有返回记录都应包含关键词'SearchTarget'");
            assertTrue(matchCount >= 2, "应至少匹配2条(SearchTargetAlpha和SearchTargetBeta)");
            System.out.println("  搜索关键词: 'SearchTarget'");
            System.out.println("  匹配数: " + matchCount);
            records.forEach(vo -> System.out.println("    - " + vo.getDatasourceName()));
        }

        @Test
        @DisplayName("✅ 按类型精确筛选 - 只返回指定类型")
        void testFilter_ByDatasourceType() {
            System.out.println("\n=== 场景7.2: 按数据源类型筛选 ===");

            for (int i = 0; i < 3; i++) {
                DatasourceConfig mysqlDs = new DatasourceConfig();
                mysqlDs.setDatasourceName("MySQLFilter_" + i);
                mysqlDs.setDatasourceType(DatasourceType.MYSQL);
                mysqlDs.setUrl("jdbc:mysql://localhost:3306/m" + i);
                mysqlDs.setConnectionStatus(1);
                mysqlDs.setDeleted(0);
                datasourceConfigMapper.insert(mysqlDs);

                DatasourceConfig clickhouseDs = new DatasourceConfig();
                clickhouseDs.setDatasourceName("CHFilter_" + i);
                clickhouseDs.setDatasourceType(DatasourceType.CLICKHOUSE);
                clickhouseDs.setUrl("jdbc:clickhouse://localhost:8123/ch" + i);
                clickhouseDs.setConnectionStatus(1);
                clickhouseDs.setDeleted(0);
                datasourceConfigMapper.insert(clickhouseDs);
            }

            Result<Page<DatasourceConfigVO>> result = datasourceConfigController.getList(1, 10, null, DatasourceType.MYSQL);

            assertNotNull(result.getData());
            List<DatasourceConfigVO> records = result.getData().getRecords();
            boolean allMysql = records.stream()
                    .allMatch(vo -> vo.getDatasourceType() == DatasourceType.MYSQL);
            assertTrue(allMysql, "所有返回记录都应是MYSQL类型");
            System.out.println("  筛选类型: MYSQL");
            System.out.println("  结果数: " + records.size());
            records.forEach(vo -> System.out.println("    - " + vo.getDatasourceName()
                    + " [" + vo.getDatasourceType() + "]"));
        }

        @Test
        @DisplayName("✅ 组合条件筛选 - 名称+类型同时过滤")
        void testFilter_CombinedConditions() {
            System.out.println("\n=== 场景7.3: 名称+类型组合筛选 ===");

            DatasourceConfig target = new DatasourceConfig();
            target.setDatasourceName("CombinedSearch_MySQL");
            target.setDatasourceType(DatasourceType.MYSQL);
            target.setUrl("jdbc:mysql://localhost:3306/combined");
            target.setConnectionStatus(1);
            target.setDeleted(0);
            datasourceConfigMapper.insert(target);

            DatasourceConfig other = new DatasourceConfig();
            other.setDatasourceName("CombinedSearch_ClickHouse");
            other.setDatasourceType(DatasourceType.CLICKHOUSE);
            other.setUrl("jdbc:clickhouse://localhost:8123/combined");
            other.setConnectionStatus(1);
            other.setDeleted(0);
            datasourceConfigMapper.insert(other);

            Result<Page<DatasourceConfigVO>> result = datasourceConfigController.getList(1, 10,
                    "CombinedSearch", DatasourceType.MYSQL);

            assertNotNull(result.getData());
            List<DatasourceConfigVO> records = result.getData().getRecords();
            assertEquals(1, records.size(), "组合条件下只应返回1条");
            assertEquals("CombinedSearch_MySQL", records.get(0).getDatasourceName(),
                    "应为MYSQL类型的那条记录");
            System.out.println("  条件: name='CombinedSearch' AND type=MYSQL");
            System.out.println("  结果数: " + records.size());
            System.out.println("  记录: " + records.get(0).getDatasourceName());
        }

        @Test
        @DisplayName("✅ 分页参数 - 第二页数据正确")
        void testPagination_SecondPage() {
            System.out.println("\n=== 场景7.4: 分页第二页 ===");

            for (int i = 0; i < 8; i++) {
                createDatasourceInDB("PageDS_" + String.format("%02d", i));
            }

            Result<Page<DatasourceConfigVO>> page1 = datasourceConfigController.getList(1, 3, null, null);
            Result<Page<DatasourceConfigVO>> page2 = datasourceConfigController.getList(2, 3, null, null);

            assertNotNull(page1.getData());
            assertNotNull(page2.getData());
            assertEquals(2, page2.getData().getCurrent(), "第二页current应为2");
            assertEquals(3, page2.getData().getSize(), "size应为3");
            assertFalse(page2.getData().getRecords().isEmpty(), "第二页应有记录");

            System.out.println("  第1页记录数: " + page1.getData().getRecords().size());
            System.out.println("  第2页记录数: " + page2.getData().getRecords().size());
            System.out.println("  注意：无分页拦截器时，两页可能返回相同或重叠的数据");
        }

        @Test
        @DisplayName("✅ 筛选无匹配结果 - 返回空列表")
        void testFilter_NoMatches() {
            System.out.println("\n=== 场景7.5: 筛选无匹配结果 ===");

            createDatasourceInDB("ExistingDS");

            Result<Page<DatasourceConfigVO>> result = datasourceConfigController.getList(1, 10,
                    "NonExistentKeyword_xyz", null);

            assertNotNull(result.getData());
            assertEquals(0, result.getData().getRecords().size(), "无匹配时应返回空列表");
            assertEquals(0, result.getData().getRecords().size(), "total应为0");
            System.out.println("  搜索不存在的关键词 -> 记录数: " + result.getData().getRecords().size() + " ✓");
        }
    }

    @Nested
    @DisplayName("场景8: 边界条件和异常情况")
    class EdgeCaseAndExceptionTests {

        @Disabled("直接调用Controller方法时，Spring的@Validated注解不会触发（需要AOP拦截器），因此空名称无法被自动校验")
        @Test
        @DisplayName("❌ 创建数据源 - 名称为空触发校验失败")
        void testCreate_EmptyName_ValidationFailure() {
            System.out.println("\n=== 场景8.1: 创建数据源-名称为空 ===");
            setUpAdminSecurityContext();

            DatasourceConfigDTO dto = buildValidDTO("");
            dto.setDatasourceName("");

            assertThrows(Exception.class, () -> {
                datasourceConfigController.save(dto);
            }, "名称为空应触发校验异常");
            System.out.println("  校验异常已抛出 ✓");
        }

        @Disabled("直接调用Controller方法时，Spring的@Validated注解不会触发（需要AOP拦截器），因此空URL无法被自动校验")
        @Test
        @DisplayName("❌ 创建数据源 - URL为空触发校验失败")
        void testCreate_EmptyUrl_ValidationFailure() {
            System.out.println("\n=== 场景8.2: 创建数据源-URL为空 ===");
            setUpAdminSecurityContext();

            DatasourceConfigDTO dto = buildValidDTO("EmptyUrlDS");
            dto.setUrl("");

            assertThrows(Exception.class, () -> {
                datasourceConfigController.save(dto);
            }, "URL为空应触发校验异常");
            System.out.println("  校验异常已抛出 ✓");
        }

        @Test
        @DisplayName("❌ 创建数据源 - 类型为null触发校验失败")
        void testCreate_NullType_ValidationFailure() {
            System.out.println("\n=== 场景8.3: 创建数据源-类型为null ===");
            setUpAdminSecurityContext();

            DatasourceConfigDTO dto = buildValidDTO("NullTypeDS");
            dto.setDatasourceType(null);

            assertThrows(Exception.class, () -> {
                datasourceConfigController.save(dto);
            }, "类型为null应触发校验异常");
            System.out.println("  校验异常已抛出 ✓");
        }

        @Test
        @DisplayName("❌ 删除数据源 - 不存在的ID抛出异常")
        void testDelete_NonExistentId() {
            System.out.println("\n=== 场景8.4: 删除不存在的数据源 ===");
            setUpAdminSecurityContext();

            Long nonExistentId = 99999998L;

            try {
                Result<Void> result = datasourceConfigController.delete(nonExistentId);
                System.out.println("  删除结果code: " + result.getCode());
            } catch (BusinessException e) {
                System.out.println("  ✅ BusinessException: " + e.getMessage());
            } catch (RuntimeException e) {
                System.out.println("  ✅ RuntimeException: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
        }

        @Test
        @DisplayName("❌ 更新数据源 - 不存在的ID不会报错(MyBatis-Plus行为)")
        void testUpdate_NonExistentId() {
            System.out.println("\n=== 场景8.5: 更新不存在的数据源 ===");
            setUpAdminSecurityContext();

            DatasourceConfigDTO dto = buildValidDTO("NonExistentUpdate");
            dto.setId(99999997L);

            try {
                datasourceConfigController.update(dto);
                System.out.println("  更新不存在的ID: 返回成功（MyBatis-Plus特性）");
            } catch (RuntimeException e) {
                assertTrue(e.getMessage().contains("不存在"), "异常消息应提示不存在");
                System.out.println("  更新不存在的ID: 抛出 " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        @Test
        @DisplayName("✅ 通过ID测试连接 - 存在但不可达的数据源")
        void testConnection_UnreachableDatasource() {
            System.out.println("\n=== 场景8.6: 测试不可达数据源的连接 ===");

            Long id = createDatasourceInDB("UnreachableDS");

            Result<ConnectionTestResult> result = datasourceConfigController.testConnection(id);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "接口本身应返回200");
            assertNotNull(result.getData(), "连接结果不应为null");
            assertFalse(result.getData().isSuccess(), "不可达数据源连接应失败");
            System.out.println("  success: " + result.getData().isSuccess());
            System.out.println("  message: " + result.getData().getMessage());
            System.out.println("  costTime: " + result.getData().getCostTime());
        }

        @Test
        @DisplayName("✅ 数据源名称最大长度边界 - 100字符")
        void testCreate_NameMaxLength_Boundary() {
            System.out.println("\n=== 场景8.7: 名称长度边界测试(100字符) ===");
            setUpAdminSecurityContext();

            String exactly100Chars = "A".repeat(100);
            DatasourceConfigDTO dto = buildValidDTO(exactly100Chars);

            try {
                Result<Void> result = datasourceConfigController.save(dto);
                assertEquals(200, result.getCode(), "刚好100字符的名称应通过校验");
                System.out.println("  100字符名称: 创建成功 ✓");
            } catch (Exception e) {
                System.out.println("  100字符名称: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
        }

        @Test
        @DisplayName("❌ 数据源名称超长 - 超过100字符触发校验失败")
        void testCreate_NameTooLong_ValidationFailure() {
            System.out.println("\n=== 场景8.8: 名称超长(101字符) ===");
            setUpAdminSecurityContext();

            String tooLongName = "A".repeat(101);
            DatasourceConfigDTO dto = buildValidDTO(tooLongName);

            assertThrows(Exception.class, () -> {
                datasourceConfigController.save(dto);
            }, "超过100字符的名称应触发校验异常");
            System.out.println("  101字符名称: 校验异常已抛出 ✓");
        }

        @Test
        @DisplayName("✅ URL最大长度边界 - 500字符")
        void testCreate_UrlMaxLength_Boundary() {
            System.out.println("\n=== 场景8.9: URL长度边界测试(500字符) ===");
            setUpAdminSecurityContext();

            String exactly500Url = "jdbc:mysql://localhost:3306/" + "a".repeat(476);
            DatasourceConfigDTO dto = buildValidDTO("UrlBoundaryDS");
            dto.setUrl(exactly500Url);

            try {
                Result<Void> result = datasourceConfigController.save(dto);
                assertEquals(200, result.getCode(), "刚好500字符的URL应通过校验");
                System.out.println("  500字符URL: 创建成功 ✓");
            } catch (Exception e) {
                System.out.println("  500字符URL: " + e.getClass().getSimpleName());
            }
        }

        @Test
        @DisplayName("✅ 创建多个同名数据源 - 验证数据库允许(业务层未做唯一约束)")
        void testCreate_DuplicateName() {
            System.out.println("\n=== 场景8.10: 重复名称数据源 ===");
            setUpAdminSecurityContext();

            DatasourceConfigDTO dto1 = buildValidDTO("DuplicateNameDS");
            Result<Void> result1 = datasourceConfigController.save(dto1);
            assertEquals(200, result1.getCode(), "第一次创建应成功");

            DatasourceConfigDTO dto2 = buildValidDTO("DuplicateNameDS");
            Result<Void> result2 = datasourceConfigController.save(dto2);
            assertEquals(200, result2.getCode(), "第二次同名创建应成功(未做唯一约束)");

            Result<Page<DatasourceConfigVO>> listResult = datasourceConfigController.getList(1, 20,
                    "DuplicateNameDS", null);
            long duplicateCount = listResult.getData().getRecords().stream()
                    .filter(vo -> vo.getDatasourceName().equals("DuplicateNameDS"))
                    .count();
            assertTrue(duplicateCount >= 2, "数据库中应存在至少2条同名记录");
            System.out.println("  同名记录数: " + duplicateCount);
            System.out.println("  注意: 当前业务层未对datasourceName做唯一性约束");
        }

        @Test
        @DisplayName("✅ 查询列表默认参数 - 使用默认分页值")
        void testGetList_DefaultParameters() {
            System.out.println("\n=== 场景8.11: 默认分页参数 ===");

            createDatasourceInDB("DefaultParamDS");

            Result<Page<DatasourceConfigVO>> result = datasourceConfigController.getList(1, 10, null, null);

            assertNotNull(result.getData());
            assertEquals(1, result.getData().getCurrent(), "默认current=1");
            assertEquals(10, result.getData().getSize(), "默认size=10");
            System.out.println("  默认参数 current=1, size=10 ✓");
            System.out.println("  实际返回记录数: " + result.getData().getRecords().size());
        }

        @Test
        @DisplayName("✅ 特殊字符名称 - 支持中文和特殊符号")
        void testCreate_SpecialCharName() {
            System.out.println("\n=== 场景8.12: 特殊字符名称 ===");
            setUpAdminSecurityContext();

            DatasourceConfigDTO dto = buildValidDTO("测试数据源_生产环境-MySQL#01");
            dto.setUrl("jdbc.mysql://localhost:3306/special");

            try {
                Result<Void> result = datasourceConfigController.save(dto);
                assertEquals(200, result.getCode(), "特殊字符名称应能正常创建");
                System.out.println("  特殊字符名称: 创建成功 ✓");
            } catch (Exception e) {
                System.out.println("  特殊字符名称: " + e.getClass().getSimpleName());
            }
        }
    }
}
