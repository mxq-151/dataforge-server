package com.bigdata.modules.development.service.execution;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.development.dto.CodeExecutionRequest;
import com.bigdata.modules.development.entity.DevScriptExecution;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.service.DatasourceConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
public class CodeExecutionService {

    private static final Logger log = LoggerFactory.getLogger(CodeExecutionService.class);

    @Autowired
    private ExecutionStrategyFactory strategyFactory;

    @Autowired
    private DatasourceConfigService datasourceConfigService;

    
    public CodeExecutionResult executeCode(CodeExecutionRequest request, Long submitBy, String submitByName) {
        log.info("🚀 开始执行代码任务");
        log.info("   - 代码类型: {}", request.getCodeType());
        log.info("   - 引擎类型: {}", request.getEngineType());
        log.info("   - 数据源数量: {}", request.getDatasources() != null ? request.getDatasources().size() : 0);
        log.info("   - 代码长度: {} 字符", request.getCodeContent() != null ? request.getCodeContent().length() : 0);
        log.info("   - 提交人: {} ({})", submitByName, submitBy);

        CodeExecutionContext context = buildContext(request, submitBy, submitByName);
        validateContext(context);

        DevScriptExecution executionRecord = createExecutionRecord(context);

        try {
            ExecutionStrategy strategy = strategyFactory.getStrategy(context.getEngineType(), context.getCodeType());
            if (strategy == null) {
                throw new BusinessException("未找到匹配的执行策略: engine=" + context.getEngineType() +
                        ", codeType=" + context.getCodeType());
            }

            log.info("📋 使用执行策略: {}", strategy.getClass().getSimpleName());
            CodeExecutionResult result = strategy.execute(context);

            log.info("✅ 代码执行完成: engine={}, status={}, duration={}ms",
                    request.getEngineType(),
                    result.getStatus(), result.getDurationMs());

            return result;
        } catch (BusinessException e) {
            log.error("❌ 代码执行业务异常: {}", e.getMessage());
            return CodeExecutionResult.failure(executionRecord.getExecutionId(), e.getMessage());
        } catch (Exception e) {
            log.error("💥 代码执行系统异常", e);
            return CodeExecutionResult.failure(executionRecord.getExecutionId(), "系统异常: " + e.getMessage());
        }
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
}
