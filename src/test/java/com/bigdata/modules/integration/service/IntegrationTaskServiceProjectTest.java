package com.bigdata.modules.integration.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.development.entity.DevProject;
import com.bigdata.modules.development.mapper.DevProjectMapper;
import com.bigdata.modules.integration.dto.IntegrationTableDTO;
import com.bigdata.modules.integration.dto.IntegrationTaskDTO;
import com.bigdata.modules.integration.entity.IntegrationSubmitLog;
import com.bigdata.modules.integration.entity.IntegrationTask;
import com.bigdata.modules.integration.entity.IntegrationTaskTable;
import com.bigdata.modules.integration.mapper.IntegrationSubmitLogMapper;
import com.bigdata.modules.integration.mapper.IntegrationTaskMapper;
import com.bigdata.modules.integration.mapper.IntegrationTaskTableMapper;
import com.bigdata.modules.integration.service.submit.ClusterSubmitterFactory;
import com.bigdata.modules.integration.service.submit.DataIntegrationSubmitter;
import com.bigdata.modules.integration.service.submit.SubmitResult;
import com.bigdata.modules.integration.vo.IntegrationTaskVO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据集成任务的「所属项目」字段：保存读取、上线带入项目编码、历史任务缺项目处理。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IntegrationTaskServiceProjectTest {

    @Mock
    private IntegrationTaskMapper taskMapper;
    @Mock
    private IntegrationTaskTableMapper tableMapper;
    @Mock
    private IntegrationSubmitLogMapper submitLogMapper;
    @Mock
    private ClusterSubmitterFactory submitterFactory;
    @Mock
    private DevProjectMapper devProjectMapper;
    @Mock
    private DataIntegrationSubmitter submitter;

    @InjectMocks
    private IntegrationTaskService service;

    /** LambdaUpdateWrapper 需要 MyBatis-Plus 的实体元数据缓存，脱离 Spring 时手动初始化 */
    @BeforeAll
    static void initMybatisTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, IntegrationTask.class);
        TableInfoHelper.initTableInfo(assistant, IntegrationTaskTable.class);
        TableInfoHelper.initTableInfo(assistant, IntegrationSubmitLog.class);
        TableInfoHelper.initTableInfo(assistant, DevProject.class);
    }

    @Test
    void 保存任务时按项目ID回查并冗余项目编码() {
        DevProject project = project(20L, "订单域", "order-platform");
        when(devProjectMapper.selectById(20L)).thenReturn(project);
        when(devProjectMapper.selectBatchIds(any())).thenReturn(List.of(project));
        IntegrationTask existing = task(1L, null, null);
        when(taskMapper.selectById(1L)).thenReturn(existing);
        when(tableMapper.selectList(any())).thenReturn(List.of());

        IntegrationTaskVO vo = service.update(dto(1L, 20L));

        ArgumentCaptor<IntegrationTask> saved = ArgumentCaptor.forClass(IntegrationTask.class);
        verify(taskMapper).updateById(saved.capture());
        assertEquals(20L, saved.getValue().getProjectId());
        assertEquals("order-platform", saved.getValue().getProjectCode());

        assertEquals(20L, vo.getProjectId());
        assertEquals("order-platform", vo.getProjectCode());
        assertEquals("订单域", vo.getProjectName());
    }

    @Test
    void 保存任务时缺少项目直接拒绝() {
        when(taskMapper.selectById(1L)).thenReturn(task(1L, null, null));
        when(tableMapper.selectList(any())).thenReturn(List.of());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.update(dto(1L, null)));

        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("请选择所属项目"), error.getMessage());
    }

    @Test
    void 上线时把最新项目编码作为血缘namespace传给提交器() {
        DevProject project = project(10L, "订单域", "order-platform");
        when(taskMapper.selectById(1L)).thenReturn(task(1L, 10L, "old-code"));
        when(devProjectMapper.selectById(10L)).thenReturn(project);
        when(tableMapper.selectList(any())).thenReturn(List.of());
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(submitLogMapper.insert(any())).thenReturn(1);
        when(submitterFactory.get("flink-standalone")).thenReturn(submitter);
        when(submitter.submit(any())).thenReturn(new SubmitResult("job-1", "作业已提交", "ok"));

        ArgumentCaptor<DataIntegrationSubmitter.SubmitContext> context =
                ArgumentCaptor.forClass(DataIntegrationSubmitter.SubmitContext.class);
        service.submit(1L, null);

        verify(submitter).submit(context.capture());
        assertEquals("order-platform", context.getValue().getProjectCode());

        // 项目改编码后，上线时把新编码写回任务快照（persistState 走 LambdaUpdateWrapper）
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<IntegrationTask>> wrapper =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(taskMapper).update(any(), wrapper.capture());
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue("order-platform"),
                wrapper.getValue().getParamNameValuePairs().toString());

        // 提交记录快照里能追溯到 namespace 来源
        ArgumentCaptor<IntegrationSubmitLog> log = ArgumentCaptor.forClass(IntegrationSubmitLog.class);
        verify(submitLogMapper).insert(log.capture());
        assertNotNull(log.getValue().getParamsSnapshot());
        assertTrue(log.getValue().getParamsSnapshot().contains("\"projectCode\":\"order-platform\""),
                log.getValue().getParamsSnapshot());
    }

    @Test
    void 历史任务缺少项目时上线被拒绝且不触发提交() {
        when(taskMapper.selectById(1L)).thenReturn(task(1L, null, null));
        when(tableMapper.selectList(any())).thenReturn(List.of());

        BusinessException error = assertThrows(BusinessException.class, () -> service.submit(1L, null));

        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("未设置所属项目"), error.getMessage());
        verify(submitterFactory, never()).get(any());
    }

    @Test
    void 项目已被删除或未维护编码时上线被拒绝() {
        when(taskMapper.selectById(1L)).thenReturn(task(1L, 10L, "old-code"));
        when(tableMapper.selectList(any())).thenReturn(List.of());
        when(devProjectMapper.selectById(anyLong())).thenReturn(null);

        BusinessException missing = assertThrows(BusinessException.class, () -> service.submit(1L, null));
        assertTrue(missing.getMessage().contains("所属项目不存在"), missing.getMessage());

        DevProject blank = project(10L, "订单域", "  ");
        when(devProjectMapper.selectById(10L)).thenReturn(blank);
        BusinessException noCode = assertThrows(BusinessException.class, () -> service.submit(1L, null));
        assertTrue(noCode.getMessage().contains("未维护项目编码"), noCode.getMessage());
    }

    // ---------------------------------------------------------------- helpers

    private DevProject project(Long id, String name, String code) {
        DevProject project = new DevProject();
        project.setId(id);
        project.setProjectName(name);
        project.setProjectCode(code);
        return project;
    }

    private IntegrationTask task(Long id, Long projectId, String projectCode) {
        IntegrationTask task = new IntegrationTask();
        task.setId(id);
        task.setTaskName("订单同步");
        task.setTaskCode("DI-TEST-1");
        task.setProjectId(projectId);
        task.setProjectCode(projectCode);
        task.setSourceType("mysql");
        task.setSourceConfig("{\"host\":\"127.0.0.1\",\"port\":3306,\"database\":\"ods\","
                + "\"username\":\"root\",\"password\":\"123456\"}");
        task.setTargetType("mysql");
        task.setTargetConfig("{\"url\":\"jdbc:mysql://127.0.0.1:3306/dw\","
                + "\"username\":\"root\",\"password\":\"123456\"}");
        task.setClusterType("flink-standalone");
        task.setJobMode("BATCH");
        task.setStatus("DRAFT");
        task.setReadParallelism(1);
        task.setWriteParallelism(1);
        return task;
    }

    private IntegrationTaskDTO dto(Long id, Long projectId) {
        IntegrationTaskDTO dto = new IntegrationTaskDTO();
        dto.setId(id);
        dto.setTaskName("订单同步");
        dto.setProjectId(projectId);
        dto.setSourceType("mysql");
        dto.setSourceConfig(Map.of("host", "127.0.0.1", "port", "3306", "database", "ods",
                "username", "root", "password", "123456"));
        dto.setTargetType("mysql");
        dto.setTargetConfig(Map.of("url", "jdbc:mysql://127.0.0.1:3306/dw",
                "username", "root", "password", "123456"));
        IntegrationTableDTO table = new IntegrationTableDTO();
        table.setSourceTable("orders");
        table.setExcludeColumns(List.of());
        table.setSortNo(0);
        dto.setTables(List.of(table));
        dto.setClusterType("flink-standalone");
        return dto;
    }
}
