package com.bigdata.modules.development.service.execution;

import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.devops.enums.StorageEngineType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class FlinkExecutionStrategy implements ExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(FlinkExecutionStrategy.class);

    private static final String FLINK_RUN_CMD = "flink";

    @Override
    public CodeExecutionResult execute(CodeExecutionContext context) {
        String executionId = UUID.randomUUID().toString().replace("-", "");
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
            log.error("❌ Flink执行失败", e);
            long duration = System.currentTimeMillis() - startTime;
            CodeExecutionResult result = CodeExecutionResult.failure(executionId, "Flink执行异常: " + e.getMessage());
            result.setStartTime(new java.util.Date(startTime));
            result.setEndTime(new java.util.Date());
            result.setDurationMs((int) duration);
            return result;
        }
    }

    private CodeExecutionResult executeFlinkJob(CodeExecutionContext context, String executionId) throws Exception {
        Path tempFile = createTempScriptFile(context);
        List<String> command = buildFlinkRunCommand(context, tempFile);

        log.info("⚡ Flink提交命令: {}", String.join(" ", command));
        log.info("   - 执行ID: {}", executionId);
        log.info("   - 引擎类型: {}", context.getEngineType());
        log.info("   - 代码类型: {}", context.getCodeType());
        log.info("   - 数据源数量: {}", context.getDatasourceConfigs().size());
        log.info("   - 运行参数: {}", context.getRunParams());
        log.info("   - 计算资源: {}", context.getComputeResources());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                log.debug("Flink输出: {}", line);
            }
        }

        int exitCode = process.waitFor();
        Files.deleteIfExists(tempFile);

        if (exitCode == 0) {
            String jobId = extractJobId(output.toString());
            CodeExecutionResult result = CodeExecutionResult.submitted(
                    jobId != null ? jobId : executionId, output.toString());
            return result;
        } else {
            return CodeExecutionResult.failure(executionId, output.toString());
        }
    }

    private CodeExecutionResult executeFlinkSql(CodeExecutionContext context, String executionId) throws Exception {
        Path tempSqlFile = Files.createTempFile("flink_sql_", ".sql");
        String sqlContent = buildFlinkSqlWithConfiguration(context);
        Files.writeString(tempSqlFile, sqlContent);

        List<String> command = buildFlinkSqlCommand(context, tempSqlFile);

        log.info("⚡ Flink SQL提交命令: {}", String.join(" ", command));
        log.info("   - 执行ID: {}", executionId);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                log.debug("Flink SQL输出: {}", line);
            }
        }

        int exitCode = process.waitFor();
        Files.deleteIfExists(tempSqlFile);

        if (exitCode == 0) {
            return CodeExecutionResult.success(executionId, output.toString());
        } else {
            return CodeExecutionResult.failure(executionId, output.toString());
        }
    }

    private Path createTempScriptFile(CodeExecutionContext context) throws Exception {
        String suffix = "." + context.getCodeType().getExtensions().get(0);
        Path tempFile = Files.createTempFile("flink_exec_", suffix);
        Files.writeString(tempFile, context.getCodeContent());
        return tempFile;
    }

    private List<String> buildFlinkRunCommand(CodeExecutionContext context, Path scriptFile) {
        List<String> command = new ArrayList<>();
        command.add(FLINK_RUN_CMD);
        command.add("run");

        addComputeResources(command, context);
        addAllDatasourceConfs(command, context);
        addJarDependencies(command, context);
        addInputParamsAsArguments(command, context);
        addCodeTypeSpecificOptions(command, context, scriptFile);

        return command;
    }

    private List<String> buildFlinkSqlCommand(CodeExecutionContext context, Path sqlFile) {
        List<String> command = new ArrayList<>();
        command.add(FLINK_RUN_CMD);
        command.add("sql-client");

        addComputeResourcesForSqlClient(command, context);
        addAllDatasourceConfs(command, context);

        command.add("-f");
        command.add(sqlFile.toAbsolutePath().toString());

        return command;
    }

    private void addComputeResources(List<String> command, CodeExecutionContext context) {
        Map<String, Object> resources = context.getComputeResources();
        if (resources == null || resources.isEmpty()) {
            log.debug("未配置计算资源，使用默认值");
            return;
        }

        log.info("⚙️ 添加Flink计算资源配置:");

        Object parallelism = resources.get("parallelism");
        if (parallelism != null) {
            command.add("-p");
            command.add(parallelism.toString());
            log.info("   -p {} (并行度)", parallelism);
        }

        Object jobManagerMemory = resources.get("jobmanager-memory");
        if (jobManagerMemory != null) {
            command.add("-yjm");
            command.add(jobManagerMemory.toString());
            log.info("   -yjm {} (JobManager内存)", jobManagerMemory);
        }

        Object taskManagerMemory = resources.get("taskmanager-memory");
        if (taskManagerMemory != null) {
            command.add("-ytm");
            command.add(taskManagerMemory.toString());
            log.info("   -ytm {} (TaskManager内存)", taskManagerMemory);
        }

        Object taskSlots = resources.get("task-slots");
        if (taskSlots != null) {
            command.add("-ys");
            command.add(taskSlots.toString());
            log.info("   -ys {} (TaskSlots)", taskSlots);
        }

        for (Map.Entry<String, Object> entry : resources.entrySet()) {
            String key = entry.getKey();
            if (!isStandardResourceKey(key)) {
                Object value = entry.getValue();
                command.add("--conf");
                command.add("flink." + key + "=" + value);
                log.info("   --conf flink.{}={}", key, value);
            }
        }
    }

    private void addComputeResourcesForSqlClient(List<String> command, CodeExecutionContext context) {
        Map<String, Object> resources = context.getComputeResources();
        if (resources == null || resources.isEmpty()) {
            return;
        }

        Object parallelism = resources.get("parallelism");
        if (parallelism != null) {
            command.add("--conf");
            command.add("parallelism.default=" + parallelism.toString());
        }
    }

    private boolean isStandardResourceKey(String key) {
        return List.of("parallelism", "jobmanager-memory", "taskmanager-memory",
                       "task-slots").contains(key);
    }

    private void addAllDatasourceConfs(List<String> command, CodeExecutionContext context) {
        List<DatasourceConfig> datasourceConfigs = context.getDatasourceConfigs();
        if (datasourceConfigs == null || datasourceConfigs.isEmpty()) {
            log.warn("⚠️ 未配置数据源");
            return;
        }

        log.info("📊 添加数据源配置 (共{}个):", datasourceConfigs.size());

        for (DatasourceConfig dsConfig : datasourceConfigs) {
            String dsName = dsConfig.getDatasourceName();
            DatasourceType dsType = dsConfig.getDatasourceType();
            StorageEngineType storageEngine = dsConfig.getStorageEngine();

            log.info("   处理数据源 [名称={}, 类型={}, 存储引擎={}]", dsName, dsType, storageEngine);

            String confPrefix = "flink." + dsName;

            command.add("--conf");
            command.add(confPrefix + ".type=" + (dsType != null ? dsType.getCode() : "unknown"));
            log.info("   --conf {}.type={}", confPrefix, dsType);

            if (dsConfig.getUrl() != null) {
                command.add("--conf");
                command.add(confPrefix + ".url=" + dsConfig.getUrl());
                log.info("   --conf {}.url={}", confPrefix, dsConfig.getUrl());
            }

            if (dsConfig.getUsername() != null) {
                command.add("--conf");
                command.add(confPrefix + ".user=" + dsConfig.getUsername());
                log.info("   --conf {}.user=***", confPrefix);
            }

            if (dsConfig.getPassword() != null) {
                command.add("--conf");
                command.add(confPrefix + ".password=" + dsConfig.getPassword());
                log.info("   --conf {}.password=***", confPrefix);
            }

            if (dsConfig.getDatabaseName() != null) {
                command.add("--conf");
                command.add(confPrefix + ".database=" + dsConfig.getDatabaseName());
                log.info("   --conf {}.database={}", confPrefix, dsConfig.getDatabaseName());
            }
            addDatasourceSpecificConf(command, confPrefix, dsConfig, storageEngine);
        }
    }

    private void addDatasourceSpecificConf(List<String> command, String confPrefix,
                                            DatasourceConfig dsConfig, StorageEngineType storageEngine) {
        DatasourceType dsType = dsConfig.getDatasourceType();

        switch (dsType) {
            case MYSQL:
                addMysqlSpecificConf(command, confPrefix, dsConfig, storageEngine);
                break;
            case DORIS:
                addDorisSpecificConf(command, confPrefix, dsConfig, storageEngine);
                break;
            case HIVE:
                addHiveSpecificConf(command, confPrefix, dsConfig, storageEngine);
                break;
            case CLICKHOUSE:
                addClickHouseSpecificConf(command, confPrefix, dsConfig, storageEngine);
                break;
            default:
                addGenericJdbcSpecificConf(command, confPrefix, dsConfig);
                break;
        }
    }

    private void addMysqlSpecificConf(List<String> command, String confPrefix,
                                       DatasourceConfig dsConfig, StorageEngineType storageEngine) {
        command.add("--conf");
        command.add(confPrefix + ".connector=jdbc");
        command.add("--conf");
        command.add(confPrefix + ".jdbc.driver=com.mysql.cj.jdbc.Driver");

        if (storageEngine == StorageEngineType.MYSQL) {
            command.add("--conf");
            command.add(confPrefix + ".cdc.enable=true");
            command.add("--conf");
            command.add(confPrefix + ".scan_startup_mode=initial-offset");
        }
    }

    private void addDorisSpecificConf(List<String> command, String confPrefix,
                                       DatasourceConfig dsConfig, StorageEngineType storageEngine) {
        command.add("--conf");
        command.add(confPrefix + ".fenier.host=" + extractHost(dsConfig.getUrl()));
        command.add("--conf");
        command.add(confPrefix + ".fenier.query.port=" + extractPort(dsConfig.getUrl(), "9030"));
        command.add("--conf");
        command.add(confPrefix + ".catalog.type=doris");

        if (storageEngine == StorageEngineType.STARROCKS) {
            command.add("--conf");
            command.add(confPrefix + ".starrocks.fenier.host=" + extractHost(dsConfig.getUrl()));
            command.add("--conf");
            command.add(confPrefix + ".starrocks.jdbc.url=" + dsConfig.getUrl());
        }
    }

    private void addHiveSpecificConf(List<String> command, String confPrefix,
                                      DatasourceConfig dsConfig, StorageEngineType storageEngine) {
        command.add("--conf");
        command.add(confPrefix + ".hive.conf.dir=/etc/hive/conf");
        command.add("--conf");
        command.add(confPrefix + ".hive.version=3.1.3");

        if (storageEngine == StorageEngineType.HUDI) {
            command.add("--conf");
            command.add(confPrefix + ".hudi.catalog.type=hive");
            command.add("--conf");
            command.add(confPrefix + ".hudi.path=hdfs://namenode:8020/warehouse/hudi");
        } else if (storageEngine == StorageEngineType.ICEBERG) {
            command.add("--conf");
            command.add(confPrefix + ".iceberg.catalog.type=hive");
            command.add("--conf");
            command.add(confPrefix + ".iceberg.warehouse=hdfs://namenode:8020/warehouse/iceberg");
        }
    }

    private void addClickHouseSpecificConf(List<String> command, String confPrefix,
                                            DatasourceConfig dsConfig, StorageEngineType storageEngine) {
        command.add("--conf");
        command.add(confPrefix + ".connection.timeout=30000");
        command.add("--conf");
        command.add(confPrefix + ".socket.timeout=300000");
        command.add("--conf");
        command.add(confPrefix + ".batch.size=10000");
    }

    private void addGenericJdbcSpecificConf(List<String> command, String confPrefix,
                                             DatasourceConfig dsConfig) {
        if (dsConfig.getExtraParams() != null && !dsConfig.getExtraParams().isEmpty()) {
            command.add("--conf");
            command.add(confPrefix + ".extra.params=" + dsConfig.getExtraParams());
        }
    }

    private void addInputParamsAsArguments(List<String> command, CodeExecutionContext context) {
        Map<String, Object> runParams = context.getRunParams();
        if (runParams == null || runParams.isEmpty()) {
            log.debug("未配置程序入参");
            return;
        }

        log.info("📝 添加程序入参 (共{}个):", runParams.size());

        for (Map.Entry<String, Object> entry : runParams.entrySet()) {
            String paramName = entry.getKey();
            Object paramValue = entry.getValue();

            if (paramValue instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> paramDetail = (Map<String, Object>) paramValue;
                Object value = paramDetail.get("value");

                command.add("--" + paramName);
                command.add(value != null ? value.toString() : "");
                log.info("   --{}={} (from runParams)", paramName, value);
            } else {
                command.add("--" + paramName);
                command.add(paramValue.toString());
                log.info("   --{}={} (from runParams)", paramName, paramValue);
            }
        }
    }

    private void addJarDependencies(List<String> command, CodeExecutionContext context) {
        String jarDependencies = context.getJarDependencies();
        if (jarDependencies != null && !jarDependencies.isEmpty()) {
            command.add("-C");
            command.add(jarDependencies);
            log.info("📦 添加依赖包: {}", jarDependencies);
        }
    }

    private void addCodeTypeSpecificOptions(List<String> command, CodeExecutionContext context,
                                              Path scriptFile) {
        switch (context.getCodeType()) {
            case JAVA:
                command.add("-c");
                command.add("Main");
                command.add(scriptFile.toAbsolutePath().toString());
                log.info("☕ Java模式: -c Main");
                break;
            case SCALA:
                command.add("-c");
                command.add("Main");
                command.add(scriptFile.toAbsolutePath().toString());
                log.info("📄 Scala模式: -c Main");
                break;
            case PYTHON:
                command.add("-py");
                command.add(scriptFile.toAbsolutePath().toString());
                log.info("🐍 Python模式: -py");
                break;
            default:
                command.add(scriptFile.toAbsolutePath().toString());
                log.info("📄 默认代码类型: {}", context.getCodeType());
                break;
        }
    }

    private String buildFlinkSqlWithConfiguration(CodeExecutionContext context) {
        StringBuilder sql = new StringBuilder();

        sql.append("-- ============================================\n");
        sql.append("-- Flink SQL Execution Configuration\n");
        sql.append("-- Generated by Big Data Platform\n");
        sql.append("-- ============================================\n\n");

        List<DatasourceConfig> datasourceConfigs = context.getDatasourceConfigs();
        if (datasourceConfigs != null && !datasourceConfigs.isEmpty()) {
            sql.append("-- Datasource Configurations\n");
            for (DatasourceConfig dsConfig : datasourceConfigs) {
                String dsName = dsConfig.getDatasourceName();
                DatasourceType dsType = dsConfig.getDatasourceType();

                sql.append("-- SET '").append(dsName).append(".type' = '")
                   .append(dsType != null ? dsType.getCode() : "unknown").append("';\n");

                if (dsConfig.getUrl() != null) {
                    sql.append("-- SET '").append(dsName).append(".url' = '")
                       .append(dsConfig.getUrl()).append("';\n");
                }
                if (dsConfig.getUsername() != null) {
                    sql.append("-- SET '").append(dsName).append(".user' = '")
                       .append(dsConfig.getUsername()).append("';\n");
                }
                if (dsConfig.getDatabaseName() != null) {
                    sql.append("-- SET '").append(dsName).append(".database' = '")
                       .append(dsConfig.getDatabaseName()).append("';\n");
                }
                sql.append("\n");
            }
        }

        Map<String, Object> runParams = context.getRunParams();
        if (runParams != null && !runParams.isEmpty()) {
            sql.append("-- Input Parameters\n");
            for (Map.Entry<String, Object> entry : runParams.entrySet()) {
                String paramName = entry.getKey();
                Object paramValue = entry.getValue();

                if (paramValue instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> paramDetail = (Map<String, Object>) paramValue;
                    Object value = paramDetail.get("value");
                    sql.append("-- SET 'app.params.").append(paramName).append("' = '")
                       .append(value != null ? value.toString() : "").append("';\n");
                } else {
                    sql.append("-- SET 'app.params.").append(paramName).append("' = '")
                       .append(paramValue.toString()).append("';\n");
                }
            }
            sql.append("\n");
        }

        Map<String, Object> computeResources = context.getComputeResources();
        if (computeResources != null && !computeResources.isEmpty()) {
            sql.append("-- Compute Resources\n");
            Object parallelism = computeResources.get("parallelism");
            if (parallelism != null) {
                sql.append("SET 'parallelism.default' = '").append(parallelism.toString()).append("';\n");
            }
            sql.append("\n");
        }

        sql.append("-- ============================================\n");
        sql.append("-- User SQL Script\n");
        sql.append("-- ============================================\n");
        sql.append(context.getCodeContent());

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
    public EngineType getEngineType() {
        return EngineType.FLINK;
    }

    @Override
    public boolean supports(CodeType codeType) {
        return getEngineType().supportsCodeType(codeType);
    }
}
