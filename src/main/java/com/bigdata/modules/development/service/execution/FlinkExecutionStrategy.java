package com.bigdata.modules.development.service.execution;

import com.bigdata.common.utils.AesUtils;
import com.bigdata.modules.development.config.EngineProperties;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class FlinkExecutionStrategy extends AbstractBaseExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(FlinkExecutionStrategy.class);

    @Autowired
    private EngineProperties engineProperties;

    @Autowired
    private ProcessManager processManager;

    @Autowired
    private AesUtils aesUtils;

    @Override
    protected Logger getLog() {
        return log;
    }

    @Override
    public CodeExecutionResult execute(CodeExecutionContext context) {
        String executionId = context.getExecutionId();
        long startTime = System.currentTimeMillis();

        try {
            CodeExecutionResult result;
            if (context.getCodeType() == CodeType.SQL) {
                result = executeFlinkSql(context, executionId);
            } else {
                result = executeFlinkJob(context, executionId);
            }

            long duration = System.currentTimeMillis() - startTime;
            result.setStartTime(new java.util.Date(startTime));
            result.setEndTime(new java.util.Date());
            result.setDurationMs((int) duration);
            return result;
        } catch (Exception e) {
            log.error("Flink执行失败", e);
            long duration = System.currentTimeMillis() - startTime;
            CodeExecutionResult result = CodeExecutionResult.failure(executionId, "Flink执行异常: " + e.getMessage());
            result.setStartTime(new java.util.Date(startTime));
            result.setEndTime(new java.util.Date());
            result.setDurationMs((int) duration);
            return result;
        }
    }

    private CodeExecutionResult executeFlinkJob(CodeExecutionContext context, String executionId) throws Exception {
        List<String> command = buildFlinkRunCommand(context);

        // 根据引用的数据源构造环境变量（如 test_datasource.url/.user/.passwd），密码自动解密为明文
        Map<String, String> datasourceEnv = DatasourceEnvHelper.buildEnv(context.getDatasourceConfigs(), aesUtils);

        log.info("Flink提交命令: {}", DatasourceEnvHelper.maskPasswords(
                String.join(" ", command), context.getDatasourceConfigs(), aesUtils));
        log.info("   - 执行ID: {}", executionId);
        log.info("   - 数据源环境变量: {}", DatasourceEnvHelper.buildEnvDescription(datasourceEnv));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        if (!datasourceEnv.isEmpty()) {
            pb.environment().putAll(datasourceEnv);
        }

        if (context.isJarExecutionMode() || context.getCodeType() == CodeType.PYTHON) {
            Process process = pb.start();
            String registeredId = processManager.registerProcess(
                    context.getExecutionId(), process, context.getSubmitBy(), "flink");

            // 推送进程启动日志
            executionLogListener.publishLog(executionId, "[Flink] 进程已启动, PID=" + process.pid());

            StringBuilder output = new StringBuilder();
            CountDownLatch readLatch = new CountDownLatch(1);

            Thread readerThread = new Thread(() -> {
                log.info("📖 开始读取Flink进程输出: executionId={}, pid={}", executionId, process.pid());
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
                    log.error("读取Flink输出异常", e);
                } finally {
                    log.info("📖 Flink进程输出读取完毕: executionId={}, 共{}行, output长度={}",
                            executionId, lineCount, output.length());
                    readLatch.countDown();
                }
            }, "flink-output-reader-" + executionId);
            readerThread.setDaemon(true);
            readerThread.start();

            int exitCode = process.waitFor();
            log.info("⏳ Flink进程已退出: exitCode={}, 等待读线程排空...", exitCode);
            readLatch.await(5, TimeUnit.SECONDS);
            processManager.unregister(registeredId);

            if (exitCode == 0) {
                String jobId = extractJobId(output.toString());
                return CodeExecutionResult.submitted(jobId != null ? jobId : executionId, output.toString());
            } else {
                return CodeExecutionResult.failure(executionId, output.toString());
            }
        } else {
            String codeContent = context.getExecutionCodeContent();
            if (codeContent == null) {
                codeContent = "";
            }
            pb.redirectInput(ProcessBuilder.Redirect.PIPE);
            Process process = pb.start();
            String registeredId = processManager.registerProcess(
                    context.getExecutionId(), process, context.getSubmitBy(), "flink");

            try (var writer = process.getOutputStream()) {
                writer.write(codeContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                writer.flush();
            }

            // 推送进程启动日志
            executionLogListener.publishLog(executionId, "[Flink] stdin模式进程已启动, PID=" + process.pid());

            StringBuilder output = new StringBuilder();
            CountDownLatch readLatch = new CountDownLatch(1);

            Thread readerThread = new Thread(() -> {
                log.info("📖 开始读取Flink stdin模式输出: executionId={}, pid={}", executionId, process.pid());
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
                    log.error("读取Flink stdin模式输出异常", e);
                } finally {
                    log.info("📖 Flink stdin模式输出读取完毕: executionId={}, 共{}行, output长度={}",
                            executionId, lineCount, output.length());
                    readLatch.countDown();
                }
            }, "flink-stdin-reader-" + executionId);
            readerThread.setDaemon(true);
            readerThread.start();

            int exitCode = process.waitFor();
            log.info("⏳ Flink stdin进程已退出: exitCode={}, 等待读线程排空...", exitCode);
            readLatch.await(5, TimeUnit.SECONDS);
            processManager.unregister(registeredId);

            if (exitCode == 0) {
                String jobId = extractJobId(output.toString());
                return CodeExecutionResult.submitted(jobId != null ? jobId : executionId, output.toString());
            } else {
                return CodeExecutionResult.failure(executionId, output.toString());
            }
        }
    }

    private CodeExecutionResult executeFlinkSql(CodeExecutionContext context, String executionId) throws Exception {
        List<String> command = buildFlinkSqlCommand(context);

        // 根据引用的数据源构造环境变量（如 test_datasource.url/.user/.passwd），密码自动解密为明文
        Map<String, String> datasourceEnv = DatasourceEnvHelper.buildEnv(context.getDatasourceConfigs(), aesUtils);

        log.info("Flink SQL提交命令: {}", DatasourceEnvHelper.maskPasswords(
                String.join(" ", command), context.getDatasourceConfigs(), aesUtils));
        log.info("   - 执行ID: {}", executionId);
        log.info("   - 数据源环境变量: {}", DatasourceEnvHelper.buildEnvDescription(datasourceEnv));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        if (!datasourceEnv.isEmpty()) {
            pb.environment().putAll(datasourceEnv);
        }
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        Process process = pb.start();
        String registeredId = processManager.registerProcess(
                context.getExecutionId(), process, context.getSubmitBy(), "flink-sql");

        String sqlContent = buildFlinkSqlWithConfiguration(context);
        try (var writer = process.getOutputStream()) {
            writer.write(sqlContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            writer.flush();
        }

        // 推送进程启动日志
        executionLogListener.publishLog(executionId, "[Flink SQL] 进程已启动, PID=" + process.pid());

        StringBuilder output = new StringBuilder();
            CountDownLatch readLatch2 = new CountDownLatch(1);

            Thread readerThread2 = new Thread(() -> {
                log.info("📖 开始读取Flink SQL进程输出: executionId={}, pid={}", executionId, process.pid());
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
                    log.error("读取Flink SQL输出异常", e);
                } finally {
                    log.info("📖 Flink SQL进程输出读取完毕: executionId={}, 共{}行, output长度={}",
                            executionId, lineCount, output.length());
                    readLatch2.countDown();
                }
            }, "flink-sql-output-reader-" + executionId);
            readerThread2.setDaemon(true);
            readerThread2.start();

            int exitCode = process.waitFor();
            log.info("⏳ Flink SQL进程已退出: exitCode={}, 等待读线程排空...", exitCode);
            readLatch2.await(5, TimeUnit.SECONDS);
            processManager.unregister(registeredId);

        if (exitCode == 0) {
            return CodeExecutionResult.success(executionId, output.toString());
        } else {
            return CodeExecutionResult.failure(executionId, output.toString());
        }
    }

    private List<String> buildFlinkRunCommand(CodeExecutionContext context) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(engineProperties.getFlink().getFlinkCommand());
        command.add("run");

        addComputeResources(command, context);
        addAllDatasourceConfs(command, context);
        addJarDependencies(command, context);
        addInputParamsAsArguments(command, context);
        addCodeTypeSpecificOptionsForJob(command, context);

        return command;
    }

    private List<String> buildFlinkSqlCommand(CodeExecutionContext context) {
        List<String> command = new ArrayList<>();
        command.add(engineProperties.getFlink().getFlinkCommand());
        command.add("sql-client");

        addComputeResourcesForSqlClient(command, context);
        addAllDatasourceConfs(command, context);

        return command;
    }

    private void addComputeResources(List<String> command, CodeExecutionContext context) {
        Map<String, Object> resources = context.getComputeResources();
        if (resources == null) {
            resources = new java.util.HashMap<>();
        }

        // 内存/cpu/实例数 未指定时使用默认值：2G / 1 CORE / 1 实例
        command.add("-ytm");
        command.add(String.valueOf(resources.getOrDefault("taskmanager-memory", "2G")));

        command.add("-ys");
        command.add(String.valueOf(resources.getOrDefault("task-slots", "1")));

        command.add("-p");
        command.add(String.valueOf(resources.getOrDefault("parallelism", "1")));

        Object jobManagerMemory = resources.get("jobmanager-memory");
        if (jobManagerMemory != null) {
            command.add("-yjm");
            command.add(jobManagerMemory.toString());
        }

        for (Map.Entry<String, Object> entry : resources.entrySet()) {
            String key = entry.getKey();
            if (!isStandardResourceKey(key)) {
                Object value = entry.getValue();
                command.add("--conf");
                command.add("flink." + key + "=" + value);
            }
        }
    }

    private void addComputeResourcesForSqlClient(List<String> command, CodeExecutionContext context) {
        Map<String, Object> resources = context.getComputeResources();
        if (resources == null) {
            resources = new java.util.HashMap<>();
        }

        // 未指定并发度时使用默认值：1
        command.add("--conf");
        command.add("parallelism.default=" + resources.getOrDefault("parallelism", "1"));
    }

    private boolean isStandardResourceKey(String key) {
        return List.of("parallelism", "jobmanager-memory", "taskmanager-memory",
                       "task-slots").contains(key);
    }

    private void addAllDatasourceConfs(List<String> command, CodeExecutionContext context) {
        List<DatasourceConfig> datasourceConfigs = context.getDatasourceConfigs();
        if (datasourceConfigs == null || datasourceConfigs.isEmpty()) {
            return;
        }

        for (DatasourceConfig dsConfig : datasourceConfigs) {
            String dsName = dsConfig.getDatasourceName();
            DatasourceType dsType = dsConfig.getDatasourceType();
            DatasourceType storageEngine = dsConfig.getDatasourceType();
            String confPrefix = "flink." + dsName;

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
                command.add(confPrefix + ".password=" + dsConfig.getPlainPassword(aesUtils));
            }

            if (dsConfig.getDatabaseName() != null && !dsConfig.getDatabaseName().isEmpty()) {
                command.add("--conf");
                command.add(confPrefix + ".database=" + dsConfig.getDatabaseName().get(0));
            }

            addDatasourceSpecificConf(command, confPrefix, dsConfig, storageEngine);
        }
    }

    private void addDatasourceSpecificConf(List<String> command, String confPrefix,
                                            DatasourceConfig dsConfig, DatasourceType storageEngine) {
        DatasourceType dsType = dsConfig.getDatasourceType();

        switch (dsType) {
            case MYSQL:
                command.add("--conf");
                command.add(confPrefix + ".connector=jdbc");
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
            case HIVE:
                command.add("--conf");
                command.add(confPrefix + ".hive.conf.dir=/etc/hive/conf");
                command.add("--conf");
                command.add(confPrefix + ".hive.version=3.1.3");
                break;
            case CLICKHOUSE:
                command.add("--conf");
                command.add(confPrefix + ".connection.timeout=30000");
                command.add("--conf");
                command.add(confPrefix + ".socket.timeout=300000");
                command.add("--conf");
                command.add(confPrefix + ".batch.size=10000");
                break;
            default:
                if (dsConfig.getExtraParams() != null && !dsConfig.getExtraParams().isEmpty()) {
                    command.add("--conf");
                    command.add(confPrefix + ".extra.params=" + dsConfig.getExtraParams());
                }
                break;
        }
    }

    private void addInputParamsAsArguments(List<String> command, CodeExecutionContext context) {
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

                command.add("--" + paramName);
                command.add(value != null ? value.toString() : "");
            } else {
                command.add("--" + paramName);
                command.add(paramValue.toString());
            }
        }
    }

    private void addJarDependencies(List<String> command, CodeExecutionContext context) {
        // jarDependencies 为已解析的完整路径 basePath/{jarPath}，直接拼接
        List<String> jarDependencies = context.getJarDependencies();
        if (jarDependencies != null && !jarDependencies.isEmpty()) {
            String joined = String.join(",", jarDependencies);
            command.add("-C");
            command.add(joined);
        }
    }

    private void addCodeTypeSpecificOptionsForJob(List<String> command, CodeExecutionContext context) throws Exception {
        CodeType codeType = context.getCodeType();

        if (context.isJarExecutionMode()) {
            handleJarMode(command, context);
        } else {
            switch (codeType) {
                case PYTHON:
                    handlePythonMode(command, context);
                    break;
                case JAVA:
                    handleJavaInlineMode(command, context);
                    break;
                case SCALA:
                    handleScalaInlineMode(command, context);
                    break;
                default:
                    break;
            }
        }
    }

    private void handleJarMode(List<String> command, CodeExecutionContext context) {
        String mainClass = context.getMainClass();
        if (mainClass == null || mainClass.trim().isEmpty()) {
            mainClass = "Main";
        }
        command.add("-c");
        command.add(mainClass);

        if (context.getFileName() != null && !context.getFileName().trim().isEmpty()) {
            command.add(context.getFileName());
        }

        String jarPath = context.getUploadedJarPath();
        if (jarPath != null) {
            command.add(jarPath);
        }
    }

    private void handlePythonMode(List<String> command, CodeExecutionContext context) throws Exception {
        Path tempFile = createTempScriptFile(context, "py");
        command.add("-py");
        command.add(tempFile.toAbsolutePath().toString());
    }

    private void handleJavaInlineMode(List<String> command, CodeExecutionContext context) {
        String mainClass = context.getMainClass();
        if (mainClass == null || mainClass.trim().isEmpty()) {
            mainClass = "Main";
        }
        command.add("-c");
        command.add(mainClass);
    }

    private void handleScalaInlineMode(List<String> command, CodeExecutionContext context) {
        command.add("-c");
        command.add("Main");
    }

    private Path createTempScriptFile(CodeExecutionContext context, String suffix) throws Exception {
        Path tempFile = Files.createTempFile("flink_exec_", "." + suffix);
        String content = context.getExecutionCodeContent();
        if (content == null) {
            content = "";
        }
        Files.writeString(tempFile, content);
        return tempFile;
    }

    private String buildFlinkSqlWithConfiguration(CodeExecutionContext context) {
        StringBuilder sql = new StringBuilder();

        sql.append("-- Flink SQL Execution Configuration\n\n");

        List<DatasourceConfig> datasourceConfigs = context.getDatasourceConfigs();
        if (datasourceConfigs != null && !datasourceConfigs.isEmpty()) {
            for (DatasourceConfig dsConfig : datasourceConfigs) {
                String dsName = dsConfig.getDatasourceName();
                DatasourceType dsType = dsConfig.getDatasourceType();

                sql.append("SET '").append(dsName).append(".type' = '")
                   .append(dsType != null ? dsType.getCode() : "unknown").append("';\n");

                if (dsConfig.getUrl() != null) {
                    sql.append("SET '").append(dsName).append(".url' = '")
                       .append(dsConfig.getUrl()).append("';\n");
                }
                if (dsConfig.getUsername() != null) {
                    sql.append("SET '").append(dsName).append(".user' = '")
                       .append(dsConfig.getUsername()).append("';\n");
                }
                if (dsConfig.getDatabaseName() != null && !dsConfig.getDatabaseName().isEmpty()) {
                    sql.append("SET '").append(dsName).append(".database' = '")
                       .append(dsConfig.getDatabaseName().get(0)).append("';\n");
                }
            }
            sql.append("\n");
        }

        Map<String, Object> runParams = context.getRunParams();
        if (runParams != null && !runParams.isEmpty()) {
            for (Map.Entry<String, Object> entry : runParams.entrySet()) {
                String paramName = entry.getKey();
                Object paramValue = entry.getValue();

                if (paramValue instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> paramDetail = (Map<String, Object>) paramValue;
                    Object value = paramDetail.get("value");
                    sql.append("SET 'app.params.").append(paramName).append("' = '")
                       .append(value != null ? value.toString() : "").append("';\n");
                } else {
                    sql.append("SET 'app.params.").append(paramName).append("' = '")
                       .append(paramValue.toString()).append("';\n");
                }
            }
            sql.append("\n");
        }

        sql.append(context.getExecutionCodeContent());

        return sql.toString();
    }

    private String extractJobId(String output) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("JobID\\s+(\\w+)");
        java.util.regex.Matcher matcher = pattern.matcher(output);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
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

    @Override
    public DatasourceType getEngineType() {
        return DatasourceType.FLINK;
    }

    @Override
    public boolean supports(CodeType codeType) {
        return getEngineType().supportsLanguage(codeType);
    }
}
