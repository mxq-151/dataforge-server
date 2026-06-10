package com.bigdata.modules.development.service.execution;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.development.dto.CodeExecutionRequest;
import com.bigdata.modules.development.entity.DevScriptExecution;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.service.DatasourceConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class CodeExecutionService {

    private static final Logger log = LoggerFactory.getLogger(CodeExecutionService.class);

    private static final ExecutorService executorService = Executors.newCachedThreadPool();

    private final ConcurrentHashMap<String, CodeExecutionResult> resultCache = new ConcurrentHashMap<>();

    @Autowired
    private ExecutionStrategyFactory strategyFactory;

    @Autowired
    private DatasourceConfigService datasourceConfigService;

    @Autowired
    private ProcessManager processManager;

    @Autowired
    private ExecutionLogListener executionLogListener;

    /**
     * 同步执行代码（兼容旧接口）
     */
    public CodeExecutionResult executeCode(CodeExecutionRequest request, Long submitBy, String submitByName) {
        log.info("🚀 开始执行代码任务");
        log.info("   - 代码类型: {}", request.getCodeType());
        log.info("   - 引擎类型: {}", request.getEngineType());
        log.info("   - 数据源数量: {}", request.getDatasources() != null ? request.getDatasources().size() : 0);
        log.info("   - 代码长度: {} 字符", request.getCodeContent() != null ? request.getCodeContent().length() : 0);
        log.info("   - 提交人: {} ({})", submitByName, submitBy);

        String executionId = generateExecutionId();

        CodeExecutionContext context = buildContext(request, submitBy, submitByName);
        context.setExecutionId(executionId);
        validateContext(context);

        try {
            ExecutionStrategy strategy = strategyFactory.getStrategy(context.getEngineType(), context.getCodeType());
            if (strategy == null) {
                throw new BusinessException("未找到匹配的执行策略: engine=" + context.getEngineType() +
                        ", codeType=" + context.getCodeType());
            }

            log.info("📋 使用执行策略: {}, executionId={}, context.executionId={}",
                    strategy.getClass().getSimpleName(), executionId, context.getExecutionId());

            // 在请求线程中执行权限检查
            strategy.checkPermission(context);

            CodeExecutionResult result = strategy.execute(context);
            result.setExecutionId(executionId);
            resultCache.put(executionId, result);

            log.info("✅ 代码执行完成: engine={}, status={}, duration={}ms",
                    request.getEngineType(),
                    result.getStatus(), result.getDurationMs());

            return result;
        } catch (BusinessException e) {
            log.error("❌ 代码执行业务异常: {}", e.getMessage());
            CodeExecutionResult failResult = CodeExecutionResult.failure(executionId, e.getMessage());
            resultCache.put(executionId, failResult);
            return failResult;
        } catch (Exception e) {
            log.error("💥 代码执行系统异常", e);
            CodeExecutionResult failResult = CodeExecutionResult.failure(executionId, "系统异常: " + e.getMessage());
            resultCache.put(executionId, failResult);
            return failResult;
        }
    }

    /**
     * 提交任务（异步执行），立即返回 executionId
     */
    public CodeExecutionResult submitCode(CodeExecutionRequest request, Long submitBy, String submitByName) {
        log.info("📤 提交代码任务");
        log.info("   - 代码类型: {}", request.getCodeType());
        log.info("   - 引擎类型: {}", request.getEngineType());
        log.info("   - 提交人: {} ({})", submitByName, submitBy);

        CodeExecutionContext context = buildContext(request, submitBy, submitByName);
        validateContext(context);

        String executionId = generateExecutionId();
        context.setExecutionId(executionId);

        ExecutionStrategy strategy = strategyFactory.getStrategy(context.getEngineType(), context.getCodeType());
        if (strategy == null) {
            throw new BusinessException("未找到匹配的执行策略: engine=" + context.getEngineType() +
                    ", codeType=" + context.getCodeType());
        }

        CodeExecutionResult submitted = CodeExecutionResult.submitted(executionId, "任务已提交");
        resultCache.put(executionId, submitted);

        log.info("📋 使用执行策略: {}, executionId={}", strategy.getClass().getSimpleName(), executionId);

        // 在请求线程中执行权限检查（SecurityContext可用）
        log.info("🔒 执行权限检查: executionId={}", executionId);
        strategy.checkPermission(context);
        log.info("✅ 权限检查通过: executionId={}", executionId);

        final String finalExecutionId = executionId;
        // 传播 SecurityContext 到异步线程
        var scontext=SecurityContextHolder.getContext();
        executorService.submit(() -> {
            try {
                SecurityContextHolder.setContext(scontext);
                CodeExecutionResult result = strategy.execute(context);
                result.setExecutionId(finalExecutionId);
                resultCache.put(finalExecutionId, result);
            } catch (BusinessException e) {
                log.error("❌ 异步执行业务异常: executionId={}, error={}", finalExecutionId, e.getMessage());
                resultCache.put(finalExecutionId,
                        CodeExecutionResult.failure(finalExecutionId, e.getMessage()));
            } catch (Exception e) {
                log.error("💥 异步执行系统异常: executionId={}", finalExecutionId, e);
                resultCache.put(finalExecutionId,
                        CodeExecutionResult.failure(finalExecutionId, "系统异常: " + e.getMessage()));
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        return submitted;
    }

    /**
     * 获取执行结果
     */
    public CodeExecutionResult getResult(String executionId) {
        CodeExecutionResult result = resultCache.get(executionId);
        if (result == null) {
            return CodeExecutionResult.failure(executionId, "任务不存在或已过期: " + executionId);
        }
        return result;
    }

    /**
     * 获取执行日志（增量拉取）
     */
    public Map<String, Object> getExecutionLogs(String executionId, int offset) {
        List<String> logs = executionLogListener.pullLogs(executionId, offset);
        int totalCount = executionLogListener.getLogCount(executionId);
        int nextOffset = offset + logs.size();

        CodeExecutionResult result = resultCache.get(executionId);
        String status = (result != null) ? result.getStatus() : "UNKNOWN";
        boolean isComplete = result != null && !"RUNNING".equals(result.getStatus());

        Map<String, Object> logResult = new ConcurrentHashMap<>();
        logResult.put("executionId", executionId);
        logResult.put("status", status);
        logResult.put("logs", logs);
        logResult.put("offset", offset);
        logResult.put("nextOffset", nextOffset);
        logResult.put("totalCount", totalCount);
        logResult.put("isComplete", isComplete);

        return logResult;
    }

    private CodeExecutionContext buildContext(CodeExecutionRequest request, Long submitBy, String submitByName) {
        log.info("🔧 构建执行上下文...");
        CodeExecutionContext context = new CodeExecutionContext();
        context.setCodeContent(request.getCodeContent());
        context.setInputParams(convertRunParamsToJson(request.getRunParams()));
        context.setSubmitBy(submitBy);
        context.setSubmitByName(submitByName);
        context.setRunParams(request.getRunParams());
        context.setComputeResources(request.getComputeResources());
        context.setUploadedJarPath(request.getUploadedJarPath());
        context.setMainClass(request.getMainClass());
        context.setFileName(request.getFileName());

        CodeType codeType = CodeType.fromCode(request.getCodeType());
        if (codeType == null) {
            throw new BusinessException("无法识别的代码类型: " + request.getCodeType());
        }
        context.setCodeType(codeType);
        log.info("   代码类型: {}", codeType);

        EngineType engineType = EngineType.fromCode(request.getEngineType());
        if (engineType == null) {
            throw new BusinessException("无法识别的引擎类型: " + request.getEngineType());
        }
        context.setEngineType(engineType);
        log.info("   引擎类型: {}", engineType);

        List<DatasourceConfig> configs = loadDatasourceConfigsByName(request.getDatasources());
        context.setDatasourceConfigs(configs);
        log.info("   数据源配置: {} 个", configs.size());

        loadJarDependencies(context, request.getJarDependencyCatalogs());

        return context;
    }

    private void validateContext(CodeExecutionContext context) {

        if (context.getCodeType() == null) {
            throw new BusinessException("无法识别代码类型");
        }
        if (context.getEngineType() == null) {
            throw new BusinessException("未指定执行引擎");
        }
        if (!strategyFactory.isSupported(context.getEngineType(), context.getCodeType())) {
            throw new BusinessException(
                    String.format("执行引擎 %s 不支持代码类型 %s",
                            context.getEngineType().getDescription(),
                            context.getCodeType().getDescription()));
        }
        log.info("✅ 执行上下文校验通过");
    }

    private List<DatasourceConfig> loadDatasourceConfigsByName(List<String> datasourceNames) {
        List<DatasourceConfig> configs = new ArrayList<>();
        if (datasourceNames == null || datasourceNames.isEmpty()) {
            log.debug("未指定数据源");
            return configs;
        }

        for (String dsName : datasourceNames) {
            try {
                DatasourceConfig config = datasourceConfigService.getDatasourceByName(dsName.trim());
                if (config != null) {
                    configs.add(config);
                    log.debug("   加载数源成功: name={}", dsName);
                } else {
                    log.warn("⚠️ 数据源不存在: name={}", dsName);
                }
            } catch (Exception e) {
                log.error("❌ 加载数据源失败: name={}, error={}", dsName, e.getMessage());
            }
        }

        return configs;
    }

    private void loadJarDependencies(CodeExecutionContext context, List<String> jarCatalogs) {
        if (jarCatalogs != null && !jarCatalogs.isEmpty()) {
            String jarDeps = String.join(",", jarCatalogs);
            context.setJarDependencies(jarDeps);
            log.info("   JAR依赖: {}", jarDeps);
        } else {
            log.debug("未指定JAR依赖");
        }
    }

    private DevScriptExecution createExecutionRecord(CodeExecutionContext context) {
        DevScriptExecution execution = new DevScriptExecution();
        execution.setExecutionId(generateExecutionId());
        execution.setScriptId(0L);
        execution.setScriptType(context.getCodeType().getCode());
        execution.setExecuteMode(context.getEngineType().getCode());
        execution.setStatus("PENDING");
        execution.setStartTime(new Date());
        execution.setInputParams(context.getInputParams());
        execution.setSubmitBy(context.getSubmitBy());
        execution.setSubmitByName(context.getSubmitByName());
        execution.setCreateTime(new Date());

        log.info("📝 创建执行记录: id={}, type={}, mode={}",
                execution.getExecutionId(), execution.getScriptType(), execution.getExecuteMode());

        return execution;
    }


    private String generateExecutionId() {
        return "EXEC_" + System.currentTimeMillis() + "_" +
                Integer.toHexString((int) (Math.random() * 0xFFFF));
    }

    private String convertRunParamsToJson(Map<String, Object> runParams) {
        if (runParams == null || runParams.isEmpty()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(runParams);
        } catch (Exception e) {
            log.warn("序列化运行参数失败", e);
            return runParams.toString();
        }
    }

    private String truncateLog(String logStr) {
        if (logStr == null) {
            return null;
        }
        int maxLength = 65535;
        if (logStr.length() > maxLength) {
            return logStr.substring(0, maxLength - 100) + "\n... [日志已截断，总长度: " + logStr.length() + " 字符]";
        }
        return logStr;
    }

    public List<EngineType> getAvailableEngines() {
        return strategyFactory.getAvailableEngines();
    }

    public boolean isExecutionSupported(String engineCode, String codeType) {
        EngineType engineType = EngineType.fromCode(engineCode);
        CodeType type = CodeType.fromCode(codeType);
        if (engineType == null || type == null) {
            return false;
        }
        return strategyFactory.isSupported(engineType, type);
    }

    public boolean cancelExecution(String executionId, Long userId) {
        log.info("请求取消任务: executionId={}, userId={}", executionId, userId);
        boolean cancelled = processManager.cancel(executionId, userId);
        if (cancelled) {
            log.info("任务已取消: executionId={}", executionId);
        } else {
            log.warn("任务取消失败: executionId={}", executionId);
        }
        return cancelled;
    }

    public List<Map<String, Object>> getRunningProcesses(Long userId) {
        processManager.cleanup();
        return processManager.getRunningProcesses(userId);
    }
}
