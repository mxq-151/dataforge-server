package com.bigdata.modules.development.controller;

import com.bigdata.common.exception.BusinessException;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.modules.development.entity.DevJarDependency;
import com.bigdata.modules.development.service.DevJarDependencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DevJarDependencyControllerTest {

    @Mock
    private DevJarDependencyService devJarDependencyService;

    @InjectMocks
    private DevJarDependencyController devJarDependencyController;

    private DevJarDependency mockJar;

    @BeforeEach
    void setUp() {
        mockJar = new DevJarDependency();
        mockJar.setId(1L);
        mockJar.setJarName("spark-sql_2.12");
        mockJar.setGroupId("org.apache.spark");
        mockJar.setArtifactId("spark-sql_2.12");
        mockJar.setVersion("3.5.0");
        mockJar.setScope("compile");
        mockJar.setSource("system");
        mockJar.setJarPath("/opt/jars/spark-sql_2.12-3.5.0.jar");
        mockJar.setCategory("Spark核心库");
        mockJar.setStatus(1);
        mockJar.setDescription("Spark SQL核心库");
    }

    @Test
    @DisplayName("分页查询依赖包列表 - 返回分页数据")
    void testGetList_ReturnsPageData() {
        Page<DevJarDependency> mockPage = new Page<>(1, 10);
        mockPage.setRecords(List.of(mockJar));
        mockPage.setTotal(1);

        when(devJarDependencyService.getJarList(eq(1), eq(10), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(mockPage);

        var result = devJarDependencyController.getList(1, 10, null, null, null, null);

        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
    }

    @Test
    @DisplayName("分页查询 - 按名称筛选")
    void testGetList_FilterByJarName() {
        Page<DevJarDependency> mockPage = new Page<>(1, 10);
        mockPage.setRecords(List.of(mockJar));
        mockPage.setTotal(1);

        when(devJarDependencyService.getJarList(eq(1), eq(10), eq("spark"), isNull(), isNull(), isNull()))
                .thenReturn(mockPage);

        var result = devJarDependencyController.getList(1, 10, "spark", null, null, null);

        assertNotNull(result);
        assertEquals(200, result.getCode());
        verify(devJarDependencyService).getJarList(eq(1), eq(10), eq("spark"), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("分页查询 - 按来源筛选")
    void testGetList_FilterBySource() {
        Page<DevJarDependency> mockPage = new Page<>(1, 10);
        mockPage.setRecords(List.of(mockJar));
        mockPage.setTotal(1);

        when(devJarDependencyService.getJarList(eq(1), eq(10), isNull(), eq("system"), isNull(), isNull()))
                .thenReturn(mockPage);

        var result = devJarDependencyController.getList(1, 10, null, "system", null, null);

        assertNotNull(result);
        assertEquals(200, result.getCode());
    }

    @Test
    @DisplayName("分页查询 - 按功能分类筛选")
    void testGetList_FilterByCategory() {
        Page<DevJarDependency> mockPage = new Page<>(1, 10);
        mockPage.setRecords(List.of(mockJar));
        mockPage.setTotal(1);

        when(devJarDependencyService.getJarList(eq(1), eq(10), isNull(), isNull(), isNull(), eq("Spark核心库")))
                .thenReturn(mockPage);

        var result = devJarDependencyController.getList(1, 10, null, null, null, "Spark核心库");

        assertNotNull(result);
        assertEquals(200, result.getCode());
    }

    @Test
    @DisplayName("获取所有依赖包 - 返回正常状态列表")
    void testGetAllJars() {
        when(devJarDependencyService.getAllJars()).thenReturn(List.of(mockJar));

        var result = devJarDependencyController.getAllJars();

        assertNotNull(result);
        assertEquals(200, result.getCode());
        List<DevJarDependency> data = result.getData();
        assertNotNull(data);
        assertEquals(1, data.size());
        assertEquals("spark-sql_2.12", data.get(0).getJarName());
    }

    @Test
    @DisplayName("根据ID获取依赖包详情")
    void testGetById() {
        when(devJarDependencyService.getJarById(1L)).thenReturn(mockJar);

        var result = devJarDependencyController.getById(1L);

        assertNotNull(result);
        assertEquals(200, result.getCode());
        DevJarDependency jar = result.getData();
        assertNotNull(jar);
        assertEquals(1L, jar.getId());
        assertEquals("spark-sql_2.12", jar.getJarName());
        assertEquals("/opt/jars/spark-sql_2.12-3.5.0.jar", jar.getJarPath());
        assertEquals("Spark核心库", jar.getCategory());
    }

    @Test
    @DisplayName("新增依赖包 - 调用service保存")
    void testSave() {
        var result = devJarDependencyController.save(mockJar);

        assertNotNull(result);
        assertEquals(200, result.getCode());
        verify(devJarDependencyService).saveJar(any(DevJarDependency.class));
    }

    @Test
    @DisplayName("更新依赖包 - 调用service更新")
    void testUpdate() {
        var result = devJarDependencyController.update(mockJar);

        assertNotNull(result);
        assertEquals(200, result.getCode());
        verify(devJarDependencyService).updateJar(any(DevJarDependency.class));
    }

    @Test
    @DisplayName("删除依赖包 - 存在时正常删除")
    void testDelete() {
        doNothing().when(devJarDependencyService).deleteJar(1L);

        var result = devJarDependencyController.delete(1L);

        assertNotNull(result);
        assertEquals(200, result.getCode());
        verify(devJarDependencyService).deleteJar(1L);
    }

    @Test
    @DisplayName("删除依赖包 - 不存在时应抛异常")
    void testDelete_NotFound() {
        doThrow(new BusinessException("依赖包配置不存在")).when(devJarDependencyService).deleteJar(999L);

        assertThrows(BusinessException.class, () -> {
            devJarDependencyController.delete(999L);
        });
    }

    @Test
    @DisplayName("获取来源类型列表")
    void testGetJarSources() {
        when(devJarDependencyService.getJarSources())
                .thenReturn(List.of("system", "custom", "maven", "local"));

        var result = devJarDependencyController.getJarSources();

        assertNotNull(result);
        assertEquals(200, result.getCode());
        List<String> sources = result.getData();
        assertNotNull(sources);
        assertEquals(4, sources.size());
        assertTrue(sources.contains("system"));
        assertTrue(sources.contains("maven"));
        assertTrue(sources.contains("local"));
        assertTrue(sources.contains("custom"));
    }

    @Test
    @DisplayName("获取作用域列表")
    void testGetJarScopes() {
        when(devJarDependencyService.getJarScopes())
                .thenReturn(List.of("compile", "runtime", "provided", "test"));

        var result = devJarDependencyController.getJarScopes();

        assertNotNull(result);
        assertEquals(200, result.getCode());
        List<String> scopes = result.getData();
        assertNotNull(scopes);
        assertEquals(4, scopes.size());
        assertTrue(scopes.contains("compile"));
        assertTrue(scopes.contains("test"));
    }

    @Test
    @DisplayName("获取功能分类列表 - 返回已有分类")
    void testGetJarCategories() {
        when(devJarDependencyService.getJarCategories())
                .thenReturn(List.of("Spark核心库", "Hadoop工具库", "MySQL驱动"));

        var result = devJarDependencyController.getJarCategories();

        assertNotNull(result);
        assertEquals(200, result.getCode());
        List<String> categories = result.getData();
        assertNotNull(categories);
        assertEquals(3, categories.size());
        assertTrue(categories.contains("Spark核心库"));
    }

    @Test
    @DisplayName("验证实体字段完整性 - 包含category和jarPath")
    void testEntityFields_Complete() {
        assertEquals(1L, mockJar.getId());
        assertEquals("spark-sql_2.12", mockJar.getJarName());
        assertEquals("org.apache.spark", mockJar.getGroupId());
        assertEquals("spark-sql_2.12", mockJar.getArtifactId());
        assertEquals("3.5.0", mockJar.getVersion());
        assertEquals("compile", mockJar.getScope());
        assertEquals("system", mockJar.getSource());
        assertEquals("/opt/jars/spark-sql_2.12-3.5.0.jar", mockJar.getJarPath());
        assertEquals("Spark核心库", mockJar.getCategory());
        assertEquals(1, mockJar.getStatus());
        assertEquals("Spark SQL核心库", mockJar.getDescription());
    }
}
