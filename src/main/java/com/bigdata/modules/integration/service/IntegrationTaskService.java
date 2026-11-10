package com.bigdata.modules.integration.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.utils.SecurityUtils;
import com.bigdata.modules.development.entity.DevProject;
import com.bigdata.modules.development.mapper.DevProjectMapper;
import com.bigdata.modules.integration.dto.IntegrationParamsDTO;
import com.bigdata.modules.integration.dto.IntegrationTableDTO;
import com.bigdata.modules.integration.dto.IntegrationTaskDTO;
import com.bigdata.modules.integration.entity.IntegrationSubmitLog;
import com.bigdata.modules.integration.entity.IntegrationTask;
import com.bigdata.modules.integration.entity.IntegrationTaskTable;
import com.bigdata.modules.integration.enums.ClusterType;
import com.bigdata.modules.integration.enums.DataSourceKind;
import com.bigdata.modules.integration.enums.TaskStatus;
import com.bigdata.modules.integration.mapper.IntegrationSubmitLogMapper;
import com.bigdata.modules.integration.mapper.IntegrationTaskMapper;
import com.bigdata.modules.integration.mapper.IntegrationTaskTableMapper;
import com.bigdata.modules.integration.service.submit.ClusterJobStatus;
import com.bigdata.modules.integration.service.submit.ClusterSubmitterFactory;
import com.bigdata.modules.integration.service.submit.DataIntegrationSubmitter;
import com.bigdata.modules.integration.service.submit.FlinkSqlBuilder;
import com.bigdata.modules.integration.service.submit.SourceConnection;
import com.bigdata.modules.integration.service.submit.SubmitResult;
import com.bigdata.modules.integration.service.submit.TableSpec;
import com.bigdata.modules.integration.service.submit.TargetNaming;
import com.bigdata.modules.integration.vo.DataSourceTypeVO;
import com.bigdata.modules.integration.vo.IntegrationOptionsVO;
import com.bigdata.modules.integration.vo.IntegrationSubmitLogVO;
import com.bigdata.modules.integration.vo.IntegrationSubmitResultVO;
import com.bigdata.modules.integration.vo.IntegrationTableVO;
import com.bigdata.modules.integration.vo.IntegrationTaskStatusVO;
import com.bigdata.modules.integration.vo.IntegrationTaskVO;
import com.bigdata.modules.integration.vo.OptionItemVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 数据集成任务的生命周期管理。
 *
 * <p>任务固定为五块配置：源端连接、目标端连接、需要同步的表清单（含排除字段）、
 * 任务级读写并行度、其他参数。表清单存在 {@code integration_task_table}，
 * 目标标识（topic / 索引 / 路径 / Hive 表名）由目标端配置的命名模板按表推导。</p>
 */
@Service
public class IntegrationTaskService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationTaskService.class);

    private static final String ACTION_SUBMIT = "SUBMIT";
    private static final String ACTION_OFFLINE = "OFFLINE";
    private static final String RESULT_SUCCESS = "SUCCESS";
    private static final String RESULT_FAILED = "FAILED";

    /** 按任务 ID 加锁，避免并发重复提交产生两个集群作业 */
    private final Map<Long, Object> taskLocks = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private IntegrationTaskMapper taskMapper;

    @Autowired
    private IntegrationTaskTableMapper tableMapper;

    @Autowired
    private IntegrationSubmitLogMapper submitLogMapper;

    @Autowired
    private ClusterSubmitterFactory submitterFactory;

    /** 项目主数据与「开发助手」共用同一张 dev_project 表，不另建项目表 */
    @Autowired
    private DevProjectMapper devProjectMapper;

    // ---------------------------------------------------------------- CRUD

    public Page<IntegrationTaskVO> page(int current, int size, String keyword, String status,
                                        String clusterType, String sourceType, String targetType) {
        Page<IntegrationTask> page = new Page<>(Math.max(1, current), Math.min(Math.max(1, size), 200));
        LambdaQueryWrapper<IntegrationTask> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(IntegrationTask::getTaskName, kw)
                    .or().like(IntegrationTask::getTaskCode, kw));
        }
        wrapper.eq(isNotBlank(targetType), IntegrationTask::getTargetType, targetType)
                .eq(isNotBlank(status), IntegrationTask::getStatus, status)
                .eq(isNotBlank(clusterType), IntegrationTask::getClusterType, clusterType)
                .eq(isNotBlank(sourceType), IntegrationTask::getSourceType, sourceType)
                .orderByDesc(IntegrationTask::getUpdateTime)
                .orderByDesc(IntegrationTask::getId);
        Page<IntegrationTask> result = taskMapper.selectPage(page, wrapper);

        Page<IntegrationTaskVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        List<Long> ids = result.getRecords().stream().map(IntegrationTask::getId).collect(Collectors.toList());
        Map<Long, List<IntegrationTaskTable>> tableMap = loadTables(ids);
        Map<Long, String> projectNames = loadProjectNames(result.getRecords());
        voPage.setRecords(result.getRecords().stream()
                .map(task -> toVO(task, tableMap.get(task.getId()), projectNames))
                .collect(Collectors.toList()));
        return voPage;
    }

    public IntegrationTaskVO detail(Long id) {
        IntegrationTask task = requireTask(id);
        return toVO(task, tablesOf(id), loadProjectNames(List.of(task)));
    }

    @Transactional
    public IntegrationTaskVO create(IntegrationTaskDTO dto) {
        validateBase(dto);
        List<IntegrationTableDTO> normalizedTables = normalizeTables(dto);
        IntegrationTask task = new IntegrationTask();
        applyDto(task, dto);
        task.setTaskCode(isNotBlank(dto.getTaskCode()) ? dto.getTaskCode().trim() : generateTaskCode());
        if (taskMapper.selectCount(new LambdaQueryWrapper<IntegrationTask>()
                .eq(IntegrationTask::getTaskCode, task.getTaskCode())) > 0) {
            throw new BusinessException(409, "任务编码已存在: " + task.getTaskCode());
        }
        task.setStatus(TaskStatus.DRAFT.getCode());
        task.setCreateBy(SecurityUtils.getCurrentUserId());
        taskMapper.insert(task);
        persistTables(task.getId(), normalizedTables);
        return toVO(task, tablesOf(task.getId()), loadProjectNames(List.of(task)));
    }

    @Transactional
    public IntegrationTaskVO update(IntegrationTaskDTO dto) {
        if (dto.getId() == null) {
            throw new BusinessException(400, "缺少任务 ID");
        }
        validateBase(dto);
        List<IntegrationTableDTO> normalizedTables = normalizeTables(dto);
        IntegrationTask task = requireTask(dto.getId());
        applyDto(task, dto);
        if (isNotBlank(dto.getTaskCode()) && !dto.getTaskCode().trim().equals(task.getTaskCode())) {
            Long duplicated = taskMapper.selectCount(new LambdaQueryWrapper<IntegrationTask>()
                    .eq(IntegrationTask::getTaskCode, dto.getTaskCode().trim())
                    .ne(IntegrationTask::getId, task.getId()));
            if (duplicated > 0) {
                throw new BusinessException(409, "任务编码已存在: " + dto.getTaskCode());
            }
            task.setTaskCode(dto.getTaskCode().trim());
        }
        taskMapper.updateById(task);
        // 表清单整体替换：编辑保存后立即与页面一致，运行中的作业仍在集群侧运行旧配置，
        // 需要重新上线才会使用新配置。
        tableMapper.delete(new LambdaQueryWrapper<IntegrationTaskTable>()
                .eq(IntegrationTaskTable::getTaskId, task.getId()));
        persistTables(task.getId(), normalizedTables);
        IntegrationTask saved = requireTask(task.getId());
        return toVO(saved, tablesOf(task.getId()), loadProjectNames(List.of(saved)));
    }

    @Transactional
    public void delete(Long id) {
        IntegrationTask task = requireTask(id);
        if (TaskStatus.PUBLISHED.getCode().equals(task.getStatus())
                || TaskStatus.RUNNING.getCode().equals(task.getStatus())) {
            throw new BusinessException(409, "任务已上线/运行中，请先下线后再删除");
        }
        taskMapper.deleteById(id);
        tableMapper.delete(new LambdaQueryWrapper<IntegrationTaskTable>()
                .eq(IntegrationTaskTable::getTaskId, id));
        submitLogMapper.delete(new LambdaQueryWrapper<IntegrationSubmitLog>()
                .eq(IntegrationSubmitLog::getTaskId, id));
    }

    /** 动态更新参数：不重建任务记录，下次提交时生效（并行度与附加参数都在任务级） */
    @Transactional
    public IntegrationTaskVO updateParams(Long id, IntegrationParamsDTO dto) {
        IntegrationTask task = requireTask(id);
        if (dto.getExtraParams() != null) {
            task.setExtraParams(toJson(dto.getExtraParams()));
        }
        if (dto.getReadParallelism() != null) {
            task.setReadParallelism(dto.getReadParallelism());
        }
        if (dto.getWriteParallelism() != null) {
            task.setWriteParallelism(dto.getWriteParallelism());
        }
        taskMapper.updateById(task);
        IntegrationTask saved = requireTask(id);
        return toVO(saved, tablesOf(id), loadProjectNames(List.of(saved)));
    }

    // ------------------------------------------------------- 上线 / 下线 / 状态

    public IntegrationSubmitResultVO submit(Long id, IntegrationParamsDTO overrides) {
        synchronized (lockOf(id)) {
            IntegrationTask task = requireTask(id);
            TaskStatus current = TaskStatus.fromCode(task.getStatus());
            if (current == TaskStatus.PUBLISHED || current == TaskStatus.RUNNING) {
                throw new BusinessException(409, "任务当前状态为「" + current.getLabel()
                        + "」，不可重复上线；如需变更参数请先下线");
            }
            // 上线前刷新项目编码：项目改编码后，下一次上线即用新编码作为血缘 namespace
            refreshProjectForSubmit(task);
            if (overrides != null) {
                if (overrides.getExtraParams() != null) {
                    task.setExtraParams(toJson(overrides.getExtraParams()));
                }
                if (overrides.getReadParallelism() != null) {
                    task.setReadParallelism(overrides.getReadParallelism());
                }
                if (overrides.getWriteParallelism() != null) {
                    task.setWriteParallelism(overrides.getWriteParallelism());
                }
                taskMapper.updateById(task);
            }

            DataIntegrationSubmitter submitter = submitterFactory.get(task.getClusterType());
            DataIntegrationSubmitter.SubmitContext context = buildContext(task);
            long startedAt = System.currentTimeMillis();
            try {
                SubmitResult result = submitter.submit(context);
                // 命令行成功但未解析到 jobId 时置空，避免把上一次的旧 jobId 继续挂在任务上
                task.setJobId(result.getJobId() == null ? "" : result.getJobId());
                task.setStatus(TaskStatus.PUBLISHED.getCode());
                task.setLastSubmitTime(LocalDateTime.now());
                task.setLastError(null);
                persistState(task);

                IntegrationSubmitLog submitLog = writeLog(task, ACTION_SUBMIT, RESULT_SUCCESS,
                        result.getJobId(), result.getLog(), null, startedAt);
                return buildResultVO(task, result.getMessage(), submitLog.getId(), startedAt);
            } catch (BusinessException e) {
                task.setStatus(TaskStatus.FAILED.getCode());
                task.setLastError(abbreviate(e.getMessage(), 500));
                persistState(task);
                // 失败也保留本次生成的作业配置，便于对照集群侧排查
                writeLog(task, ACTION_SUBMIT, RESULT_FAILED, null, withConfigHeader(context.getGeneratedConfig()),
                        e.getMessage(), startedAt);
                throw e;
            }
        }
    }

    public IntegrationSubmitResultVO offline(Long id) {
        synchronized (lockOf(id)) {
            IntegrationTask task = requireTask(id);
            TaskStatus current = TaskStatus.fromCode(task.getStatus());
            if (current == TaskStatus.DRAFT) {
                throw new BusinessException(409, "任务尚未上线，无需下线");
            }
            if (current == TaskStatus.OFFLINE) {
                throw new BusinessException(409, "任务已下线，不可重复下线");
            }

            long startedAt = System.currentTimeMillis();
            try {
                if (isNotBlank(task.getJobId())) {
                    DataIntegrationSubmitter submitter = submitterFactory.get(task.getClusterType());
                    DataIntegrationSubmitter.SubmitContext context = buildContext(task);
                    context.setJobId(task.getJobId());
                    SubmitResult result = submitter.cancel(context);
                    writeLog(task, ACTION_OFFLINE, RESULT_SUCCESS, task.getJobId(),
                            result.getLog(), null, startedAt);
                }
                task.setStatus(TaskStatus.OFFLINE.getCode());
                task.setLastOfflineTime(LocalDateTime.now());
                task.setLastError(null);
                persistState(task);
                IntegrationSubmitLog submitLog = submitLogMapper.selectOne(
                        new LambdaQueryWrapper<IntegrationSubmitLog>()
                                .eq(IntegrationSubmitLog::getTaskId, task.getId())
                                .eq(IntegrationSubmitLog::getAction, ACTION_OFFLINE)
                                .orderByDesc(IntegrationSubmitLog::getId)
                                .last("LIMIT 1"));
                return buildResultVO(task, "任务已下线", submitLog == null ? null : submitLog.getId(), startedAt);
            } catch (BusinessException e) {
                task.setStatus(TaskStatus.FAILED.getCode());
                task.setLastError(abbreviate(e.getMessage(), 500));
                persistState(task);
                writeLog(task, ACTION_OFFLINE, RESULT_FAILED, task.getJobId(), null, e.getMessage(), startedAt);
                throw e;
            }
        }
    }

    /** 查询集群实时状态，并把结果同步回任务记录 */
    public IntegrationTaskStatusVO refreshStatus(Long id) {
        IntegrationTask task = requireTask(id);
        IntegrationTaskStatusVO vo = new IntegrationTaskStatusVO();
        vo.setTaskId(task.getId());
        vo.setJobId(task.getJobId());
        vo.setCheckedAt(LocalDateTime.now());

        if (!isNotBlank(task.getJobId())) {
            vo.setStatus(task.getStatus());
            vo.setStatusLabel(labelOf(task.getStatus()));
            vo.setClusterReachable(true);
            vo.setMessage("任务尚未提交，无运行实例");
            return vo;
        }

        DataIntegrationSubmitter submitter = submitterFactory.get(task.getClusterType());
        DataIntegrationSubmitter.SubmitContext context = buildContext(task);
        context.setJobId(task.getJobId());
        ClusterJobStatus status = submitter.queryStatus(context);

        vo.setClusterStatus(status.getClusterStatus());
        vo.setClusterReachable(status.isReachable());
        vo.setMessage(status.getMessage());

        TaskStatus mapped = status.isReachable() ? mapClusterStatus(status.getClusterStatus()) : null;
        if (mapped != null && !mapped.getCode().equals(task.getStatus())) {
            task.setStatus(mapped.getCode());
            if (mapped == TaskStatus.OFFLINE) {
                task.setLastOfflineTime(LocalDateTime.now());
            }
            persistState(task);
        }
        vo.setStatus(task.getStatus());
        vo.setStatusLabel(labelOf(task.getStatus()));
        return vo;
    }

    // ------------------------------------------------------------- 提交记录 / 日志

    public List<IntegrationSubmitLogVO> logs(Long taskId, int limit) {
        requireTask(taskId);
        List<IntegrationSubmitLog> list = submitLogMapper.selectList(
                new LambdaQueryWrapper<IntegrationSubmitLog>()
                        .eq(IntegrationSubmitLog::getTaskId, taskId)
                        .orderByDesc(IntegrationSubmitLog::getId)
                        .last("LIMIT " + Math.min(Math.max(1, limit), 100)));
        return list.stream().map(item -> toLogVO(item, false)).collect(Collectors.toList());
    }

    public IntegrationSubmitLogVO logDetail(Long logId) {
        IntegrationSubmitLog entity = submitLogMapper.selectById(logId);
        if (entity == null) {
            throw new BusinessException(404, "提交记录不存在: " + logId);
        }
        return toLogVO(entity, true);
    }

    // ---------------------------------------------------------------- 选项

    public IntegrationOptionsVO options() {
        IntegrationOptionsVO vo = new IntegrationOptionsVO();
        List<DataSourceTypeVO> targets = new ArrayList<>();
        List<DataSourceTypeVO> sources = new ArrayList<>();
        for (DataSourceKind kind : DataSourceKind.values()) {
            DataSourceTypeVO type = new DataSourceTypeVO();
            type.setCode(kind.getCode());
            type.setLabel(kind.getLabel());
            type.setStreamSource(kind.isStreamSource());
            type.setStreamSink(kind.isStreamSink());
            type.setReadFields(kind.getReadFields().stream()
                    .map(f -> new DataSourceTypeVO.FieldVO(f.getKey(), f.getLabel(), f.isRequired(),
                            f.getPlaceholder(), f.getType()))
                    .collect(Collectors.toList()));
            type.setWriteFields(kind.getWriteFields().stream()
                    .map(f -> new DataSourceTypeVO.FieldVO(f.getKey(), f.getLabel(), f.isRequired(),
                            f.getPlaceholder(), f.getType()))
                    .collect(Collectors.toList()));
            // 只有声明了写参数的连接器才能作为目标端（CDC 源端如 Oracle / SQL Server / Db2 / OceanBase 仅支持读取）
            if (!kind.getWriteFields().isEmpty()) {
                targets.add(type);
            }
            // 只有声明了读参数的连接器才能作为源端（例如 Hive 目前仅支持作为目标）
            if (!kind.getReadFields().isEmpty()) {
                sources.add(type);
            }
        }
        vo.setSourceTypes(sources);
        vo.setTargetTypes(targets);
        vo.setClusterTypes(Arrays.stream(ClusterType.values())
                .map(c -> new OptionItemVO(c.getCode(), c.getLabel())).collect(Collectors.toList()));
        vo.setStatuses(Arrays.stream(TaskStatus.values())
                .map(s -> new OptionItemVO(s.getCode(), s.getLabel())).collect(Collectors.toList()));
        vo.setJobModes(List.of(new OptionItemVO("BATCH", "批模式"), new OptionItemVO("STREAM", "流模式")));
        vo.setTriggerTypes(List.of(new OptionItemVO("MANUAL", "手动触发"), new OptionItemVO("CRON", "定时调度")));
        return vo;
    }

    // ---------------------------------------------------------------- 校验与归一化

    private void validateBase(IntegrationTaskDTO dto) {
        DataSourceKind source = DataSourceKind.fromCode(dto.getSourceType());
        if (source == null) {
            throw new BusinessException(400, "不支持的源端类型: " + dto.getSourceType());
        }
        if (source.getReadFields().isEmpty()) {
            throw new BusinessException(400, "该连接器暂不支持作为源端: " + source.getLabel());
        }
        if (ClusterType.fromCode(dto.getClusterType()) == null) {
            throw new BusinessException(400, "不支持的运行集群: " + dto.getClusterType());
        }
        if (dto.getReadParallelism() != null && dto.getReadParallelism() <= 0) {
            throw new BusinessException(400, "读并行度必须大于 0");
        }
        if (dto.getWriteParallelism() != null && dto.getWriteParallelism() <= 0) {
            throw new BusinessException(400, "写并行度必须大于 0");
        }
        // CDC 源端在保存阶段就把连接参数与启动位点校验掉，避免上线时才发现配置不可用
        if (source.isCdcSource()) {
            Map<String, Object> sourceConfig = SourceConnection.normalize(source, dto.getSourceConfig());
            SourceConnection.endpoint(source, sourceConfig);
            SourceConnection.startup(source, sourceConfig);
        }
    }

    private List<IntegrationTableDTO> normalizeTables(IntegrationTaskDTO dto) {
        List<IntegrationTableDTO> list = dto.getTables() == null
                ? new ArrayList<>() : new ArrayList<>(dto.getTables());
        if (list.isEmpty()) {
            throw new BusinessException(400, "至少需要配置一张需要同步的表");
        }
        DataSourceKind sourceKind = DataSourceKind.fromCode(dto.getSourceType());
        boolean multiTableSource = sourceKind != null && sourceKind.isCdcSource();
        if (!multiTableSource && list.size() > 1) {
            throw new BusinessException(400, "源端类型 " + sourceKind.getLabel()
                    + " 不支持一次同步多张表，请只保留一张表");
        }
        DataSourceKind targetKind = DataSourceKind.fromCode(dto.getTargetType());
        if (targetKind == null) {
            throw new BusinessException(400, "不支持的目标端类型: " + dto.getTargetType());
        }
        if (targetKind.getWriteFields().isEmpty()) {
            throw new BusinessException(400, "该连接器暂不支持作为目标端: " + targetKind.getLabel());
        }

        Set<String> sourceTables = new HashSet<>();
        List<TableSpec> specs = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            IntegrationTableDTO table = list.get(i);
            String sourceTable = table.getSourceTable() == null ? "" : table.getSourceTable().trim();
            if (sourceTable.isEmpty()) {
                throw new BusinessException(400, "第 " + (i + 1) + " 张表缺少表名，请填写表名（库名由源端数据库决定）");
            }
            if (!sourceTables.add(sourceTable.toLowerCase())) {
                throw new BusinessException(400, "表「" + sourceTable + "」重复配置；同一张表只能出现一次");
            }
            table.setSourceTable(sourceTable);
            List<String> excludes = new ArrayList<>();
            Set<String> seenColumns = new HashSet<>();
            if (table.getExcludeColumns() != null) {
                for (String column : table.getExcludeColumns()) {
                    if (column == null || column.trim().isEmpty()) {
                        continue;
                    }
                    String name = column.trim();
                    if (seenColumns.add(name.toLowerCase())) {
                        excludes.add(name);
                    }
                }
            }
            table.setExcludeColumns(excludes);
            if (table.getSortNo() == null) {
                table.setSortNo(i);
            }
            specs.add(new TableSpec(sourceTable, excludes, table.getSortNo()));
        }
        // 目标标识按命名模板逐表推导，模板非法或推导结果重名在保存阶段就报错
        TargetNaming.resolveAll(targetKind, dto.getTargetConfig(), specs);
        if (sourceKind != null && sourceKind.isCdcSource()) {
            validateCdcTables(sourceKind, dto.getSourceConfig(), list);
        }
        return list;
    }

    /**
     * CDC 表清单校验：
     * <ul>
     *   <li>MySQL / PostgreSQL / OceanBase：表清单只填表名，库名统一由源端 database 决定；
     *       兼容老数据里的「库.表」，但库名必须一致；</li>
     *   <li>SQL Server / Db2：允许「schema.table」，只填表名时使用源端 schema；</li>
     *   <li>Oracle：表清单只填表名，schema 由连接参数指定；</li>
     *   <li>每张表一个 CDC source，MySQL / OceanBase 的 server-id 按「基数 + 序号」分配。</li>
     * </ul>
     */
    private void validateCdcTables(DataSourceKind sourceKind, Map<String, Object> sourceConfig,
                                   List<IntegrationTableDTO> tables) {
        Map<String, Object> config = SourceConnection.normalize(sourceKind, sourceConfig);
        String database = SourceConnection.endpoint(sourceKind, config).database();
        for (IntegrationTableDTO table : tables) {
            String name = table.getSourceTable();
            int dot = name.indexOf('.');
            if (sourceKind.tableQualifier() == DataSourceKind.TableQualifier.DATABASE) {
                if (dot < 0) {
                    continue;
                }
                String tableDb = name.substring(0, dot).trim();
                if (name.substring(dot + 1).trim().isEmpty()) {
                    throw new BusinessException(400, "表「" + name + "」缺少表名，请填写「库名.表名」或只填表名");
                }
                if (!tableDb.equalsIgnoreCase(database)) {
                    throw new BusinessException(400, "表「" + name + "」的库名 " + tableDb
                            + " 与源端数据库 " + database + " 不一致；CDC 一份源连接只能读一个库，"
                            + "表清单请只填表名，或按库拆分任务");
                }
            } else if (sourceKind.tableQualifier() == DataSourceKind.TableQualifier.SCHEMA) {
                if (dot >= 0 && name.substring(dot + 1).trim().isEmpty()) {
                    throw new BusinessException(400, "表「" + name + "」缺少表名，请填写「schema.表名」或只填表名");
                }
            } else if (dot >= 0) {
                throw new BusinessException(400, sourceKind.getLabel()
                        + " 的表清单只填表名（如 orders），schema 请在源端连接的 schema 参数里指定");
            }
        }
        FlinkSqlBuilder.serverIdBase(sourceKind, config, null, tables.size());
    }

    private void applyDto(IntegrationTask task, IntegrationTaskDTO dto) {
        applyProject(task, dto);
        task.setTaskName(dto.getTaskName());
        task.setDescription(dto.getDescription() == null ? "" : dto.getDescription());
        task.setSourceType(dto.getSourceType());
        task.setSourceConfig(toJson(visibleConfig(dto.getSourceType(), dto.getSourceConfig(), true)));
        task.setTargetType(dto.getTargetType());
        task.setTargetConfig(toJson(visibleConfig(dto.getTargetType(), dto.getTargetConfig(), false)));
        task.setClusterType(dto.getClusterType());
        task.setReadParallelism(dto.getReadParallelism() == null ? 1 : dto.getReadParallelism());
        task.setWriteParallelism(dto.getWriteParallelism() == null ? 1 : dto.getWriteParallelism());
        task.setJobMode(isNotBlank(dto.getJobMode()) ? dto.getJobMode().toUpperCase() : "BATCH");
        task.setTriggerType(isNotBlank(dto.getTriggerType()) ? dto.getTriggerType().toUpperCase() : "MANUAL");
        task.setTriggerConfig(dto.getTriggerConfig() == null ? "" : dto.getTriggerConfig());
        task.setExtraParams(toJson(dto.getExtraParams()));
    }

    private void persistTables(Long taskId, List<IntegrationTableDTO> tables) {
        for (IntegrationTableDTO dto : tables) {
            IntegrationTaskTable entity = new IntegrationTaskTable();
            entity.setTaskId(taskId);
            entity.setSourceTable(dto.getSourceTable());
            entity.setExcludeColumns(String.join(",", dto.getExcludeColumns() == null ? List.of()
                    : dto.getExcludeColumns()));
            entity.setSortNo(dto.getSortNo() == null ? 0 : dto.getSortNo());
            tableMapper.insert(entity);
        }
    }

    /**
     * 项目为必填：保存时按 project_id 回查 dev_project 并冗余写入当前 project_code。
     * 历史任务允许 project_id 为空地读取，但编辑保存与上线都会强制补齐。
     */
    private void applyProject(IntegrationTask task, IntegrationTaskDTO dto) {
        if (dto.getProjectId() == null) {
            throw new BusinessException(400, "请选择所属项目");
        }
        DevProject project = devProjectMapper.selectById(dto.getProjectId());
        if (project == null) {
            throw new BusinessException(400, "所选项目不存在: " + dto.getProjectId());
        }
        if (!isNotBlank(project.getProjectCode())) {
            throw new BusinessException(400, "项目「" + project.getProjectName() + "」未维护项目编码，"
                    + "无法作为数据血缘 namespace，请先在项目管理中补充项目编码");
        }
        task.setProjectId(project.getId());
        task.setProjectCode(project.getProjectCode().trim());
    }

    /**
     * 上线前校验并刷新项目编码，返回实际使用的 namespace（项目编码）。
     * 历史任务缺少项目、项目被删除或未维护编码时直接拒绝，避免血缘落到空 namespace / 固定值上。
     */
    private void refreshProjectForSubmit(IntegrationTask task) {
        if (task.getProjectId() == null) {
            throw new BusinessException(400,
                    "任务未设置所属项目，无法上线；请编辑任务选择项目并保存后重试");
        }
        DevProject project = devProjectMapper.selectById(task.getProjectId());
        if (project == null) {
            throw new BusinessException(400, "任务所属项目不存在（id=" + task.getProjectId()
                    + "），请编辑任务重新选择项目并保存");
        }
        if (!isNotBlank(project.getProjectCode())) {
            throw new BusinessException(400, "项目「" + project.getProjectName() + "」未维护项目编码，"
                    + "无法作为数据血缘 namespace，请先在项目管理中补充项目编码");
        }
        task.setProjectId(project.getId());
        task.setProjectCode(project.getProjectCode().trim());
    }

    private Map<Long, String> loadProjectNames(List<IntegrationTask> tasks) {
        Set<Long> projectIds = tasks.stream()
                .map(IntegrationTask::getProjectId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (DevProject project : devProjectMapper.selectBatchIds(projectIds)) {
            names.put(project.getId(), project.getProjectName());
        }
        return names;
    }

    private DataIntegrationSubmitter.SubmitContext buildContext(IntegrationTask task) {
        List<TableSpec> tables = tablesOf(task.getId()).stream()
                .map(t -> new TableSpec(t.getSourceTable(), splitColumns(t.getExcludeColumns()),
                        t.getSortNo() == null ? 0 : t.getSortNo()))
                .collect(Collectors.toList());
        return new DataIntegrationSubmitter.SubmitContext(
                task.getId(), task.getTaskName(), task.getTaskCode(), task.getProjectCode(), task.getJobMode(),
                task.getSourceType(), fromJson(task.getSourceConfig()),
                task.getTargetType(), fromJson(task.getTargetConfig()),
                tables, fromJson(task.getExtraParams()),
                task.getReadParallelism(), task.getWriteParallelism());
    }

    private IntegrationTask requireTask(Long id) {
        if (id == null) {
            throw new BusinessException(400, "缺少任务 ID");
        }
        IntegrationTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(404, "数据集成任务不存在: " + id);
        }
        return task;
    }

    private List<IntegrationTaskTable> tablesOf(Long taskId) {
        return tableMapper.selectList(new LambdaQueryWrapper<IntegrationTaskTable>()
                .eq(IntegrationTaskTable::getTaskId, taskId)
                .orderByAsc(IntegrationTaskTable::getSortNo)
                .orderByAsc(IntegrationTaskTable::getId));
    }

    private Map<Long, List<IntegrationTaskTable>> loadTables(Collection<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return Map.of();
        }
        List<IntegrationTaskTable> list = tableMapper.selectList(new LambdaQueryWrapper<IntegrationTaskTable>()
                .in(IntegrationTaskTable::getTaskId, taskIds)
                .orderByAsc(IntegrationTaskTable::getSortNo)
                .orderByAsc(IntegrationTaskTable::getId));
        return list.stream().collect(Collectors.groupingBy(IntegrationTaskTable::getTaskId));
    }

    private List<String> splitColumns(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return List.of();
        }
        List<String> columns = new ArrayList<>();
        for (String item : raw.split(",")) {
            if (!item.trim().isEmpty()) {
                columns.add(item.trim());
            }
        }
        return columns;
    }

    private void persistState(IntegrationTask task) {
        taskMapper.update(null, new LambdaUpdateWrapper<IntegrationTask>()
                .eq(IntegrationTask::getId, task.getId())
                .set(IntegrationTask::getStatus, task.getStatus())
                .set(IntegrationTask::getJobId, task.getJobId())
                .set(IntegrationTask::getProjectId, task.getProjectId())
                .set(IntegrationTask::getProjectCode, task.getProjectCode())
                .set(IntegrationTask::getLastSubmitTime, task.getLastSubmitTime())
                .set(IntegrationTask::getLastOfflineTime, task.getLastOfflineTime())
                .set(IntegrationTask::getLastError, task.getLastError())
                .set(IntegrationTask::getUpdateTime, LocalDateTime.now()));
    }

    private IntegrationSubmitLog writeLog(IntegrationTask task, String action, String resultStatus,
                                          String jobId, String logContent, String errorMessage, long startedAt) {
        IntegrationSubmitLog entity = new IntegrationSubmitLog();
        entity.setTaskId(task.getId());
        entity.setAction(action);
        entity.setClusterType(task.getClusterType());
        entity.setJobId(jobId);
        entity.setResultStatus(resultStatus);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        // 血缘 namespace 来源：任务所属项目的编码快照
        snapshot.put("projectId", task.getProjectId());
        snapshot.put("projectCode", task.getProjectCode());
        snapshot.put("sourceType", task.getSourceType());
        snapshot.put("sourceConfig", fromJson(task.getSourceConfig()));
        snapshot.put("targetType", task.getTargetType());
        snapshot.put("targetConfig", fromJson(task.getTargetConfig()));
        List<IntegrationTaskTable> tables = tablesOf(task.getId());
        Map<String, String> identifiers = resolveIdentifiers(task.getTargetType(),
                fromJson(task.getTargetConfig()), tables);
        List<Map<String, Object>> tableSnapshots = new ArrayList<>();
        for (IntegrationTaskTable table : tables) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sourceTable", table.getSourceTable());
            item.put("excludeColumns", splitColumns(table.getExcludeColumns()));
            item.put("targetIdentifier", identifiers.get(table.getSourceTable()));
            tableSnapshots.add(item);
        }
        snapshot.put("tables", tableSnapshots);
        snapshot.put("clusterType", task.getClusterType());
        snapshot.put("jobMode", task.getJobMode());
        snapshot.put("readParallelism", task.getReadParallelism());
        snapshot.put("writeParallelism", task.getWriteParallelism());
        snapshot.put("extraParams", fromJson(task.getExtraParams()));
        entity.setParamsSnapshot(toJson(snapshot));
        entity.setLogContent(logContent);
        entity.setErrorMessage(errorMessage);
        entity.setOperatorId(SecurityUtils.getCurrentUserId());
        entity.setOperatorName(SecurityUtils.getCurrentUsername());
        entity.setDurationMs(System.currentTimeMillis() - startedAt);
        submitLogMapper.insert(entity);
        return entity;
    }

    private IntegrationSubmitResultVO buildResultVO(IntegrationTask task, String message,
                                                    Long logId, long startedAt) {
        IntegrationSubmitResultVO vo = new IntegrationSubmitResultVO();
        vo.setTaskId(task.getId());
        vo.setJobId(task.getJobId());
        vo.setStatus(task.getStatus());
        vo.setStatusLabel(labelOf(task.getStatus()));
        vo.setMessage(message);
        vo.setLogId(logId);
        vo.setDurationMs(System.currentTimeMillis() - startedAt);
        return vo;
    }

    private IntegrationTaskVO toVO(IntegrationTask task, List<IntegrationTaskTable> tables,
                                   Map<Long, String> projectNames) {
        IntegrationTaskVO vo = new IntegrationTaskVO();
        vo.setId(task.getId());
        vo.setTaskName(task.getTaskName());
        vo.setTaskCode(task.getTaskCode());
        vo.setDescription(task.getDescription());
        vo.setProjectId(task.getProjectId());
        vo.setProjectCode(task.getProjectCode());
        // 项目被删除时仍返回编码快照，历史任务在列表/详情中可辨认
        vo.setProjectName(task.getProjectId() == null ? null : projectNames.get(task.getProjectId()));
        vo.setSourceType(task.getSourceType());
        vo.setSourceTypeLabel(labelOfSource(task.getSourceType()));
        vo.setSourceConfig(visibleConfig(task.getSourceType(), fromJson(task.getSourceConfig()), true));

        Map<String, Object> targetConfig = visibleConfig(task.getTargetType(), fromJson(task.getTargetConfig()), false);
        vo.setTargetType(task.getTargetType());
        vo.setTargetTypeLabel(labelOfSource(task.getTargetType()));
        vo.setTargetConfig(targetConfig);

        List<IntegrationTaskTable> safeTables = tables == null ? List.of() : tables;
        Map<String, String> identifiers = resolveIdentifiers(task.getTargetType(), targetConfig, safeTables);
        List<IntegrationTableVO> tableVOs = new ArrayList<>();
        for (IntegrationTaskTable table : safeTables) {
            IntegrationTableVO item = new IntegrationTableVO();
            item.setId(table.getId());
            item.setSourceTable(table.getSourceTable());
            item.setExcludeColumns(splitColumns(table.getExcludeColumns()));
            item.setTargetIdentifier(identifiers.get(table.getSourceTable()));
            item.setSortNo(table.getSortNo());
            tableVOs.add(item);
        }
        vo.setTables(tableVOs);
        vo.setTableCount(tableVOs.size());
        vo.setTableSummary(summarizeTables(tableVOs));

        vo.setClusterType(task.getClusterType());
        vo.setReadParallelism(task.getReadParallelism());
        vo.setWriteParallelism(task.getWriteParallelism());
        vo.setJobMode(task.getJobMode());
        vo.setTriggerType(task.getTriggerType());
        vo.setTriggerConfig(task.getTriggerConfig());
        vo.setExtraParams(fromJson(task.getExtraParams()));
        vo.setStatus(task.getStatus());
        vo.setStatusLabel(labelOf(task.getStatus()));
        vo.setJobId(task.getJobId());
        vo.setLastSubmitTime(task.getLastSubmitTime());
        vo.setLastOfflineTime(task.getLastOfflineTime());
        vo.setLastError(task.getLastError());
        vo.setCreateTime(task.getCreateTime());
        vo.setUpdateTime(task.getUpdateTime());
        return vo;
    }

    /**
     * 只回带当前类型声明过的连接字段：历史任务 source_config / target_config 里遗留的
     * table、columns 等已废弃 key 不再外显（编辑表单与详情「连接参数」都不会带出来）。
     */
    private Map<String, Object> visibleConfig(String type, Map<String, Object> config, boolean source) {
        if (config == null || config.isEmpty()) {
            return config;
        }
        DataSourceKind kind = DataSourceKind.fromCode(type);
        if (kind == null) {
            return config;
        }
        Map<String, Object> normalized = source && kind.isCdcSource()
                ? SourceConnection.normalize(kind, config) : config;
        List<DataSourceKind.ConnectorField> fields = source ? kind.getReadFields() : kind.getWriteFields();
        Map<String, Object> visible = new LinkedHashMap<>();
        if (fields == null) {
            return visible;
        }
        for (DataSourceKind.ConnectorField field : fields) {
            if (normalized.containsKey(field.getKey())) {
                visible.put(field.getKey(), normalized.get(field.getKey()));
            }
        }
        return visible;
    }

    /** 展示用：目标标识推导失败（历史数据/配置未填全）时返回空映射，不阻断列表与详情 */
    private Map<String, String> resolveIdentifiers(String targetType, Map<String, Object> targetConfig,
                                                   List<IntegrationTaskTable> tables) {
        DataSourceKind targetKind = DataSourceKind.fromCode(targetType);
        if (targetKind == null || tables == null || tables.isEmpty()) {
            return Map.of();
        }
        List<TableSpec> specs = tables.stream()
                .map(t -> new TableSpec(t.getSourceTable(), splitColumns(t.getExcludeColumns()),
                        t.getSortNo() == null ? 0 : t.getSortNo()))
                .collect(Collectors.toList());
        try {
            return TargetNaming.resolveAll(targetKind, targetConfig, specs);
        } catch (BusinessException e) {
            log.warn("推导目标标识失败，按空值展示: taskTables={}, reason={}", tables.size(), e.getMessage());
            return Map.of();
        }
    }

    /** 表清单摘要，如「ods.orders, ods.users」；超过 3 张时折叠为「等 N 张表」 */
    private String summarizeTables(List<IntegrationTableVO> tables) {
        List<String> items = tables.stream()
                .map(IntegrationTableVO::getSourceTable)
                .collect(Collectors.toList());
        if (items.isEmpty()) {
            return "未配置表";
        }
        String head = items.stream().limit(3).collect(Collectors.joining(", "));
        return items.size() > 3 ? head + " 等 " + items.size() + " 张表" : head;
    }

    private IntegrationSubmitLogVO toLogVO(IntegrationSubmitLog entity, boolean withContent) {
        IntegrationSubmitLogVO vo = new IntegrationSubmitLogVO();
        vo.setId(entity.getId());
        vo.setTaskId(entity.getTaskId());
        vo.setAction(entity.getAction());
        vo.setClusterType(entity.getClusterType());
        vo.setJobId(entity.getJobId());
        vo.setResultStatus(entity.getResultStatus());
        vo.setErrorMessage(entity.getErrorMessage());
        vo.setOperatorId(entity.getOperatorId());
        vo.setOperatorName(entity.getOperatorName());
        vo.setDurationMs(entity.getDurationMs());
        vo.setCreateTime(entity.getCreateTime());
        if (withContent) {
            vo.setLogContent(entity.getLogContent());
        }
        return vo;
    }

    private TaskStatus mapClusterStatus(String clusterStatus) {
        if (clusterStatus == null) {
            return null;
        }
        String state = clusterStatus.toUpperCase();
        return switch (state) {
            case "RUNNING", "CREATED", "RESTARTING", "INITIALIZING", "RECONCILING", "CANCELLING",
                 "DOING_SAVEPOINT", "SCHEDULED", "READY_STARTING", "FAILING" -> TaskStatus.RUNNING;
            case "FAILED" -> TaskStatus.FAILED;
            case "FINISHED" -> TaskStatus.PUBLISHED;
            case "CANCELED", "CANCELLED", "FINISHED_CANCELED" -> TaskStatus.OFFLINE;
            default -> null;
        };
    }

    private String labelOf(String status) {
        TaskStatus taskStatus = TaskStatus.fromCode(status);
        return taskStatus == null ? status : taskStatus.getLabel();
    }

    private String labelOfSource(String code) {
        DataSourceKind kind = DataSourceKind.fromCode(code);
        return kind == null ? code : kind.getLabel();
    }

    private String generateTaskCode() {
        String base = "DI" + System.currentTimeMillis();
        String code = base;
        int suffix = 0;
        while (taskMapper.selectCount(new LambdaQueryWrapper<IntegrationTask>()
                .eq(IntegrationTask::getTaskCode, code)) > 0) {
            code = base + "_" + (++suffix);
        }
        return code;
    }

    private Object lockOf(Long id) {
        return taskLocks.computeIfAbsent(id, key -> new Object());
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String abbreviate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }

    /** 给生成的作业配置加标题，便于在提交历史里区分配置内容与错误信息。 */
    private String withConfigHeader(String generatedConfig) {
        if (generatedConfig == null || generatedConfig.isBlank()) {
            return null;
        }
        return "===== 生成的作业配置 =====\n" + generatedConfig;
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map == null ? new LinkedHashMap<>() : map);
        } catch (Exception e) {
            throw new BusinessException(400, "参数不是合法的 JSON 结构: " + e.getMessage());
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("解析任务参数 JSON 失败，按空对象处理: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }
}
