package com.bigdata.modules.development.service.execution;

import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.StorageEngineType;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.vo.ColumnMetadataVO;
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

    private final RestTemplate restTemplate = new RestTemplate();

    /** 异步执行的任务缓存，用于取消操作 */
    private final ConcurrentHashMap<String, Future<?>> asyncTaskMap = new ConcurrentHashMap<>();

    // ==================== Session 缓存 ====================

    /** Session缓存：同一token（用户名:密码）复用同一Kyuubi Session */
    private final ConcurrentHashMap<String, SessionEntry> sessionCache = new ConcurrentHashMap<>();

    /** Session最大空闲时间（毫秒），超过此时间未使用的session会被标记为可回收 */
    private static final long SESSION_IDLE_TIMEOUT_MS = 30 * 60 * 1000L; // 30分钟

    /**
     * Session缓存条目
     */
    private static class SessionEntry {
        final String sessionHandle;
        final String baseUrl;
        volatile long lastAccessTime;

        SessionEntry(String sessionHandle, String baseUrl) {
            this.sessionHandle = sessionHandle;
            this.baseUrl = baseUrl;
            this.lastAccessTime = System.currentTimeMillis();
        }

        void touch() {
            this.lastAccessTime = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - lastAccessTime > SESSION_IDLE_TIMEOUT_MS;
        }
    }

    /**
     * 根据用户名和密码构建缓存Key（即"token"）
     */
    private String buildToken(String username, String password) {
        return username + ":" + password;
    }

    /**
     * 获取或创建Session：优先从缓存取，缓存不存在或已过期则新建。
     * 同一token（相同用户名+密码）始终返回同一个Session。
     *
     * @return SessionEntry（包含sessionHandle和baseUrl），失败返回null
     */
    private SessionEntry getOrCreateSession(String baseUrl, String username, String password,
                                             CodeExecutionContext context) {
        String token = buildToken(username, password);
        SessionEntry entry = sessionCache.get(token);
        if (entry != null && !entry.isExpired()) {
            entry.touch();
            log.debug("[Kyuubi Session] 命中缓存: token={}, session={}", maskToken(token), entry.sessionHandle);
            return entry;
        }

        // 缓存未命中或已过期，加锁防止并发重复创建
        synchronized (this) {
            // 双重检查
            entry = sessionCache.get(token);
            if (entry != null && !entry.isExpired()) {
                entry.touch();
                return entry;
            }

            // 过期则先关闭旧session
            if (entry != null) {
                log.info("[Kyuubi Session] 清理过期Session: {}", entry.sessionHandle);
                doCloseSession(entry.baseUrl, entry.sessionHandle);
                sessionCache.remove(token);
            }

            // 创建新Session
            String newHandle = createSession(baseUrl, username, password, context);
            if (newHandle == null) {
                return null;
            }

            SessionEntry newEntry = new SessionEntry(newHandle, baseUrl);
            sessionCache.put(token, newEntry);
            log.info("[Kyuubi Session] 新建并缓存: token={}, session={}", maskToken(token), newHandle);
            return newEntry;
        }
    }

    /**
     * 关闭指定token对应的Session并从缓存中移除（用于显式登出/切换数据源场景）
     */
    private void invalidateSession(String username, String password) {
        String token = buildToken(username, password);
        SessionEntry entry = sessionCache.remove(token);
        if (entry != null) {
            doCloseSession(entry.baseUrl, entry.sessionHandle);
            log.info("[Kyuubi Session] 已失效并移除: session={}", entry.sessionHandle);
        }
    }

    /** 对token脱敏用于日志输出 */
    private String maskToken(String token) {
        int colonIdx = token.lastIndexOf(':');
        if (colonIdx > 0) {
            return token.substring(0, colonIdx) + ":****";
        }
        return "****";
    }

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
            // 1. 获取或复用Session（同一token缓存复用）
            SessionEntry sessionEntry = getOrCreateSession(baseUrl, username, password, context);
            if (sessionEntry == null) {
                return CodeExecutionResult.failure(executionId, "创建Kyuubi Session失败");
            }
            String sessionHandle = sessionEntry.sessionHandle;
            executionLogListener.publishLog(executionId, "[Kyuubi] Session就绪(缓存复用): " + sessionHandle);

            // 用AtomicReference持有operationHandle（提交SQL后才获得），供取消回调使用
            java.util.concurrent.atomic.AtomicReference<String> operationHandleRef = new java.util.concurrent.atomic.AtomicReference<>();

            // 注册到ProcessManager用于取消
            String registeredId = processManager.registerCancellable(executionId, () ->
                    cancelOperation(baseUrl, sessionHandle, operationHandleRef.get()), context.getSubmitBy(), "kyuubi");

            try {
                // 2. 应用会话设置（通过执行SET语句）
                applySessionSettingsViaRest(baseUrl, sessionHandle, context);

                // 3. 提交SQL执行
                String operationHandle = submitOperation(baseUrl, sessionHandle, sql);
                if (operationHandle == null) {
                    return CodeExecutionResult.failure(executionId, "提交SQL操作失败");
                }
                operationHandleRef.set(operationHandle);  // 赋值给取消回调
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
                // Session由缓存管理，不在此关闭（同一token复用）
                if (!isAsyncMode(context)) {
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
                // Session由缓存管理，不在此关闭
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
            body.put("configs", buildSessionConfiguration(context));

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, createAuthHeaders(username, password));
            log.info("[Kyuubi REST] 创建Session: {}", url);
            log.debug("[Kyuubi REST] 请求体: {}", body);
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
        if (identifier.isTextual()) {
            return identifier.asText();
        }
        if (identifier.has("guid")) {
            return identifier.get("guid").asText();
        }
        // 兜底：去掉首尾引号
        String raw = identifier.toString().trim();
        if (raw.startsWith("\"") && raw.endsWith("\"")) {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    /** 构建Session配置参数（包含存储引擎相关设置） */
    private Map<String, String> buildSessionConfiguration(CodeExecutionContext context) {
        Map<String, String> config = new LinkedHashMap<>();
        if (context == null || context.getPrimaryStorageEngine() == null) {
            return config;
        }
        StorageEngineType storageEngine = context.getPrimaryStorageEngine();

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

    /** POST /api/v1/sessions/{session}/operations/statement 提交SQL操作 */
    private String submitOperation(String baseUrl, String sessionHandle, String statement) {
        try {
            String url = baseUrl + "/api/v1/sessions/" + sessionHandle + "/operations/statement";
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
        if (identifier.isTextual()) {
            return identifier.asText();
        }
        if (identifier.has("guid")) {
            return identifier.get("guid").asText();
        }
        // 兜底：去掉首尾引号
        String raw = identifier.toString().trim();
        if (raw.startsWith("\"") && raw.endsWith("\"")) {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    /** 轮询操作状态直到完成 GET /api/v1/operations/{operationHandle}/event */
    private OperationResult pollForResult(String baseUrl, String sessionHandle, String operationHandle,
                                           long timeoutMs, long pollIntervalMs, String executionId) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String eventUrl = baseUrl + "/api/v1/operations/" + operationHandle + "/event";

        while (System.currentTimeMillis() < deadline) {
            try {
                ResponseEntity<JsonNode> eventResponse = restTemplate.exchange(
                        URI.create(eventUrl), HttpMethod.GET,
                        new HttpEntity<>(createAuthHeaders(null, null)), JsonNode.class);

                JsonNode eventBody = eventResponse.getBody();
                if (eventBody == null) { Thread.sleep(pollIntervalMs); continue; }

                String state = parseOperationState(eventBody);
                log.debug("[Kyuubi REST] 轮询状态: state={}", state);
                // 兼容两种格式: "FINISHED" / "FINISHED_STATE"
                String normalizedState = state.replace("_STATE", "");
                switch (normalizedState) {
                    case "FINISHED":
                    case "COMPLETED":
                        log.info("[Kyuubi REST] 操作完成, state={}", state);
                        return fetchOperationResult(baseUrl, operationHandle);
                    case "ERROR": {
                        OperationResult r = new OperationResult();
                        r.status = OperationStatus.ERROR;
                        // 从事件中提取基础错误信息
                        String eventError = extractErrorMessage(eventBody);
                        // 从操作详情端点补充更详细的错误信息
                        String detailError = fetchDetailedErrorInfo(baseUrl, operationHandle);
                        if (detailError != null && !detailError.isEmpty()
                                && !detailError.equals(eventError)) {
                            r.errorMessage = eventError + "\n详情: " + detailError;
                        } else {
                            r.errorMessage = eventError;
                        }
                        log.error("[Kyuubi REST] 操作执行失败: {}", r.errorMessage);
                        return r;
                    }
                    case "CANCELED": {
                        OperationResult r = new OperationResult();
                        r.status = OperationStatus.CANCELED;
                        r.errorMessage = "操作已被取消";
                        return r;
                    }
                    default:
                        if (executionLogListener != null) {
                            executionLogListener.publishLog(executionId, "[Kyuubi] 执行中... (" + state + ")");
                        }
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

    /**
     * 从KyuubiOperationEvent解析状态字符串
     * 官方文档: state字段值为 PENDING | RUNNING | FINISHED | ERROR | CANCELED
     */
    private String parseOperationState(JsonNode eventBody) {
        if (eventBody.has("state")) {
            return eventBody.get("state").asText().toUpperCase();
        }
        return "RUNNING";
    }

    /**
     * 从Kyuubi操作事件中提取完整的错误信息。
     * <p>
     * Kyuubi错误事件响应结构（HiveServer2兼容）：
     * <pre>{
     *   "state": "ERROR",
     *   "exception": "org.apache.spark.sql.AnalysisException",
     *   "message": "Table or view not found: xxx",
     *   "sqlState": "42000",
     *   "errorCode": 0,
     *   "javaClassName": "org.apache.spark.sql.AnalysisException"
     * }</pre>
     *
     * @param eventBody 操作事件JSON
     * @return 完整的错误信息（多行格式，包含所有可用字段）
     */
    private String extractErrorMessage(JsonNode eventBody) {
        StringBuilder sb = new StringBuilder();

        // 0. 记录原始响应体用于排查（仅ERROR级别）

        // === 处理 Kyuubi 实际的异常对象结构（最常见情况）===
        // 真实响应: {"exception": {"message":"...", "sqlstate":"42P01", "errorCode":0, "stackTrace":[...]}}
        if (eventBody.has("exception") && !eventBody.get("exception").isNull()
                && eventBody.get("exception").isObject()) {
            JsonNode excObj = eventBody.get("exception");

            // 1. 从 exception.message 提取核心错误信息
            if (excObj.has("message") && !excObj.get("message").isNull()) {
                String rawMsg = excObj.get("message").asText();
                sb.append(extractCoreError(rawMsg));
            }

            // 2. SQL状态码
            if (excObj.has("sqlstate") && !excObj.get("sqlstate").isNull()) {
                String sqlState = excObj.get("sqlstate").asText();
                if (!sqlState.isEmpty() && !"null".equals(sqlState)) {
                    sb.append(" [SQLState: ").append(sqlState).append("]");
                }
            }

            // 3. 错误码
            if (excObj.has("errorCode") && !excObj.get("errorCode").isNull()) {
                int code = excObj.get("errorCode").asInt();
                if (code != 0) {
                    sb.append(" [Code: ").append(code).append("]");
                }
            }
        }

        // === 兜底：顶层字段匹配（兼容非标准格式）===
        if (sb.length() == 0) {
            if (eventBody.has("exception") && eventBody.get("exception").isTextual()) {
                sb.append("[").append(eventBody.get("exception").asText()).append("] ");
            } else if (eventBody.has("javaClassName") && !eventBody.get("javaClassName").isNull()) {
                sb.append("[").append(eventBody.get("javaClassName").asText()).append("] ");
            }

            if (eventBody.has("message") && !eventBody.get("message").isNull()) {
                sb.append(eventBody.get("message").asText());
            } else if (eventBody.has("errorMessage") && !eventBody.get("errorMessage").isNull()) {
                sb.append(eventBody.get("errorMessage").asText());
            } else if (eventBody.has("error") && !eventBody.get("error").isNull()) {
                if (eventBody.get("error").isTextual()) {
                    sb.append(eventBody.get("error").asText());
                } else if (eventBody.get("error").isObject() && eventBody.get("error").has("message")) {
                    sb.append(eventBody.get("error").get("message").asText());
                }
            }

            if (eventBody.has("sqlState") && !eventBody.get("sqlState").isNull()) {
                sb.append(" [SQLState: ").append(eventBody.get("sqlState").asText()).append("]");
            }
            if (eventBody.has("errorCode") && !eventBody.get("errorCode").isNull()) {
                sb.append(" [Code: ").append(eventBody.get("errorCode").asInt()).append("]");
            }
        }

        // 最终兜底
        if (sb.length() == 0) {
            sb.append("Kyuubi执行错误(详见上方原始响应): ").append(truncateForLog(eventBody.toString(), 500));
        }

        return sb.toString();
    }

    /**
     * 从Kyubi/Spark异常消息中提取核心错误信息。
     * <p>
     * 原始消息通常包含多层包装和完整堆栈：
     * <pre>org.apache.kyuubi.KyuubiSQLException: Error operating ExecuteStatement:
     * org.apache.spark.sql.AnalysisException: [TABLE_OR_VIEW_NOT_FOUND] The table cannot be found...
     * \tat org.apache.spark... (几十行堆栈)</pre>
     * <p>
     * 提取策略：剥离KyuubiSQLException包装层，保留完整错误信息（含完整堆栈）
     */
    private String extractCoreError(String rawMessage) {
        if (rawMessage == null || rawMessage.isEmpty()) return "";

        // 剥离常见的包装层前缀
        String msg = rawMessage;
        String[] wrappers = {
                "org.apache.kyuubi.KyuubiSQLException: Error operating ExecuteStatement: ",
                "org.apache.kyuubi.KyuubiSQLException: ",
                "Error operating ExecuteStatement: ",
                "org.apache.hive.service.cli.HiveSQLException: "
        };
        for (String wrapper : wrappers) {
            if (msg.startsWith(wrapper)) {
                msg = msg.substring(wrapper.length());
                break;
            }
        }

        return msg.trim();
    }

    /** 截断过长字符串用于日志输出 */
    private String truncateForLog(String str, int maxLen) {
        if (str == null) return "null";
        return str.length() > maxLen ? str.substring(0, maxLen) + "...(truncated)" : str;
    }

    /**
     * 从操作详情端点获取更详细的错误信息（补充extractErrorMessage）。
     * 使用 GET /api/v1/operations/{operationHandle}/status 获取操作状态详情。
     */
    private String fetchDetailedErrorInfo(String baseUrl, String operationHandle) {
        try {
            // 尝试 status 子资源
            String detailUrl = baseUrl + "/api/v1/operations/" + operationHandle + "/status";
            ResponseEntity<JsonNode> detailResponse = restTemplate.exchange(
                    URI.create(detailUrl), HttpMethod.GET,
                    new HttpEntity<>(createAuthHeaders(null, null)), JsonNode.class);
            JsonNode detailBody = detailResponse.getBody();
            if (detailBody != null) {
                String msg = extractErrorMessageFromNode(detailBody);
                if (msg != null) return msg;
            }
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.debug("[Kyuubi REST] /status 端点不可用({}), 尝试根端点", e.getStatusCode());
            // /status 不存在，尝试不带子资源的 GET
        } catch (Exception e) {
            log.debug("[Kyuubi REST] 获取/status 失败: {}", e.getMessage());
        }

        // 兜底：尝试直接 GET /operations/{handle}（部分版本支持）
        try {
            String detailUrl = baseUrl + "/api/v1/operations/" + operationHandle;
            ResponseEntity<JsonNode> detailResponse = restTemplate.exchange(
                    URI.create(detailUrl), HttpMethod.GET,
                    new HttpEntity<>(createAuthHeaders(null, null)), JsonNode.class);
            JsonNode detailBody = detailResponse.getBody();
            if (detailBody != null) {
                String msg = extractErrorMessageFromNode(detailBody);
                if (msg != null) return msg;
            }
        } catch (Exception e) {
            log.debug("[Kyuubi REST] 获取操作详情失败(非致命): {}", e.getMessage());
        }
        return null;
    }

    /**
     * 从任意 JsonNode 中递归查找错误消息字段
     */
    private String extractErrorMessageFromNode(JsonNode node) {
        if (node == null || node.isNull()) return null;

        // 直接字段匹配
        String[] errorFields = {"errorMessage", "message", "error", "reason", "error_message"};
        for (String field : errorFields) {
            if (node.has(field) && !node.get(field).isNull() && node.get(field).isTextual()) {
                String val = node.get(field).asText();
                if (!val.isEmpty()) return val;
            }
        }

        // 嵌套在 status 对象中
        if (node.has("status") && node.get("status").isObject()) {
            String nested = extractErrorMessageFromNode(node.get("status"));
            if (nested != null) return nested;
        }

        // 嵌套在 error 对象中
        if (node.has("error") && node.get("error").isObject()) {
            String nested = extractErrorMessageFromNode(node.get("error"));
            if (nested != null) return nested;
        }

        return null;
    }

    /**
     * 获取操作结果数据（分两步：先取schema，再取rowset）
     * GET /operations/{handle}/resultsetmetadata → 列信息
     * GET /operations/{handle}/rowset → 行数据
     */
    private OperationResult fetchOperationResult(String baseUrl, String operationHandle) {
        OperationResult result = new OperationResult();
        result.status = OperationStatus.FINISHED;

        // 1. 获取结果集列元数据
        try {
            String metaUrl = baseUrl + "/api/v1/operations/" + operationHandle + "/resultsetmetadata";
            ResponseEntity<JsonNode> metaResponse = restTemplate.exchange(
                    URI.create(metaUrl), HttpMethod.GET,
                    new HttpEntity<>(createAuthHeaders(null, null)), JsonNode.class);
            JsonNode metaBody = metaResponse.getBody();
            log.debug("[Kyuubi REST] resultsetmetadata原始响应: {}", metaBody);
            if (metaBody != null && metaBody.has("columns")) {
                List<String> columnNames = new ArrayList<>();
                for (JsonNode col : metaBody.get("columns")) {
                    if (col.has("columnName")) {
                        columnNames.add(col.get("columnName").asText());
                    }
                }
                result.columns = columnNames;
                log.debug("[Kyuubi REST] 列名: {}", columnNames);
            }
        } catch (Exception e) {
            log.warn("[Kyuubi REST] 获取结果集元数据失败", e);
        }

        // 2. 获取行数据
        try {
            String rowsetUrl = baseUrl + "/api/v1/operations/" + operationHandle + "/rowset?maxrows=" + MAX_RESULT_ROWS;
            ResponseEntity<JsonNode> rowResponse = restTemplate.exchange(
                    URI.create(rowsetUrl), HttpMethod.GET,
                    new HttpEntity<>(createAuthHeaders(null, null)), JsonNode.class);
            JsonNode rowBody = rowResponse.getBody();
            if (rowBody != null && rowBody.has("rows")) {
                List<List<String>> rows = new ArrayList<>();
                for (JsonNode row : rowBody.get("rows")) {
                    List<String> rowData = new ArrayList<>();
                    if (row.has("fields")) {
                        for (JsonNode field : row.get("fields")) {
                            if (field.has("value")) {
                                rowData.add(field.get("value").isNull() ? "NULL" : field.get("value").asText());
                            } else {
                                rowData.add("");
                            }
                        }
                    }
                    rows.add(rowData);
                }
                result.rows = rows;
                result.rowCount = rows.size();
            }
            if (rowBody != null && rowBody.has("rowCount")) {
                result.rowCount = rowBody.get("rowCount").asLong();
            }
            log.info("[Kyuubi REST] 行数据: columns={}, rows={}, rowCount={}",
                    result.columns != null ? result.columns.size() : "null",
                    result.rows != null ? result.rows.size() : "null",
                    result.rowCount);
        } catch (Exception e) {
            log.warn("[Kyuubi REST] 获取结果集数据失败", e);
        }

        return result;
    }

    /** PUT /api/v1/operations/{operationHandle} 取消正在执行的操作 */
    private void cancelOperation(String baseUrl, String sessionHandle, String operationHandle) {
        if (operationHandle == null) {
            log.info("[Kyuubi REST] 操作尚未提交，跳过取消（直接关闭Session）");
            doCloseSession(baseUrl, sessionHandle);
            return;
        }
        try {
            String url = baseUrl + "/api/v1/operations/" + operationHandle;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("action", "cancel");
            restTemplate.exchange(URI.create(url), HttpMethod.PUT,
                    new HttpEntity<>(body, createAuthHeaders(null, null)), Void.class);
            log.info("[Kyuubi REST] 操作已取消: operation={}", operationHandle);
        } catch (Exception e) {
            log.warn("[Kyuubi REST] 取消操作异常", e);
        }
    }

    /** DELETE 关闭Session（底层HTTP调用，不操作缓存） */
    private void doCloseSession(String baseUrl, String sessionHandle) {
        try {
            String url = baseUrl + "/api/v1/sessions/" + sessionHandle;
            restTemplate.exchange(URI.create(url), HttpMethod.DELETE,
                    new HttpEntity<>(createAuthHeaders(null, null)), Void.class);
            log.info("[Kyuubi REST] Session已关闭: {}", sessionHandle);
        } catch (Exception e) {
            log.warn("[Kyuubi REST] 关闭Session异常: {}", e.getMessage());
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

    // ==================== 数据源元数据查询方法 ====================

    /**
     * 从DatasourceConfig构建最小化的执行上下文（仅用于Session配置）
     */
    private CodeExecutionContext buildMinimalContext(DatasourceConfig datasourceConfig) {
        CodeExecutionContext ctx = new CodeExecutionContext();
        ctx.setDatasourceConfigs(java.util.Collections.singletonList(datasourceConfig));
        return ctx;
    }

    @Override
    public boolean testConnection(DatasourceConfig datasourceConfig) {
        try {
            String baseUrl = buildBaseUrl(datasourceConfig.getUrl());
            CodeExecutionContext ctx = buildMinimalContext(datasourceConfig);
            SessionEntry entry = getOrCreateSession(baseUrl, datasourceConfig.getUsername(), datasourceConfig.getPassword(), ctx);
            return entry != null && entry.sessionHandle != null && !entry.sessionHandle.isEmpty();
        } catch (Exception e) {
            log.warn("测试Kyuubi连接失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public List<String> listDatabases(DatasourceConfig datasourceConfig) {
        List<String> databases = new ArrayList<>();
        try {
            String baseUrl = buildBaseUrl(datasourceConfig.getUrl());
            CodeExecutionContext ctx = buildMinimalContext(datasourceConfig);
            SessionEntry entry = getOrCreateSession(baseUrl, datasourceConfig.getUsername(), datasourceConfig.getPassword(), ctx);
            if (entry == null) return databases;
            String sessionHandle = entry.sessionHandle;

            log.info("[Kyuubi REST] listDatabases 开始(复用Session), sessionHandle={}", sessionHandle);

            String operationHandle = submitOperation(baseUrl, sessionHandle, "SHOW DATABASES");
            log.info("[Kyuubi REST] listDatabases operationHandle={}", operationHandle);
            if (operationHandle == null) return databases;

            OperationResult result = pollForResult(baseUrl, sessionHandle, operationHandle, 120000, 2000, "metadata-query");
            log.info("[Kyuubi REST] listDatabases 轮询结果: status={}, columns={}, rows={}",
                    result.status, result.columns != null ? result.columns.size() : "null",
                    result.rows != null ? result.rows.size() : "null");
            if (result.rows != null && !result.rows.isEmpty()) {
                log.debug("[Kyuubi REST] listDatabases 首行数据: {}", result.rows.get(0));
            }
            if (result.status == OperationStatus.FINISHED && result.rows != null) {
                for (List<String> row : result.rows) {
                    if (!row.isEmpty()) {
                        databases.add(row.get(0));
                    }
                }
            } else if (result.status == OperationStatus.ERROR) {
                log.warn("[Kyuubi REST] listDatabases 执行错误: {}", result.errorMessage);
            }
        } catch (Exception e) {
            log.warn("通过Kyuubi获取数据库列表失败: {}", e.getMessage(), e);
        }
        // Session由缓存管理，不在此关闭
        return databases;
    }

    @Override
    public List<TableMetadata> listTables(DatasourceConfig datasourceConfig, String databaseName) {
        List<TableMetadata> tables = new ArrayList<>();
        try {
            String baseUrl = buildBaseUrl(datasourceConfig.getUrl());
            CodeExecutionContext ctx = buildMinimalContext(datasourceConfig);
            SessionEntry entry = getOrCreateSession(baseUrl, datasourceConfig.getUsername(), datasourceConfig.getPassword(), ctx);
            if (entry == null) return tables;
            String sessionHandle = entry.sessionHandle;

            String sql = "SHOW TABLES IN " + databaseName;
            String operationHandle = submitOperation(baseUrl, sessionHandle, sql);
            if (operationHandle == null) return tables;

            OperationResult result = pollForResult(baseUrl, sessionHandle, operationHandle, 120000, 2000, "metadata-query");
            if (result.status == OperationStatus.FINISHED && result.rows != null) {
                for (List<String> row : result.rows) {
                    if (!row.isEmpty()) {
                        TableMetadata tableMeta = new TableMetadata();
                        tableMeta.setTableName(row.get(0));
                        tableMeta.setTableType("TABLE");
                        tableMeta.setDatabaseName(databaseName);
                        tables.add(tableMeta);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("通过Kyuubi获取表列表失败[{}]: {}", databaseName, e.getMessage());
        }
        // Session由缓存管理，不在此关闭
        return tables;
    }

    @Override
    public List<ColumnMetadataVO> listColumns(DatasourceConfig datasourceConfig, String databaseName, String tableName) {
        List<ColumnMetadataVO> columns = new ArrayList<>();
        try {
            String baseUrl = buildBaseUrl(datasourceConfig.getUrl());
            CodeExecutionContext ctx = buildMinimalContext(datasourceConfig);
            SessionEntry entry = getOrCreateSession(baseUrl, datasourceConfig.getUsername(), datasourceConfig.getPassword(), ctx);
            if (entry == null) return columns;
            String sessionHandle = entry.sessionHandle;

            String sql = "DESCRIBE " + databaseName + "." + tableName;
            String operationHandle = submitOperation(baseUrl, sessionHandle, sql);
            if (operationHandle == null) return columns;

            OperationResult result = pollForResult(baseUrl, sessionHandle, operationHandle, 120000, 2000, "metadata-query");
            if (result.status == OperationStatus.FINISHED && result.rows != null) {
                int position = 1;
                for (List<String> row : result.rows) {
                    if (row.size() < 2) continue;
                    ColumnMetadataVO col = new ColumnMetadataVO();
                    col.setColumnName(row.get(0));
                    col.setColumnType(row.get(1));
                    col.setOrdinalPosition(position++);
                    col.setNullable(1);
                    col.setDatabaseName(databaseName);
                    col.setTableName(tableName);
                    if (row.size() >= 3) {
                        col.setColumnComment(row.get(2));
                    }
                    columns.add(col);
                }
            }
        } catch (Exception e) {
            log.warn("通过Kyuubi获取字段信息失败[{}.{}]: {}", databaseName, tableName, e.getMessage());
        }
        // Session由缓存管理，不在此关闭
        return columns;
    }
}
