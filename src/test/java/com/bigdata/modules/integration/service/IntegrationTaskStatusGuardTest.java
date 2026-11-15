package com.bigdata.modules.integration.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.integration.entity.IntegrationTask;
import com.bigdata.modules.integration.mapper.IntegrationTaskMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据集成任务的状态守卫：非法转移必须被拒绝，且拒绝时不得修改落库状态。
 *
 * <p>这些守卫是「防止重复上线 / 重复下线」的唯一保护（进程内锁之外的业务规则），
 * 之前 IntegrationTaskService 的 583 行里只有 48.9% 被执行，守卫分支没有专门用例。</p>
 */
@SpringBootTest
@Transactional
@Rollback
@DisplayName("Integration模块 - 任务状态守卫")
class IntegrationTaskStatusGuardTest {

    @Autowired
    private IntegrationTaskService integrationTaskService;

    @Autowired
    private IntegrationTaskMapper taskMapper;

    @Test
    @DisplayName("已上线任务不可重复上线（409），状态保持不变")
    void rejectsSubmitWhenPublished() {
        IntegrationTask task = createTask("PUBLISHED");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> integrationTaskService.submit(task.getId(), null));

        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("不可重复上线"), ex.getMessage());
        assertEquals("PUBLISHED", taskMapper.selectById(task.getId()).getStatus());
    }

    @Test
    @DisplayName("运行中任务不可重复上线（409）")
    void rejectsSubmitWhenRunning() {
        IntegrationTask task = createTask("RUNNING");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> integrationTaskService.submit(task.getId(), null));

        assertEquals(409, ex.getCode());
        assertEquals("RUNNING", taskMapper.selectById(task.getId()).getStatus());
    }

    @Test
    @DisplayName("草稿任务无需下线（409），状态保持不变")
    void rejectsOfflineWhenDraft() {
        IntegrationTask task = createTask("DRAFT");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> integrationTaskService.offline(task.getId()));

        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("尚未上线"), ex.getMessage());
        assertEquals("DRAFT", taskMapper.selectById(task.getId()).getStatus());
    }

    @Test
    @DisplayName("已下线任务不可重复下线（409）")
    void rejectsOfflineWhenAlreadyOffline() {
        IntegrationTask task = createTask("OFFLINE");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> integrationTaskService.offline(task.getId()));

        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("不可重复下线"), ex.getMessage());
    }

    @Test
    @DisplayName("任务不存在：提交与下线都返回 404")
    void rejectsUnknownTask() {
        BusinessException submitEx = assertThrows(BusinessException.class,
                () -> integrationTaskService.submit(-1L, null));
        assertEquals(404, submitEx.getCode());

        BusinessException offlineEx = assertThrows(BusinessException.class,
                () -> integrationTaskService.offline(-1L));
        assertEquals(404, offlineEx.getCode());
    }

    private IntegrationTask createTask(String status) {
        IntegrationTask task = new IntegrationTask();
        task.setTaskName("UT任务");
        task.setTaskCode("UT_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        task.setSourceType("MYSQL");
        task.setTargetType("HIVE");
        task.setClusterType("flink-standalone");
        task.setReadParallelism(1);
        task.setWriteParallelism(1);
        task.setJobMode("BATCH");
        task.setTriggerType("MANUAL");
        task.setStatus(status);
        taskMapper.insert(task);
        return task;
    }
}
