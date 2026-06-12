package com.bigdata.modules.development.service.execution;

import com.bigdata.modules.development.config.EngineProperties;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.devops.enums.StorageEngineType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


@Component
public class SparkExecutionStrategy implements ExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(SparkExecutionStrategy.class);

    @Autowired
    private EngineProperties engineProperties;

    @Autowired
    private ProcessManager processManager;

    @Autowired
    private ExecutionLogListener executionLogListener;

    @Override
    public CodeExecutionResult execute(CodeExecutionContext context) {
        String executionId = context.getExecutionId();
        long startTime = System.currentTimeMillis();

        try {
            List<String> command = buildCommand(context);

            log.info("Spark提交命令: {}", String.join(" ", command));
            log.info("   - 执行ID: {}", executionId);
            log.info("   - 引擎类型: {}", context.getEngineType());
            log.info("   - 代码类型: {}", context.getCodeType());
            log.info("   - 数据源数量: {}", context.getDatasourceConfigs().size());
            log.info("   - 运行参数: {}", context.getRunParams());
            log.info("   - 计算资源: {}", context.getComputeResources());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String registeredId = processManager.registerProcess(
                    context.getExecutionId(), process, context.getSubmitBy(), "spark");

            // 推送进程启动日志，确保 buffer 中立即有内容
            executionLogListener.publishLog(executionId, "[Spark] 进程已启动, PID=" + process.pid());

            StringBuilder output = new StringBuilder();
            CountDownLatch readLatch = new CountDownLatch(1);

            // 异步线程：实时读取进程输出并发布日志
            Thread readerThread = new Thread(() -> {
                log.info("📖 开始读取Spark进程输出: executionId={}, pid={}", executionId, process.pid());
                int lineCount = 0;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                        lineCount++;
                        executionLogListener.publishLog(executionId, line);
                    }
                } catch (Exception e) {
                    log.error("读取Spark输出异常", e);
                } finally {
                    log.info("📖 Spark进程输出读取完毕: executionId={}, 共{}行, output长度={}",
                            executionId, lineCount, output.length());
                    readLatch.countDown();
                }
            }, "spark-output-reader-" + executionId);
            readerThread.setDaemon(true);
            readerThread.start();

            // 主线程：等待进程结束，然后等待读线程排空剩余缓冲区
            int exitCode = process.waitFor();
            log.info("⏳ Spark进程已退出: exitCode={}, 等待读线程排空...", exitCode);
            readLatch.await(5, TimeUnit.SECONDS);

            long duration = System.currentTimeMillis() - startTime;

            processManager.unregister(registeredId);

            if (exitCode == 0) {
                CodeExecutionResult result = CodeExecutionResult.success(executionId, output.toString());
                result.setStartTime(new java.util.Date(startTime));
                result.setEndTime(new java.util.Date());
                result.setDurationMs((int) duration);
                return result;
            } else {
                // 提取核心错误信息，避免返回全量Spark日志（可能数千行）
                String rawOutput = output.toString();
                log.error("[Spark-ERROR] 进程退出码={}, 输出总长度={}字符, 共{}行", exitCode, rawOutput.length(), countLines(rawOutput));
                log.error("[Spark-ERROR] 原始输出(前500字): {}", truncateForLog(rawOutput, 500));

                String errorMessage = extractCoreError(rawOutput);
                log.error("[Spark-ERROR] 提取的核心错误: {}", errorMessage);

                executionLogListener.publishLog(executionId, "[ERROR] " + errorMessage);

                CodeExecutionResult result = CodeExecutionResult.failure(executionId, errorMessage);
                result.setStartTime(new java.util.Date(startTime));
                result.setEndTime(new java.util.Date());
                result.setDurationMs((int) duration);
                return result;
            }
        } catch (Exception e) {
            log.error("Spark执行失败", e);
            long duration = System.currentTimeMillis() - startTime;
            CodeExecutionResult result = CodeExecutionResult.failure(executionId, e.getMessage());
            result.setStartTime(new java.util.Date(startTime));
            result.setEndTime(new java.util.Date());
            result.setDurationMs((int) duration);
            return result;
        }
    }

    private List<String> buildCommand(CodeExecutionContext context) throws Exception {
        List<String> command = new ArrayList<>();

        // SQL模式使用 spark-sql（支持 -e / -f 参数），其他模式使用 spark-submit
        if (context.getCodeType() == CodeType.SQL) {
            command.add(engineProperties.getSpark().getSparkSqlCommand());
        } else {
            command.add(engineProperties.getSpark().getSparkSubmitCommand());
        }

        addComputeResources(command, context);
        addAllDatasourceConfs(command, context);
        addJarDependencies(command, context);
        addInputParams(command, context);
        addCodeTypeSpecificOptions(command, context);

        return command;
    }

    private void addComputeResources(List<String> command, CodeExecutionContext context) {
        Map<String, Object> resources = context.getComputeResources();
        if (resources == null || resources.isEmpty()) {
            return;
        }

        log.info("添加计算资源配置:");

        Object driverMemory = resources.get("driver-memory");
        if (driverMemory != null) {
            command.add("--driver-memory");
            command.add(driverMemory.toString());
        }

        Object executorMemory = resources.get("executor-memory");
        if (executorMemory != null) {
            command.add("--executor-memory");
            command.add(executorMemory.toString());
        }

        Object executorCores = resources.get("executor-cores");
        if (executorCores != null) {
            command.add("--executor-cores");
            command.add(executorCores.toString());
        }

        Object numExecutors = resources.get("num-executors");
        if (numExecutors != null) {
            command.add("--num-executors");
            command.add(numExecutors.toString());
        }

        Object master = resources.get("master");
        if (master != null) {
            command.add("--master");
            command.add(master.toString());
        }

        Object deployMode = resources.get("deploy-mode");
        if (deployMode != null) {
            command.add("--deploy-mode");
            command.add(deployMode.toString());
        }

        for (Map.Entry<String, Object> entry : resources.entrySet()) {
            String key = entry.getKey();
            if (!isStandardResourceKey(key)) {
                Object value = entry.getValue();
                command.add("--conf");
                command.add("spark." + key + "=" + value);
            }
        }
    }

    private boolean isStandardResourceKey(String key) {
        return List.of("driver-memory", "executor-memory", "executor-cores",
                       "num-executors", "master", "deploy-mode").contains(key);
    }

    private void addAllDatasourceConfs(List<String> command, CodeExecutionContext context) {
        List<DatasourceConfig> datasourceConfigs = context.getDatasourceConfigs();
        if (datasourceConfigs == null || datasourceConfigs.isEmpty()) {
            return;
        }

        for (DatasourceConfig dsConfig : datasourceConfigs) {
            String dsName = dsConfig.getDatasourceName();
            DatasourceType dsType = dsConfig.getDatasourceType();
            StorageEngineType storageEngine = dsConfig.getStorageEngine();
            String confPrefix = "spark." + dsName;

            command.add("--conf");
            command.add(confPrefix + ".type=" + (dsType != null ? dsType.getCode() : "unknown"));

            if (dsConfig.getUrl() != null) {
                command.add("--conf");
                command.add(confPrefix + ".url=" + dsConfig.getUrl());
            }

            if (dsConfig.getUsername() != null) {
                command.add("--conf");
                command.add(confPrefix + ".user=" + dsConfig.getUsername());
            }

            if (dsConfig.getPassword() != null) {
                command.add("--conf");
                command.add(confPrefix + ".password=" + dsConfig.getPassword());
            }

            if (dsConfig.getDatabaseName() != null && !dsConfig.getDatabaseName().isEmpty()) {
                command.add("--conf");
                command.add(confPrefix + ".database=" + dsConfig.getDatabaseName().get(0));
            }

            addDatasourceSpecificConf(command, confPrefix, dsConfig, storageEngine);
        }
    }

    private void addDatasourceSpecificConf(List<String> command, String confPrefix,
                                            DatasourceConfig dsConfig, StorageEngineType storageEngine) {
        DatasourceType dsType = dsConfig.getDatasourceType();

        switch (dsType) {
            case HIVE:
                command.add("--conf");
                command.add(confPrefix + ".metastore.uris=" + dsConfig.getUrl());
                if (storageEngine == StorageEngineType.HUDI) {
                    command.add("--conf");
                    command.add(confPrefix + ".sql.extensions=org.apache.spark.sql.hudi.HudiSparkSessionExtension");
                    command.add("--conf");
                    command.add(confPrefix + ".serializer=org.apache.spark.serializer.KryoSerializer");
                } else if (storageEngine == StorageEngineType.ICEBERG) {
                    command.add("--conf");
                    command.add(confPrefix + ".sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions");
                    command.add("--conf");
                    command.add(confPrefix + ".catalog.type=hive");
                }
                break;
            case MYSQL:
                command.add("--conf");
                command.add(confPrefix + ".jdbc.driver=com.mysql.cj.jdbc.Driver");
                break;
            case DORIS:
                command.add("--conf");
                command.add(confPrefix + ".fenier.host=" + extractHost(dsConfig.getUrl()));
                command.add("--conf");
                command.add(confPrefix + ".fenier.query.port=" + extractPort(dsConfig.getUrl(), "9030"));
                command.add("--conf");
                command.add(confPrefix + ".catalog.type=doris");
                break;
            case CLICKHOUSE:
                command.add("--conf");
                command.add(confPrefix + ".write.batch.size=10000");
                command.add("--conf");
                command.add(confPrefix + ".connection.timeout=30000");
                break;
            default:
                if (dsConfig.getExtraParams() != null && !dsConfig.getExtraParams().isEmpty()) {
                    command.add("--conf");
                    command.add(confPrefix + ".extra.params=" + dsConfig.getExtraParams());
                }
                break;
        }
    }

    private void addInputParams(List<String> command, CodeExecutionContext context) {
        Map<String, Object> runParams = context.getRunParams();
        if (runParams == null || runParams.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : runParams.entrySet()) {
            String paramName = entry.getKey();
            Object paramValue = entry.getValue();

            if (paramValue instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> paramDetail = (Map<String, Object>) paramValue;
                Object value = paramDetail.get("value");
                command.add("--conf");
                command.add("spark.app.params." + paramName + "=" +
                           (value != null ? value.toString() : ""));
            } else {
                command.add("--conf");
                command.add("spark.app.params." + paramName + "=" + paramValue.toString());
            }
        }
    }

    private void addJarDependencies(List<String> command, CodeExecutionContext context) {
        String jarDependencies = context.getJarDependencies();
        if (jarDependencies != null && !jarDependencies.isEmpty()) {
            command.add("--jars");
            command.add(jarDependencies);
        }
    }

    private void addCodeTypeSpecificOptions(List<String> command, CodeExecutionContext context) throws Exception {
        CodeType codeType = context.getCodeType();

        if (context.isJarExecutionMode()) {
            handleJarMode(command, context);
        } else {
            switch (codeType) {
                case SQL:
                    handleSqlMode(command, context);
                    break;
                case PYTHON:
                    handlePythonMode(command, context);
                    break;
                case SCALA:
                case JAVA:
                    handleCompiledMode(command, context);
                    break;
                default:
                    handleDefaultMode(command, context);
                    break;
            }
        }
    }

    private void handleJarMode(List<String> command, CodeExecutionContext context) {
        String mainClass = context.getMainClass();
        if (mainClass == null || mainClass.trim().isEmpty()) {
            mainClass = "Main";
        }
        command.add("--class");
        command.add(mainClass);

        String jarPath = context.getUploadedJarPath();
        if (jarPath != null) {
            command.add(jarPath);
        }
    }

    private void handleSqlMode(List<String> command, CodeExecutionContext context) {
        command.add("--conf");
        command.add("spark.sql.codegen.wholeStage=true");

        String codeContent = context.getCodeContent();
        if (codeContent == null || codeContent.trim().isEmpty()) {
            codeContent = "SELECT 1";
        }

        // spark-sql 支持 -e 参数直接执行SQL（spark-submit不支持）
        command.add("-e");
        command.add(codeContent.trim());
    }

    private void handlePythonMode(List<String> command, CodeExecutionContext context) throws Exception {
        Path tempScriptFile = createTempScriptFile(context, "py");
        command.add(tempScriptFile.toAbsolutePath().toString());
    }

    private void handleCompiledMode(List<String> command, CodeExecutionContext context) throws Exception {
        String mainClass = context.getMainClass();
        if (mainClass == null || mainClass.trim().isEmpty()) {
            mainClass = "Main";
        }
        command.add("--class");
        command.add(mainClass);

        Path tempScriptFile = createTempScriptFile(context, context.getCodeType().getExtensions().get(0));
        command.add(tempScriptFile.toAbsolutePath().toString());
    }

    private void handleDefaultMode(List<String> command, CodeExecutionContext context) throws Exception {
        String codeContent = context.getCodeContent();
        if (codeContent != null && !codeContent.trim().isEmpty()) {
            command.add("-e");
            command.add(codeContent.trim());
        }
    }

    private Path createTempScriptFile(CodeExecutionContext context, String suffix) throws Exception {
        Path tempFile = Files.createTempFile("spark_exec_", "." + suffix);
        String content = context.getCodeContent();
        if (content == null) {
            content = "";
        }
        Files.writeString(tempFile, content);
        return tempFile;
    }

    private String extractHost(String url) {
        try {
            java.net.URL u = new java.net.URL(url.replace("jdbc:", "http:"));
            return u.getHost();
        } catch (Exception e) {
            return url;
        }
    }

    private String extractPort(String url, String defaultPort) {
        try {
            java.net.URL u = new java.net.URL(url.replace("jdbc:", "http:"));
            int port = u.getPort();
            return port > 0 ? String.valueOf(port) : defaultPort;
        } catch (Exception e) {
            return defaultPort;
        }
    }

    // ==================== 错误信息提取 ====================

    /**
     * 从Spark进程原始输出中提取核心错误信息。
     * <p>
     * Spark输出通常包含大量classpath、INFO日志等噪音，真正的错误信息隐藏在其中。
     * 提取策略（按优先级）：
     * <ol>
     *   <li>Exception in thread / Error: 行 + 后续堆栈摘要</li>
     *   <li>Caused by: 行</li>
     *   <li>org.apache.spark.*Exception / java.lang.*Exception 的第一行</li>
     *   <li>包含 ERROR / WARN 关键字的行</li>
     *   <li>最后 N 行（Spark常在末尾输出错误摘要）</li>
     * </ol>
     */
    private String extractCoreError(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return "Spark执行失败（无输出信息）";
        }

        String[] lines = rawOutput.split("\n");

        // 1. 查找 "Exception in thread" 或 "Error:" 开头的行
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("Exception in thread") || line.startsWith("Error:")) {
                return buildErrorContext(lines, i, "异常");
            }
        }

        // 2. 查找 Caused by:
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("Caused by:")) {
                return buildErrorContext(lines, i, "根因");
            }
        }

        // 3. 查找 Spark/Java 异常类
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if ((line.contains("org.apache.spark.") || line.contains("java.lang."))
                    && (line.contains("Exception:") || line.contains("Error:"))) {
                return buildErrorContext(lines, i, "异常");
            }
        }

        // 4. 查找 ERROR 级别日志行
        List<String> errorLines = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.contains(" ERROR ") || trimmed.contains("FATAL")) {
                // 去掉时间戳前缀
                String clean = removeLogPrefix(trimmed);
                if (!clean.isEmpty()) {
                    errorLines.add(clean);
                }
            }
        }
        if (!errorLines.isEmpty()) {
            return String.join("\n", errorLines);
        }

        // 5. 兜底：取最后15行（Spark错误常在末尾）
        int start = Math.max(0, lines.length - 15);
        StringBuilder lastLines = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                lastLines.append(lines[i].trim()).append("\n");
            }
        }
        String result = lastLines.toString().trim();
        return result.isEmpty() ? rawOutput : result;
    }

    /**
     * 从匹配行开始构建错误上下文（包含后续相关行）
     */
    private String buildErrorContext(String[] allLines, int startIndex, String label) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Spark ").append(label).append("] ").append(allLines[startIndex].trim()).append("\n");

        // 收集后续最多10行相关上下文（堆栈或详情）
        int contextLines = 0;
        for (int i = startIndex + 1; i < allLines.length && contextLines < 10; i++) {
            String line = allLines[i].trim();
            // 遇到空行后又是非缩进行时停止（新的日志条目开始）
            if (line.isEmpty()) continue;
            // 堆栈行以 \t 或 "at " 开头
            if (line.startsWith("\t") || line.startsWith("at ")
                    || line.startsWith("Caused by:")
                    || line.startsWith("... ")) {
                sb.append(line).append("\n");
                contextLines++;
            } else if (!line.startsWith("[") && contextLines > 0) {
                // 非堆栈非日志前缀的行，可能是额外错误信息
                sb.append(line).append("\n");
                contextLines++;
            } else if (contextLines == 0 && !line.startsWith("[")
                    && !line.matches("^\\d{4}-\\d{2}-\\d{2}.*")) {
                // 第一行上下文：非时间戳非日志格式的行
                sb.append(line).append("\n");
                contextLines++;
            }
        }

        return sb.toString().trim();
    }

    /**
     * 移除常见日志前缀（时间戳 + 日志级别），保留核心消息
     */
    private String removeLogPrefix(String logLine) {
        // 匹配 "2024-01-01 12:00:00 [thread] LEVEL ..." 格式
        String cleaned = logLine.replaceFirst("^\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}[^ ]*\\s+", "");
        cleaned = cleaned.replaceFirst("^\\[[^]]+\\]\\s*", "");  // [thread-name]
        cleaned = cleaned.replaceFirst("^(ERROR|WARN|INFO|DEBUG|FATAL)\\s+", "");
        cleaned = cleaned.replaceFirst("^---+\s*", "");           // 分隔线
        return cleaned.trim();
    }

    private int countLines(String text) {
        if (text == null || text.isEmpty()) return 0;
        return text.split("\n").length;
    }

    private String truncateForLog(String text, int maxLen) {
        if (text == null) return "(null)";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...(截断,总长度=" + text.length() + ")";
    }

    @Override
    public EngineType getEngineType() {
        return EngineType.SPARK;
    }

    @Override
    public boolean supports(CodeType codeType) {
        return getEngineType().supportsCodeType(codeType);
    }
}
