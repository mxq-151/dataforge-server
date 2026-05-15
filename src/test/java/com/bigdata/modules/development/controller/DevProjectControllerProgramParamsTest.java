package com.bigdata.modules.development.controller;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.development.dto.ProgramParamsDTO;
import com.bigdata.modules.development.entity.ProgramParams;
import com.bigdata.modules.development.service.DevProjectService;
import com.bigdata.modules.development.service.ProgramParamsService;
import com.bigdata.modules.development.vo.ProgramParamsVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DevProjectControllerProgramParamsTest {

    @Mock
    private DevProjectService devProjectService;

    @Mock
    private ProgramParamsService programParamsService;

    @InjectMocks
    private DevProjectController devProjectController;

    private ProgramParams mockEntity;
    private ProgramParamsDTO mockDto;
    private static final Long TEST_PROJECT_ID = 1L;
    private static final String TEST_FILE_PATH = "src/main/etl/ods/user_sync.sql";

    @BeforeEach
    void setUp() {
        mockEntity = new ProgramParams();
        mockEntity.setId(1L);
        mockEntity.setProjectId(TEST_PROJECT_ID);
        mockEntity.setFilePath(TEST_FILE_PATH);
        mockEntity.setEngine("spark");
        mockEntity.setDatasources("[\"kyuubi-prod\",\"doris-olap\"]");
        mockEntity.setJarDependencyCatalogs("[\"spark-core\",\"hadoop-common\"]");
        mockEntity.setRunParams("{\"master\":\"yarn[4]\",\"deploy-mode\":\"client\",\"executor-memory\":\"4g\"}");
        mockEntity.setComputeResources("{\"driver-memory\":\"2g\",\"executor-cores\":2,\"num-executors\":5}");
        mockEntity.setRemark("ODS层用户数据同步任务");
        mockEntity.setCreateTime(LocalDateTime.now());
        mockEntity.setUpdateTime(LocalDateTime.now());

        mockDto = new ProgramParamsDTO();
        mockDto.setProjectId(TEST_PROJECT_ID);
        mockDto.setFilePath(TEST_FILE_PATH);
        mockDto.setEngine("spark");
        mockDto.setDatasources(List.of("kyuubi-prod", "doris-olap"));
        mockDto.setJarDependencyCatalogs(List.of("spark-core", "hadoop-common"));
        mockDto.setRunParams(Map.of("master", "yarn[4]", "deploy-mode", "client", "executor-memory", "4g"));
        mockDto.setComputeResources(Map.of("driver-memory", "2g", "executor-cores", 2, "num-executors", 5));
        mockDto.setRemark("ODS层用户数据同步任务");
    }

    @Test
    @DisplayName("查询项目下所有程序参数 - 返回参数列表")
    void testListProgramParams_ReturnsList() {
        when(programParamsService.listByProjectId(TEST_PROJECT_ID)).thenReturn(List.of(mockEntity));

        Result<List<ProgramParamsVO>> result = devProjectController.listProgramParams(TEST_PROJECT_ID);

        assertNotNull(result);
        assertEquals(200, result.getCode());
        List<ProgramParamsVO> data = result.getData();
        assertNotNull(data);
        assertEquals(1, data.size());
        assertEquals(TEST_PROJECT_ID, data.get(0).getProjectId());
        assertEquals(TEST_FILE_PATH, data.get(0).getFilePath());
        assertEquals("spark", data.get(0).getEngine());
        verify(programParamsService).listByProjectId(TEST_PROJECT_ID);
    }

    @Test
    @DisplayName("查询项目下所有程序参数 - 空列表")
    void testListProgramParams_EmptyList() {
        when(programParamsService.listByProjectId(999L)).thenReturn(List.of());

        Result<List<ProgramParamsVO>> result = devProjectController.listProgramParams(999L);

        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
        assertTrue(result.getData().isEmpty());
    }

    @Test
    @DisplayName("根据复合主键查询单条参数 - 返回详情")
    void testGetProgramParams_ReturnsDetail() {
        when(programParamsService.getByProjectIdAndFilePath(TEST_PROJECT_ID, TEST_FILE_PATH))
                .thenReturn(mockEntity);

        Result<ProgramParamsVO> result = devProjectController.getProgramParams(TEST_PROJECT_ID, TEST_FILE_PATH);

        assertNotNull(result);
        assertEquals(200, result.getCode());
        ProgramParamsVO vo = result.getData();
        assertNotNull(vo);
        assertEquals(TEST_PROJECT_ID, vo.getProjectId());
        assertEquals(TEST_FILE_PATH, vo.getFilePath());
        assertEquals("spark", vo.getEngine());
        assertNotNull(vo.getDatasources());
        assertTrue(vo.getDatasources().contains("kyuubi-prod"));
        assertNotNull(vo.getJarDependencyCatalogs());
        assertTrue(vo.getJarDependencyCatalogs().contains("spark-core"));
        assertNotNull(vo.getRunParams());
        assertEquals("yarn[4]", vo.getRunParams().get("master"));
        assertNotNull(vo.getComputeResources());
        assertEquals(2, vo.getComputeResources().get("executor-cores"));
        assertEquals("ODS层用户数据同步任务", vo.getRemark());
    }

    @Test
    @DisplayName("根据复合主键查询 - 记录不存在时抛异常")
    void testGetProgramParams_NotFound() {
        when(programParamsService.getByProjectIdAndFilePath(999L, "not/exist.sql")).thenReturn(null);

        assertThrows(BusinessException.class, () -> {
            devProjectController.getProgramParams(999L, "not/exist.sql");
        });
    }

    @Test
    @DisplayName("新增程序参数 - 正常保存")
    void testSaveProgramParams_Success() {
        doNothing().when(programParamsService).save(any(ProgramParamsDTO.class));

        Result<Void> result = devProjectController.saveProgramParams(TEST_PROJECT_ID, mockDto);

        assertNotNull(result);
        assertEquals(200, result.getCode());
        verify(programParamsService).save(argThat(dto -> dto.getProjectId().equals(TEST_PROJECT_ID)
                && dto.getFilePath().equals(TEST_FILE_PATH)));
    }

    @Test
    @DisplayName("新增程序参数 - 重复记录返回错误")
    void testSaveProgramParams_Duplicate() {
        doThrow(new BusinessException("该项目的文件运行参数已存在，请使用更新接口"))
                .when(programParamsService).save(any(ProgramParamsDTO.class));

        Result<Void> result = devProjectController.saveProgramParams(TEST_PROJECT_ID, mockDto);

        assertNotNull(result);
        assertNotEquals(200, result.getCode());
        assertTrue(result.getMessage().contains("已存在"));
    }

    @Test
    @DisplayName("更新程序参数 - 正常更新")
    void testUpdateProgramParams_Success() {
        doNothing().when(programParamsService).update(any(ProgramParamsDTO.class));

        Result<Void> result = devProjectController.updateProgramParams(
                TEST_PROJECT_ID, TEST_FILE_PATH, mockDto);

        assertNotNull(result);
        assertEquals(200, result.getCode());
        verify(programParamsService).update(argThat(dto -> dto.getProjectId().equals(TEST_PROJECT_ID)
                && dto.getFilePath().equals(TEST_FILE_PATH)));
    }

    @Test
    @DisplayName("更新程序参数 - 记录不存在返回错误")
    void testUpdateProgramParams_NotFound() {
        doThrow(new BusinessException("未找到对应的程序参数记录"))
                .when(programParamsService).update(any(ProgramParamsDTO.class));

        Result<Void> result = devProjectController.updateProgramParams(
                999L, "not/exist.sql", mockDto);

        assertNotNull(result);
        assertNotEquals(200, result.getCode());
        assertTrue(result.getMessage().contains("未找到"));
    }

    @Test
    @DisplayName("删除程序参数 - 正常删除")
    void testDeleteProgramParams_Success() {
        doNothing().when(programParamsService).delete(eq(TEST_PROJECT_ID), eq(TEST_FILE_PATH));

        Result<Void> result = devProjectController.deleteProgramParams(TEST_PROJECT_ID, TEST_FILE_PATH);

        assertNotNull(result);
        assertEquals(200, result.getCode());
        verify(programParamsService).delete(TEST_PROJECT_ID, TEST_FILE_PATH);
    }

    @Test
    @DisplayName("删除程序参数 - 记录不存在返回错误")
    void testDeleteProgramParams_NotFound() {
        doThrow(new BusinessException("未找到对应的程序参数记录"))
                .when(programParamsService).delete(eq(999L), eq("not/exist.sql"));

        Result<Void> result = devProjectController.deleteProgramParams(999L, "not/exist.sql");

        assertNotNull(result);
        assertNotEquals(200, result.getCode());
        assertTrue(result.getMessage().contains("未找到"));
    }

    @Test
    @DisplayName("多级路径查询 - 路径含多级目录")
    void testGetProgramParams_MultiLevelPath() {
        String multiPath = "src/main/etl/dwd/order_detail.sql";
        mockEntity.setFilePath(multiPath);
        when(programParamsService.getByProjectIdAndFilePath(TEST_PROJECT_ID, multiPath)).thenReturn(mockEntity);

        Result<ProgramParamsVO> result = devProjectController.getProgramParams(TEST_PROJECT_ID, multiPath);

        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertEquals(multiPath, result.getData().getFilePath());
    }

    @Test
    @DisplayName("多级路径更新 - 路径含多级目录")
    void testUpdateProgramParams_MultiLevelPath() {
        String multiPath = "src/main/etl/dwd/order_detail.sql";
        doNothing().when(programParamsService).update(any(ProgramParamsDTO.class));

        Result<Void> result = devProjectController.updateProgramParams(
                TEST_PROJECT_ID, multiPath, mockDto);

        assertNotNull(result);
        assertEquals(200, result.getCode());
        verify(programParamsService).update(argThat(dto -> dto.getFilePath().equals(multiPath)));
    }

    @Test
    @DisplayName("多级路径删除 - 路径含多级目录")
    void testDeleteProgramParams_MultiLevelPath() {
        String multiPath = "src/main/etl/dwd/order_detail.sql";
        doNothing().when(programParamsService).delete(eq(TEST_PROJECT_ID), eq(multiPath));

        Result<Void> result = devProjectController.deleteProgramParams(TEST_PROJECT_ID, multiPath);

        assertNotNull(result);
        assertEquals(200, result.getCode());
        verify(programParamsService).delete(TEST_PROJECT_ID, multiPath);
    }

    @Test
    @DisplayName("验证DTO字段完整性")
    void testDTOFields_Complete() {
        assertEquals(TEST_PROJECT_ID, mockDto.getProjectId());
        assertEquals(TEST_FILE_PATH, mockDto.getFilePath());
        assertEquals("spark", mockDto.getEngine());
        assertEquals(2, mockDto.getDatasources().size());
        assertTrue(mockDto.getDatasources().contains("kyuubi-prod"));
        assertTrue(mockDto.getDatasources().contains("doris-olap"));
        assertEquals(2, mockDto.getJarDependencyCatalogs().size());
        assertTrue(mockDto.getJarDependencyCatalogs().contains("spark-core"));
        assertTrue(mockDto.getJarDependencyCatalogs().contains("hadoop-common"));
        assertEquals(3, mockDto.getRunParams().size());
        assertEquals("yarn[4]", mockDto.getRunParams().get("master"));
        assertEquals(3, mockDto.getComputeResources().size());
        assertEquals(2, mockDto.getComputeResources().get("executor-cores"));
        assertEquals(5, mockDto.getComputeResources().get("num-executors"));
        assertEquals("ODS层用户数据同步任务", mockDto.getRemark());
    }

    @Test
    @DisplayName("验证Entity字段完整性")
    void testEntityFields_Complete() {
        assertEquals(1L, mockEntity.getId());
        assertEquals(TEST_PROJECT_ID, mockEntity.getProjectId());
        assertEquals(TEST_FILE_PATH, mockEntity.getFilePath());
        assertEquals("spark", mockEntity.getEngine());
        assertNotNull(mockEntity.getDatasources());
        assertNotNull(mockEntity.getJarDependencyCatalogs());
        assertNotNull(mockEntity.getRunParams());
        assertNotNull(mockEntity.getComputeResources());
        assertEquals("ODS层用户数据同步任务", mockEntity.getRemark());
        assertNotNull(mockEntity.getCreateTime());
        assertNotNull(mockEntity.getUpdateTime());
    }
}
