package com.bigdata.modules.development.service.execution;

import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.StorageEngineType;
import com.bigdata.modules.metadata.service.MetadataSyncService;
import com.bigdata.modules.system.entity.SysTablePermission;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Kyuubi执行策略，通过REST API与Kyuubi Server交互。
 * <p>
 * 继承 {@link AbstractBaseExecutionStrategy}，复用统一的权限校验、行级过滤和数据脱敏能力。
 * 支持同步和异步两种执行模式。
 * </p>
 */
@Component
public class KyuubiExecutionStrategy extends AbstractBaseExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(KyuubiExecutionStrategy.class);
    private static final int MAX_RESULT_ROWS = 1000;
    private static final long ASYNC_POLL_INTERVAL_MS = 2000;
    private static final long SYNC_POLL_TIMEOUT_MS = 300000; // 5分钟同步超时
    private static final long ASYNC_POLL_TIMEOUT_MS = 600000; // 10分钟异步超时

    @Autowired
    private ProcessManager processManager;

    @Autowired
    private MetadataSyncService metadataSyncService;

    @Autowired
    private ExecutionLogListener executionLogListener;

    private final RestTemplate restTemplate = new RestTemplate();

    /** 异步执行的任务缓存，用于取消操作 */
    private final ConcurrentHashMap<String, Future<?>> asyncTaskMap = new ConcurrentHashMap<>();

    @Override
    protected Logger getLog() {
        return log;
    }

    @Override
    public EngineType getEngineType() {
        return EngineType.KYUUBI;
    }

    /**
     * 判断是否为异步执行模式（从运行参数中读取）
     */
    private boolean isAsyncMode(CodeExecutionContext context) {
        Map<String, Object> runParams = context.getRunParams();
        if (runParams != null && runParams.containsKey("executionMode")) {
            return "async".equalsIgnoreCase(String.valueOf(runParams.get("executionMode")));
        }
        return false;
    }

    @Override
    public CodeExecutionResult execute(CodeExecutionContext context) {
        String executionId = context.getExecutionId();
        log.info("[Kyuubi REST] 策略开始执行, executionId={}, mode={}", executionId,
                isAsyncMode(context) ? "ASYNC" : "SYNC");
        long startTime = System.currentTimeMillis();

        if (context.getPrimaryDatasourceConfig() == null) {
            return CodeExecutionResult.failure(executionId, "Kyuubi执行引擎需要指定数据源");
        }

        DatasourceConfig dsConfig = context.getPrimaryDatasourceConfig();
        String baseUrl = buildBaseUrl(dsConfig.getUrl());
        String username = dsConfig.getUsername();
        String password = dsConfig.getPassword();

        String sql = context.getCodeContent();
        if (sql == null || sql.trim().isEmpty()) {
            return CodeExecutionResult.failure(executionId, "SQL语句不能为空");
        }
        sql = sql.trim();

        try {
            // 1. 创建Session
            String sessionHandle = createSession(baseUrl, username, password, context);
            if (sessionHandle == null) {
                return CodeExecutionResult.failure(executionId, "创建Kyuubi Session失败");
            }
            executionLogListener.publishLog(executionId, "[Kyuubi] Session创建成功: " + sessionHandle);

            // 注册到ProcessManager用于取消
            String registeredId = processManager.registerCancellable(() ->
                    cancelOperation(baseUrl, sessionHandle, null), context.getSubmitBy(), "kyuubi");

            try {
                // 2. 应用会话设置（通过执行SET语句）
                applySessionSettingsViaRest(baseUrl, sessionHandle, context);

                // 3. 提交SQL执行
                String operationHandle = submitOperation(baseUrl, sessionHandle, sql);
                if (operationHandle == null) {
                    return CodeExecutionResult.failure(executionId, "提交SQL操作失败");
                }
                executionLogListener.publishLog(executionId, "[Kyuubi] SQL已提交, operationHandle=" + operationHandle);

                boolean asyncMode = isAsyncMode(context);

                if (asyncMode) {
                    // 异步模式：后台轮询，立即返回RUNNING
                    return executeAsync(executionId, baseUrl, sessionHandle, operationHandle,
                            sql, context, startTime, registeredId);
                } else {
                    // 同步模式：阻塞等待结果
                    return executeSync(executionId, baseUrl, sessionHandle, operationHandle,
                            sql, context, startTime, registeredId);
                }
            } finally {
                if (!isAsyncMode(context)) {
                    closeSession(baseUrl, sessionHandle);
                    processManager.unregister(registeredId);
                }
            }
        } catch (SecurityException e) {
            log.error("Kyuubi REST 执行权限异常: {}", e.getMessage());
            executionLogListener.publishLog(executionId, "[错误] " + e.getMessage());
            long duration = System.currentTimeMillis() - startTime;
            CodeExecutionResult failResult = CodeExecutionResult.failure(executionId, e.getMessage());
            failResult.setStartTime(new Date(startTime));
            failResult.setEndTime(new Date());
            failResult.setDurationMs((int) duration);
            return failResult;
        } catch (Exception e) {
            log.error("Kyuubi REST 执行异常", e);
            long duration = System.currentTimeMillis() - startTime;
            String errMsg = "Kyuubi REST执行异常: " + e.getMessage();
            executionLogListener.publishLog(executionId, "[异常] " + errMsg);
            CodeExecutionResult result = CodeExecutionResult.failure(executionId, errMsg);
            result.setStartTime(new Date(startTime));
            result.setEndTime(new Date());
            result.setDurationMs((int) duration);
            return result;
        }
    }

    /**
     * 同步执行：轮询直到操作完成，返回完整结果
     */
    private CodeExecutionResult executeSync(String executionId, String baseUrl, String sessionHandle,
                                            String operationHandle, String sql, CodeExecutionContext context,
                                            long startTime, String registeredId) {
        try {
            OperationResult opResult = pollForResult(baseUrl, sessionHandle, operationHandle,
                    SYNC_POLL_TIMEOUT_MS, ASYNC_POLL_INTERVAL_MS, executionId);

            if (opResult.status == OperationStatus.ERROR) {
                executionLogListener.publishLog(executionId, "[错误] SQL执行错误: " + opResult.errorMessage);
                long duration = System.currentTimeMillis() - startTime;
                CodeExecutionResult result = CodeExecutionResult.failure(executionId,
                        "SQL执行失败: " + opResult.errorMessage);
                result.setStartTime(new Date(startTime));
                result.setEndTime(new Date());
                result.setDurationMs((int) duration);
                return result;
            }

            // 处理DDL元数据同步
            handleDdlMetadataSync(sql, context, executionId);

            // 构建成功结果
            List<String> columns = opResult.columns;
            List<List<String>> rows = opResult.rows;
            int affectedRows = opResult.rowCount > 0 ? (int) opResult.rowCount :
                    (opResult.modifiedRowCount > 0 ? (int) opResult.modifiedRowCount : 0);

            // 如果有结果集，应用行级过滤和脱敏（调用基类方法）
            if (columns != null && !columns.isEmpty() && rows != null && !rows.isEmpty()) {
                applyRowFilterAndMasking(rows, columns, sql, context);
            }

            long duration = System.currentTimeMillis() - startTime;
            int rowCount = rows != null ? rows.size() : 0;
            int colCount = columns != null ? columns.size() : 0;

            log.info("[Kyuubi REST] 同步执行完成: executionId={}, 列数={}, 行数={}", executionId, colCount, rowCount);
            executionLogListener.publishLog(executionId, "[完成] 执行成功, 耗时" + duration + "ms" +
                    (rowCount > 0 ? ", 返回" + rowCount + "行" : ""));

            CodeExecutionResult result = CodeExecutionResult.success(executionId, "执行成功",
                    columns, rows, affectedRows);
            result.setStartTime(new Date(startTime));
            result.setEndTime(new Date());
            result.setDurationMs((int) duration);
            return result;
        } catch (Exception e) {
            log.error("[Kyuubi REST] 同步执行过程异常", e);
            long duration = System.currentTimeMillis() - startTime;
            CodeExecutionResult result = CodeExecutionResult.failure(executionId, "执行异常: " + e.getMessage());
            result.setStartTime(new Date(startTime));
            result.setEndTime(new Date());
            result.setDurationMs((int) duration);
            return result;
        }
    }

    /**
     * 异步执行：立即返回RUNNING状态，后台线程轮询获取最终结果
     */
    private CodeExecutionResult executeAsync(String executionId, String baseUrl, String sessionHandle,
                                             String operationHandle, String sql, CodeExecutionContext context,
                                             long startTime, String registeredId) {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "kyuubi-async-" + executionId);
            t.setDaemon(true);
            return t;
        });

        Future<?> future = executor.submit(() -> {
            try {
                log.info("[Kyuubi REST] 异步任务开始轮询: executionId={}", executionId);
                executionLogListener.publishLog(executionId, "[Kyuubi] 异步执行中, 正在等待结果...");

                OperationResult opResult = pollForResult(baseUrl, sessionHandle, operationHandle,
                        ASYNC_POLL_TIMEOUT_MS, ASYNC_POLL_INTERVAL_MS, executionId);

                if (opResult.status == OperationStatus.ERROR) {
                    executionLogListener.publishLog(executionId, "[错误] 异步SQL执行错误: " + opResult.errorMessage);
                } else {
                    handleDdlMetadataSync(sql, context, executionId);

                    List<String> columns = opResult.columns;
                    List<List<String>> rows = opResult.rows;
                    int affectedRows = opResult.rowCount > 0 ? (int) opResult.rowCount :
                            (opResult.modifiedRowCount > 0 ? (int) opResult.modifiedRowCount : 0);

                    if (columns != null && !columns.isEmpty() && rows != null && !rows.isEmpty()) {
                        applyRowFilterAndMasking(rows, columns, sql, context);
                    }

                    long duration = System.currentTimeMillis() - startTime;
                    int rowCount = rows != null ? rows.size() : 0;
                    executionLogListener.publishLog(executionId,
                            "[完成] 异步执行成功, 耗时" + duration + "ms" +
                                    (rowCount > 0 ? ", 返回" + rowCount + "行" : ""));
                }
            } catch (Exception e) {
                log.error("[Kyuubi REST] 异步执行过程异常: executionId={}", executionId, e);
                executionLogListener.publishLog(executionId, "[异常] 异步执行异常: " + e.getMessage());
            } finally {
                try {
                    closeSession(baseUrl, sessionHandle);
                    log.info("[Kyuubi REST] 异步任务Session已关闭: executionId={}", executionId);
                } catch (Exception e) {
                    log.warn("[Kyuubi REST] 关闭异步Session异常", e);
                }
                processManager.unregister(registeredId);
                asyncTaskMap.remove(executionId);
                executor.shutdown();
            }
        });

        asyncTaskMap.put(executionId, future);

        executionLogListener.publishLog(executionId, "[Kyuubi] 任务已异步提交, 请等待结果...");
        CodeExecutionResult result = CodeExecutionResult.submitted(executionId, "任务已异步提交");
        result.setStartTime(new Date(startTime));
        return result;
    }

    // ==================== Kyuubi REST API 调用方法 ====================

    /** 从JDBC URL构建REST基础URL：jdbc:hive2://host:port/db -> http://host:port */
    private String buildBaseUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isEmpty()) {
            throw new IllegalArgumentException("数据源URL不能为空");
        }
        if (jdbcUrl.startsWith("http://") || jdbcUrl.startsWith("https://")) {
            return jdbcUrl.replaceAll("/+$", "");
        }
        String cleaned = jdbcUrl.replace("jdbc:hive2://", "").replace("jdbc:kyuubi://", "")
                .replace("jdbc:kyuubi2://", "");
        int slashIdx = cleaned.indexOf('/');
        int semicolonIdx = cleaned.indexOf(';');
        int endIdx = cleaned.length();
        if (slashIdx > 0) endIdx = Math.min(endIdx, slashIdx);
        if (semicolonIdx > 0) endIdx = Math.min(endIdx, semicolonIdx);
        return "http://" + cleaned.substring(0, endIdx).trim();
    }

    /** 创建Basic Auth认证头 */
    private HttpHeaders createAuthHeaders(String username, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (username != null && password != null) {
            String auth = username + ":" + password;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            headers.set("Authorization", "Basic " + encodedAuth);
        }
        return headers;
    }

    /** POST /api/v1/sessions 创建Kyuubi Session */
    private String createSession(String baseUrl, String username, String password,
                                 CodeExecutionContext context) {
        try {
            String url = baseUrl + "/api/v1/sessions";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("protocolVersion", 13);
            body.put("catalog", "spark");
            body.put("configuration", buildSessionConfiguration(context));

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, createAuthHeaders(username, password));
            log.info("[Kyuubi REST] 创建Session: {}", url);
            ResponseEntity<JsonNode> response = restTemplate.exchange(URI.create(url), HttpMethod.POST, request, JsonNode.class);

            JsonNode responseBody = response.getBody();
            if (responseBody != null && responseBody.has("identifier")) {
                String handle = extractSessionHandle(responseBody.get("identifier"));
                log.info("[Kyuubi REST] Session创建成功: {}", handle);
                return handle;
            }
            if (responseBody != null && responseBody.has("status") && responseBody.get("status").has("errorMessage")) {
                log.error("[Kyuubi REST] Session创建失败: {}", responseBody.get("status").get("errorMessage").asText());
            }
            return null;
        } catch (Exception e) {
            log.error("[Kyuubi REST] 创建Session异常", e);
            return null;
        }
    }

    private String extractSessionHandle(JsonNode identifier) {
        return identifier.has("guid") ? identifier.get("guid").asText() : identifier.toString();
    }

    /** 构建Session配置参数（包含存储引擎相关设置） */
    private Map<String, String> buildSessionConfiguration(CodeExecutionContext context) {
        Map<String, String> config = new LinkedHashMap<>();
        StorageEngineType storageEngine = context.getPrimaryStorageEngine();
        if (storageEngine == null) return config;

        switch (storageEngine) {
            case HIVE:
                config.put("hive.exec.parallel", "true");
                config.put("hive.exec.parallel.thread.number", "8");
                config.put("hive.auto.convert.join", "true");
                break;
            case DORIS:
                config.put("enable_vectorized_engine", "true");
                config.put("batch_size", "4096");
                break;
            case STARROCKS:
                config.put("enable_pipeline", "true");
                config.put("pipeline_dop", "4");
                break;
            case HUDI:
                config.put("spark.sql.hive.convertMetastoreParquet", "false");
                config.put("hoodie.upsert.shuffle.parallelism", "200");
                break;
            case ICEBERG:
                config.put("iceberg.engine.spark.execute-in-place-merge", "true");
                break;
            case CLICKHOUSE:
                config.put("max_execution_time", "300000");
                break;
            default:
                break;
        }
        return config;
    }

    /** 通过REST API应用会话设置（执行SET语句） */
    private void applySessionSettingsViaRest(String baseUrl, String sessionHandle, CodeExecutionContext context) {
        StorageEngineType storageEngine = context.getPrimaryStorageEngine();
        if (storageEngine == null) return;

        for (Map.Entry<String, String> entry : getSettingsForStorageEngine(storageEngine).entrySet()) {
            String setSql = "SET " + entry.getKey() + "=" + entry.getValue();
            try {
                submitOperation(baseUrl, sessionHandle, setSql);
                log.debug("[Kyuubi REST] 应用会话设置: {}", setSql);
            } catch (Exception e) {
                log.warn("[Kyuubi REST] 应用会话设置失败: {}, 错误: {}", setSql, e.getMessage());
            }
        }
    }

    private Map<String, String> getSettingsForStorageEngine(StorageEngineType engineType) {
        Map<String, String> settings = new LinkedHashMap<>();
        switch (engineType) {
            case HIVE:
                settings.put("hive.exec.parallel", "true");
                settings.put("hive.exec.parallel.thread.number", "8");
                break;
            case DORIS:
                settings.put("enable_vectorized_engine", "true");
                settings.put("batch_size", "4096");
                break;
            case STARROCKS:
                settings.put("enable_pipeline", "true");
                settings.put("pipeline_dop", "4");
                break;
            case HUDI:
                settings.put("spark.sql.hive.convertMetastoreParquet", "false");
                settings.put("hoodie.upsert.shuffle.parallelism", "200");
                break;
            case ICEBERG:
                settings.put("iceberg.engine.spark.execute-in-place-merge", "true");
                break;
            case CLICKHOUSE:
                settings.put("max_execution_time", "300000");
                break;
            default:
                break;
        }
        return settings;
    }

    /** POST /api/v1/sessions/{session}/operations 提交SQL操作 */
    private String submitOperation(String baseUrl, String sessionHandle, String statement) {
        try {
            String url = baseUrl + "/api/v1/sessions/" + sessionHandle + "/operations";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("statement", statement);
            body.put("runAsync", true);
            body.put("queryTimeout", 300);

            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    URI.create(url), HttpMethod.POST,
                    new HttpEntity<>(body, createAuthHeaders(null, null)), JsonNode.class);

            JsonNode responseBody = response.getBody();
            if (responseBody != null && responseBody.has("identifier")) {
                return extractOperationHandle(responseBody.get("identifier"));
            }
            return null;
        } catch (Exception e) {
            log.error("[Kyuubi REST] 提交操作异常", e);
            return null;
        }
    }

    private String extractOperationHandle(JsonNode identifier) {
        return identifier.has("guid") ? identifier.get("guid").asText() : identifier.toString();
    }

    /** 轮询操作状态直到完成 GET /api/v1/sessions/{session}/operations/{operation} */
    private OperationResult pollForResult(String baseUrl, String sessionHandle, String operationHandle,
                                           long timeoutMs, long pollIntervalMs, String executionId) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String url = baseUrl + "/api/v1/sessions/" + sessionHandle + "/operations/" + operationHandle;

        while (System.currentTimeMillis() < deadline) {
            try {
                ResponseEntity<JsonNode> response = restTemplate.exchange(
                        URI.create(url), HttpMethod.GET,
                        new HttpEntity<>(createAuthHeaders(null, null)), JsonNode.class);

                JsonNode body = response.getBody();
                if (body == null) { Thread.sleep(pollIntervalMs); continue; }

                OperationStatus status = parseOperationStatus(body);
                switch (status) {
                    case FINISHED:
                        return parseOperationResult(body);
                    case ERROR: {
                        OperationResult r = new OperationResult();
                        r.status = OperationStatus.ERROR;
                        r.errorMessage = extractErrorMessage(body);
                        return r;
                    }
                    case CANCELED: {
                        OperationResult r = new OperationResult();
                        r.status = OperationStatus.CANCELED;
                        r.errorMessage = "操作已被取消";
                        return r;
                    }
                    default:
                        executionLogListener.publishLog(executionId, "[Kyuubi] 执行中... (" + status + ")");
                        Thread.sleep(pollIntervalMs);
                        break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                OperationResult r = new OperationResult();
                r.status = OperationStatus.CANCELED;
                r.errorMessage = "执行被中断";
                return r;
            } catch (Exception e) {
                log.warn("[Kyuubi REST] 轮询操作状态异常, 将重试: {}", e.getMessage());
                try { Thread.sleep(pollIntervalMs); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    OperationResult r = new OperationResult();
                    r.status = OperationStatus.CANCELED;
                    r.errorMessage = "执行被中断";
                    return r;
                }
            }
        }

        OperationResult timeoutResult = new OperationResult();
        timeoutResult.status = OperationStatus.ERROR;
        timeoutResult.errorMessage = "执行超时（" + (timeoutMs / 1000) + "秒）";
        return timeoutResult;
    }

    private OperationStatus parseOperationStatus(JsonNode body) {
        if (body.has("status") && body.get("status").has("statusCode")) {
            int code = body.get("status").get("statusCode").asInt();
            switch (code) {
                case 0: return OperationStatus.INITIALIZED;
                case 1: return OperationStatus.RUNNING;
                case 2: return OperationStatus.FINISHED;
                case 3: return OperationStatus.CANCELED;
                case 4: return OperationStatus.ERROR;
                default: return OperationStatus.RUNNING;
            }
        }
        return OperationStatus.RUNNING;
    }

    private String extractErrorMessage(JsonNode body) {
        if (body.has("status") && body.get("status").has("errorMessage")) {
            String msg = body.get("status").get("errorMessage").asText();
            if (msg != null && !msg.isEmpty()) return msg;
        }
        return "未知错误";
    }

    /** 解析操作结果（包含结果集） */
    private OperationResult parseOperationResult(JsonNode body) {
        OperationResult result = new OperationResult();
        result.status = OperationStatus.FINISHED;

        if (body.has("results") && body.get("results").isArray()) {
            List<String> columns = new ArrayList<>();
            List<List<String>> rows = new ArrayList<>();

            for (JsonNode resultNode : body.get("results")) {
                if (resultNode.has("schema") && resultNode.get("schema").has("columns")) {
                    for (JsonNode col : resultNode.get("schema").get("columns")) {
                        if (col.has("name")) columns.add(col.get("name").asText());
                    }
                }
                if (resultNode.has("data") && resultNode.get("data").isArray()) {
                    int rowCount = 0;
                    for (JsonNode rowNode : resultNode.get("data")) {
                        if (rowCount >= MAX_RESULT_ROWS) break;
                        List<String> row = new ArrayList<>();
                        if (rowNode.isArray()) {
                            for (JsonNode cell : rowNode) row.add(cell.isNull() ? "NULL" : cell.asText());
                        } else if (rowNode.isObject() && rowNode.has("values") && rowNode.get("values").isArray()) {
                            for (JsonNode cell : rowNode.get("values")) row.add(cell.isNull() ? "NULL" : cell.asText());
                        }
                        rows.add(row);
                        rowCount++;
                    }
                }
            }
            result.columns = columns;
            result.rows = rows;
            result.rowCount = rows.size();
        }

        if (body.has("modifiedRowCount")) result.modifiedRowCount = body.get("modifiedRowCount").asLong();
        if (body.has("rowCount")) result.rowCount = body.get("rowCount").asLong();

        return result;
    }

    /** DELETE 取消正在执行的操作 */
    private void cancelOperation(String baseUrl, String sessionHandle, String operationHandle) {
        try {
            String url = baseUrl + "/api/v1/sessions/" + sessionHandle + "/operations/" + operationHandle;
            restTemplate.exchange(URI.create(url), HttpMethod.DELETE,
                    new HttpEntity<>(createAuthHeaders(null, null)), Void.class);
            log.info("[Kyuubi REST] 操作已取消: session={}, operation={}", sessionHandle, operationHandle);
        } catch (Exception e) {
            log.warn("[Kyuubi REST] 取消操作异常", e);
        }
    }

    /** DELETE 关闭Session */
    private void closeSession(String baseUrl, String sessionHandle) {
        try {
            String url = baseUrl + "/api/v1/sessions/" + sessionHandle;
            restTemplate.exchange(URI.create(url), HttpMethod.DELETE,
                    new HttpEntity<>(createAuthHeaders(null, null)), Void.class);
            log.info("[Kyuubi REST] Session已关闭: {}", sessionHandle);
        } catch (Exception e) {
            log.warn("[Kyuubi REST] 关闭Session异常: {}", e.getMessage());
        }
    }

    // ==================== 元数据同步 & 行过滤/脱敏（调用基类方法）====================

    /** DDL元数据同步 */
    private void handleDdlMetadataSync(String sql, CodeExecutionContext context, String executionId) {
        DdlInfo ddlInfo = detectDdlStatement(sql);
        if (ddlInfo == null) return;

        String ddlMsg = "DDL语句执行成功: " + ddlInfo.ddlType + " " +
                (ddlInfo.databaseName != null ? ddlInfo.databaseName + "." : "") + ddlInfo.tableName;
        log.info(ddlMsg + ", 开始同步元数据");
        executionLogListener.publishLog(executionId, "[DDL] " + ddlMsg);
        try {
            boolean syncSuccess = metadataSyncService.syncSingleTableMetadata(
                    context.getPrimaryDatasourceConfig().getId(),
                    context.getPrimaryStorageEngine(),
                    ddlInfo.databaseName, ddlInfo.tableName, ddlInfo.ddlType);
            if (syncSuccess) {
                log.info("元数据同步成功: {}.{}", ddlInfo.databaseName, ddlInfo.tableName);
                executionLogListener.publishLog(executionId, "[元数据] 同步成功: " + ddlInfo.tableName);
            } else {
                log.warn("元数据同步失败: {}.{}", ddlInfo.databaseName, ddlInfo.tableName);
                executionLogListener.publishLog(executionId, "[元数据] 同步失败: " + ddlInfo.tableName);
            }
        } catch (Exception syncEx) {
            log.error("元数据同步异常: {}.{}", ddlInfo.databaseName, ddlInfo.tableName, syncEx);
            executionLogListener.publishLog(executionId, "[元数据] 同步异常: " + syncEx.getMessage());
        }
    }

    /** 对查询结果统一应用行级过滤和数据脱敏（调用基类提供的方法） */
    private void applyRowFilterAndMasking(List<List<String>> rows, List<String> columns,
                                           String sql, CodeExecutionContext context) {
        String datasourceName = context.getPrimaryDatasourceConfig().getDatasourceName();
        String defaultDatabase = context.getPrimaryDatasourceDatabaseName();

        SelectPermissionInfo permInfo = extractSelectPermissionInfo(sql, datasourceName, defaultDatabase);
        if (permInfo != null && permInfo.tableRef != null) {
            String dbName = permInfo.tableRef.databaseName != null ?
                    permInfo.tableRef.databaseName : defaultDatabase;
            SysTablePermission mergedPerm = dataAccessPermissionService.getMergedTablePermission(
                    datasourceName, dbName, permInfo.tableRef.tableName);
            if (mergedPerm != null) {
                if (mergedPerm.getRowFilterSql() != null && !mergedPerm.getRowFilterSql().isBlank()) {
                    int beforeSize = rows.size();
                    rows = applyRowFilter(rows, columns, mergedPerm.getRowFilterSql());
                    log.info("行级过滤: {} -> {} 行, 过滤条件: {}", beforeSize, rows.size(),
                            mergedPerm.getRowFilterSql());
                }
                if (mergedPerm.getDataMaskingRule() != null && !mergedPerm.getDataMaskingRule().isBlank()) {
                    applyDataMasking(rows, columns, mergedPerm.getDataMaskingRule());
                    log.info("已应用数据脱敏规则, 表: {}.{}", dbName, permInfo.tableRef.tableName);
                }
            }
        }
    }

    // ==================== 内部数据结构 ====================

    private enum OperationStatus {
        INITIALIZED, RUNNING, FINISHED, CANCELED, ERROR
    }

    private static class OperationResult {
        OperationStatus status = OperationStatus.INITIALIZED;
        String errorMessage;
        List<String> columns;
        List<List<String>> rows;
        long rowCount;
        long modifiedRowCount;
    }
}
