package com.bigdata.modules.development.service.execution;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.development.dto.CodeExecutionRequest;
import com.bigdata.modules.development.entity.DevScriptExecution;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.devops.service.DatasourceConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
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

    @Autowired
    private com.bigdata.modules.development.service.DevJarDependencyService devJarDependencyService;

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

        DatasourceType engineType = DatasourceType.fromCode(request.getEngineType());
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
            // 解析为 basePath/{jarPath} 的完整路径，供 Spark/Flink 的 --jars 使用
            List<String> fullPaths = devJarDependencyService.resolveJarFullPaths(jarCatalogs);
            context.setJarDependencies(fullPaths);
            log.info("   JAR依赖: {}", fullPaths);
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

    public List<DatasourceType> getAvailableEngines() {
        return strategyFactory.getAvailableEngines();
    }

    public boolean isExecutionSupported(String engineCode, String codeType) {
        DatasourceType engineType = DatasourceType.fromCode(engineCode);
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

    /**
     * SQL语法校验：不真正执行业务SQL，仅利用各引擎自身的语法解析能力进行校验。
     * <ul>
     *   <li>MySQL：PREPARE stmt FROM '&lt;sql&gt;' （仅解析，不执行）</li>
     *   <li>Doris / StarRocks：EXPLAIN &lt;sql&gt;</li>
     *   <li>ClickHouse：EXPLAIN SYNTAX &lt;sql&gt;</li>
     * </ul>
     * 校验目标引擎由主数据源的 storageEngine 决定（与实际连接一致）。
     */
    public Map<String, Object> validateSqlSyntax(CodeExecutionRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();

        String sql = request.getCodeContent();
        if (sql == null || sql.trim().isEmpty()) {
            result.put("valid", false);
            result.put("message", "SQL语句不能为空");
            return result;
        }

        List<DatasourceConfig> configs = loadDatasourceConfigsByName(request.getDatasources());
        if (configs.isEmpty()) {
            throw new BusinessException("语法校验需要指定有效的数据源");
        }
        DatasourceConfig dsConfig = configs.get(0);
        DatasourceType storageEngine = dsConfig.getDatasourceType();
        if (storageEngine == null) {
            throw new BusinessException("数据源未配置存储引擎类型");
        }
        result.put("engine", storageEngine.name());

        String dbName = (dsConfig.getDatabaseName() != null && !dsConfig.getDatabaseName().isEmpty())
                ? dsConfig.getDatabaseName().get(0) : null;

        List<String> statements = splitSqlStatements(sql);
        List<Map<String, Object>> errors = new ArrayList<>();
        int skipped = 0;

        Connection connection = null;
        Statement stmt = null;
        try {
            connection = datasourceConfigService.getConnection(dsConfig.getId(), dbName);
            stmt = connection.createStatement();

            for (int i = 0; i < statements.size(); i++) {
                String statement = statements.get(i);
                if (isCommentOrBlank(statement)) {
                    continue;
                }
                String validateSql = buildValidateSql(storageEngine, statement);
                try {
                    stmt.execute(validateSql);
                    drainResults(stmt);
                } catch (SQLException e) {
                    // MySQL PREPARE 不支持的语句类型(如 USE/SET 会话命令)直接跳过，不视为语法错误
                    if (storageEngine == DatasourceType.MYSQL && isUnsupportedPrepareStatement(e)) {
                        skipped++;
                        continue;
                    }
                    log.error("SQL语法校验失败, 引擎={}, 数据源={}, 第{}条语句: [{}], 错误: {}",
                            storageEngine, dsConfig.getDatasourceName(), i + 1, statement, e.getMessage());
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("index", i + 1);
                    err.put("sql", statement);
                    err.put("message", e.getMessage());
                    errors.add(err);
                } finally {
                    // MySQL 的 PREPARE 会在会话中残留预编译语句，及时释放避免连接复用时的泄漏
                    if (storageEngine == DatasourceType.MYSQL) {
                        try { stmt.execute("DEALLOCATE PREPARE stmt"); } catch (Exception ignored) { }
                    }
                    // PostgreSQL 的事务包裹校验在语句失败后会留下 aborted 事务，
                    // 回滚清理，避免影响下一条语句的 BEGIN
                    if (storageEngine == DatasourceType.POSTGRESQL) {
                        try { stmt.execute("ROLLBACK"); } catch (Exception ignored) { }
                    }
                }
            }
        } catch (SQLException e) {
            log.error("SQL语法校验失败, 引擎={}, 数据源={}, 连接数据源失败: {}",
                    storageEngine, dsConfig.getDatasourceName(), e.getMessage());
            result.put("valid", false);
            result.put("message", "连接数据源失败: " + e.getMessage());
            return result;
        } finally {
            closeQuietly(stmt);
            closeQuietly(connection);
        }

        result.put("valid", errors.isEmpty());
        result.put("statementCount", statements.size());
        result.put("skipped", skipped);
        result.put("errors", errors);
        String message;
        if (errors.isEmpty()) {
            message = skipped > 0 ? "语法校验通过(跳过" + skipped + "条PREPARE不支持的语句)" : "语法校验通过";
        } else {
            message = "存在" + errors.size() + "处语法错误";
        }
        result.put("message", message);
        return result;
    }

    private String buildValidateSql(DatasourceType engine, String statement) {
        switch (engine) {
            case MYSQL:
                // 转义单引号后用 PREPARE 解析，不真正执行
                String escaped = statement.replace("'", "''");
                return "PREPARE stmt FROM '" + escaped + "'";
            case CLICKHOUSE:
                return "EXPLAIN SYNTAX " + statement;
            case POSTGRESQL:
                // 利用事务包裹 + 回滚做纯语法校验：
                // PostgreSQL 的语法错误在 parse/analyze 阶段即抛出；
                // DDL(CREATE TABLE等) 在事务内执行后可回滚，不落盘
                return "BEGIN; " + statement + "; ROLLBACK";
            case DORIS:
            case STARROCKS:
            default:
                return "EXPLAIN " + statement;
        }
    }

    /**
     * 判断是否为 MySQL "PREPARE 不支持该语句类型"的错误(非语法错误)。
     * MySQL 错误码 1295: ER_UNSUPPORTED_PS
     * "This command is not supported in the prepared statement protocol yet"
     */
    private boolean isUnsupportedPrepareStatement(SQLException e) {
        if (e.getErrorCode() == 1295) {
            return true;
        }
        String msg = e.getMessage();
        return msg != null && msg.contains("not supported in the prepared statement protocol");
    }

    /**
     * 消费执行后可能产生的结果集，避免影响下一条语句执行。
     */
    private void drainResults(Statement stmt) throws SQLException {
        while (true) {
            ResultSet rs = stmt.getResultSet();
            if (rs != null) {
                rs.close();
            }
            if (!stmt.getMoreResults() && stmt.getUpdateCount() == -1) {
                break;
            }
        }
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 判断片段是否仅由注释/空白构成（无实际可执行SQL），此类片段跳过校验。
     */
    private boolean isCommentOrBlank(String s) {
        if (s == null) return true;
        String t = s;
        t = t.replaceAll("(?s)/\\*.*?\\*/", " ");
        t = t.replaceAll("(?m)--.*$", " ");
        t = t.replaceAll("(?m)#.*$", " ");
        return t.trim().isEmpty();
    }

    /**
     * 按分号拆分SQL脚本为独立语句，拆分时跳过字符串字面量与注释内的分号。
     */
    private List<String> splitSqlStatements(String sql) {
        List<String> statements = new ArrayList<>();
        if (sql == null || sql.isEmpty()) return statements;

        StringBuilder current = new StringBuilder();
        int len = sql.length();
        int i = 0;
        while (i < len) {
            char c = sql.charAt(i);

            // 块注释 /* */
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                current.append(c).append(sql.charAt(i + 1));
                i += 2;
                while (i < len) {
                    char cc = sql.charAt(i);
                    current.append(cc);
                    if (cc == '*' && i + 1 < len && sql.charAt(i + 1) == '/') {
                        current.append(sql.charAt(i + 1));
                        i += 2;
                        break;
                    }
                    i++;
                }
                continue;
            }
            // 行注释 --
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                current.append(c).append(sql.charAt(i + 1));
                i += 2;
                while (i < len && sql.charAt(i) != '\n') {
                    current.append(sql.charAt(i));
                    i++;
                }
                continue;
            }
            // 单引号字符串
            if (c == '\'') {
                current.append(c);
                i++;
                while (i < len) {
                    char cc = sql.charAt(i);
                    current.append(cc);
                    if (cc == '\\' && i + 1 < len) {
                        current.append(sql.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    if (cc == '\'') {
                        if (i + 1 < len && sql.charAt(i + 1) == '\'') {
                            current.append(sql.charAt(i + 1));
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            // 双引号字符串/标识符
            if (c == '"') {
                current.append(c);
                i++;
                while (i < len) {
                    char cc = sql.charAt(i);
                    current.append(cc);
                    if (cc == '\\' && i + 1 < len) {
                        current.append(sql.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    if (cc == '"') {
                        if (i + 1 < len && sql.charAt(i + 1) == '"') {
                            current.append(sql.charAt(i + 1));
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            // 反引号标识符
            if (c == '`') {
                current.append(c);
                i++;
                while (i < len) {
                    char cc = sql.charAt(i);
                    current.append(cc);
                    if (cc == '`') {
                        if (i + 1 < len && sql.charAt(i + 1) == '`') {
                            current.append(sql.charAt(i + 1));
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            // 语句分隔符
            if (c == ';') {
                String st = current.toString().trim();
                if (!st.isEmpty()) statements.add(st);
                current.setLength(0);
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        String last = current.toString().trim();
        if (!last.isEmpty()) statements.add(last);
        return statements;
    }
}
