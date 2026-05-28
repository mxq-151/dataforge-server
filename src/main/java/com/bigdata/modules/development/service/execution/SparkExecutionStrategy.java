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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class SparkExecutionStrategy implements ExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(SparkExecutionStrategy.class);

    @Autowired
    private EngineProperties engineProperties;

    @Autowired
    private ProcessManager processManager;

    @Override
    public CodeExecutionResult execute(CodeExecutionContext context) {
        String executionId = UUID.randomUUID().toString().replace("-", "");
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

            String registeredId = processManager.registerProcess(process, context.getSubmitBy(), "spark");

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    log.debug("Spark输出: {}", line);
                }
            }

            int exitCode = process.waitFor();
            long duration = System.currentTimeMillis() - startTime;

            processManager.unregister(registeredId);

            if (exitCode == 0) {
                CodeExecutionResult result = CodeExecutionResult.success(executionId, output.toString());
                result.setStartTime(new java.util.Date(startTime));
                result.setEndTime(new java.util.Date());
                result.setDurationMs((int) duration);
                return result;
            } else {
                CodeExecutionResult result = CodeExecutionResult.failure(executionId, output.toString());
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
        command.add(engineProperties.getSpark().getSparkSubmitCommand());

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
        if (codeContent != null && !codeContent.trim().isEmpty()) {
            command.add("-e");
            command.add(codeContent.trim());
        } else {
            command.add("-e");
            command.add("SELECT 1");
        }
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

    @Override
    public EngineType getEngineType() {
        return EngineType.SPARK;
    }

    @Override
    public boolean supports(CodeType codeType) {
        return getEngineType().supportsCodeType(codeType);
    }
}
