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
public class SparkExecutionStrategy implements ExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(SparkExecutionStrategy.class);

    private static final String SPARK_SUBMIT_CMD = "spark-submit";

    @Override
    public CodeExecutionResult execute(CodeExecutionContext context) {
        String executionId = UUID.randomUUID().toString().replace("-", "");
        long startTime = System.currentTimeMillis();

        try {
            Path tempScriptFile = createTempScriptFile(context);
            List<String> command = buildSparkSubmitCommand(context, tempScriptFile);

            log.info("🔥 Spark提交命令: {}", String.join(" ", command));
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
                    log.debug("Spark输出: {}", line);
                }
            }

            int exitCode = process.waitFor();
            long duration = System.currentTimeMillis() - startTime;

            Files.deleteIfExists(tempScriptFile);

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
            log.error("❌ Spark执行失败", e);
            long duration = System.currentTimeMillis() - startTime;
            CodeExecutionResult result = CodeExecutionResult.failure(executionId, e.getMessage());
            result.setStartTime(new java.util.Date(startTime));
            result.setEndTime(new java.util.Date());
            result.setDurationMs((int) duration);
            return result;
        }
    }

    private Path createTempScriptFile(CodeExecutionContext context) throws Exception {
        String suffix = "." + context.getCodeType().getExtensions().get(0);
        Path tempFile = Files.createTempFile("spark_exec_", suffix);
        Files.writeString(tempFile, context.getCodeContent());
        return tempFile;
    }

    private List<String> buildSparkSubmitCommand(CodeExecutionContext context, Path scriptFile) {
        List<String> command = new ArrayList<>();
        command.add(SPARK_SUBMIT_CMD);

        addComputeResources(command, context);
        addAllDatasourceConfs(command, context);
        addJarDependencies(command, context);
        addInputParams(command, context);
        addCodeTypeSpecificOptions(command, context);

        command.add(scriptFile.toAbsolutePath().toString());
        return command;
    }

    private void addComputeResources(List<String> command, CodeExecutionContext context) {
        Map<String, Object> resources = context.getComputeResources();
        if (resources == null || resources.isEmpty()) {
            log.debug("未配置计算资源，使用默认值");
            return;
        }

        log.info("⚙️ 添加计算资源配置:");

        Object driverMemory = resources.get("driver-memory");
        if (driverMemory != null) {
            command.add("--driver-memory");
            command.add(driverMemory.toString());
            log.info("   --driver-memory {}", driverMemory);
        }

        Object executorMemory = resources.get("executor-memory");
        if (executorMemory != null) {
            command.add("--executor-memory");
            command.add(executorMemory.toString());
            log.info("   --executor-memory {}", executorMemory);
        }

        Object executorCores = resources.get("executor-cores");
        if (executorCores != null) {
            command.add("--executor-cores");
            command.add(executorCores.toString());
            log.info("   --executor-cores {}", executorCores);
        }

        Object numExecutors = resources.get("num-executors");
        if (numExecutors != null) {
            command.add("--num-executors");
            command.add(numExecutors.toString());
            log.info("   --num-executors {}", numExecutors);
        }

        Object master = resources.get("master");
        if (master != null) {
            command.add("--master");
            command.add(master.toString());
            log.info("   --master {}", master);
        }

        Object deployMode = resources.get("deploy-mode");
        if (deployMode != null) {
            command.add("--deploy-mode");
            command.add(deployMode.toString());
            log.info("   --deploy-mode {}", deployMode);
        }

        for (Map.Entry<String, Object> entry : resources.entrySet()) {
            String key = entry.getKey();
            if (!isStandardResourceKey(key)) {
                Object value = entry.getValue();
                command.add("--conf");
                command.add("spark." + key + "=" + value);
                log.info("   --conf spark.{}={}", key, value);
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
            log.warn("⚠️ 未配置数据源");
            return;
        }

        log.info("📊 添加数据源配置 (共{}个):", datasourceConfigs.size());

        for (DatasourceConfig dsConfig : datasourceConfigs) {
            String dsName = dsConfig.getDatasourceName();
            DatasourceType dsType = dsConfig.getDatasourceType();
            StorageEngineType storageEngine = dsConfig.getStorageEngine();

            log.info("   处理数据源 [名称={}, 类型={}, 存储引擎={}]", dsName, dsType, storageEngine);

            String confPrefix = "spark." + dsName;

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
            case HIVE:
                addHiveSpecificConf(command, confPrefix, dsConfig, storageEngine);
                break;
            case MYSQL:
                addMysqlSpecificConf(command, confPrefix, dsConfig, storageEngine);
                break;
            case DORIS:
                addDorisSpecificConf(command, confPrefix, dsConfig, storageEngine);
                break;
            case CLICKHOUSE:
                addClickHouseSpecificConf(command, confPrefix, dsConfig, storageEngine);
                break;
            default:
                addGenericJdbcSpecificConf(command, confPrefix, dsConfig);
                break;
        }
    }

    private void addHiveSpecificConf(List<String> command, String confPrefix,
                                      DatasourceConfig dsConfig, StorageEngineType storageEngine) {
        command.add("--conf");
        command.add(confPrefix + ".metastore.uris=" + dsConfig.getUrl());
        log.info("   --conf {}.metastore.uris={}", confPrefix, dsConfig.getUrl());

        if (storageEngine == StorageEngineType.HUDI) {
            command.add("--conf");
            command.add(confPrefix + ".sql.extensions=org.apache.spark.sql.hudi.HudiSparkSessionExtension");
            command.add("--conf");
            command.add(confPrefix + ".serializer=org.apache.spark.serializer.KryoSerializer");
        } else if (storageEngine == StorageEngineType.ICEBERG) {
            command.add("--conf");
            command.add(confPrefix + ".sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions");
            command.add("--conf");
            command.add(confPrefix + ".sql.catalog.type=hive");
        }
    }

    private void addMysqlSpecificConf(List<String> command, String confPrefix,
                                       DatasourceConfig dsConfig, StorageEngineType storageEngine) {
        command.add("--conf");
        command.add(confPrefix + ".jdbc.driver=com.mysql.cj.jdbc.Driver");
        log.info("   --conf {}.jdbc.driver=com.mysql.cj.jdbc.Driver", confPrefix);

        if (storageEngine == StorageEngineType.MYSQL) {
            command.add("--conf");
            command.add(confPrefix + ".connector=mysql-cdc");
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

    private void addClickHouseSpecificConf(List<String> command, String confPrefix,
                                            DatasourceConfig dsConfig, StorageEngineType storageEngine) {
        command.add("--conf");
        command.add(confPrefix + ".write.batch.size=10000");
        command.add("--conf");
        command.add(confPrefix + ".connection.timeout=30000");
    }

    private void addGenericJdbcSpecificConf(List<String> command, String confPrefix,
                                             DatasourceConfig dsConfig) {
        if (dsConfig.getExtraParams() != null && !dsConfig.getExtraParams().isEmpty()) {
            command.add("--conf");
            command.add(confPrefix + ".extra.params=" + dsConfig.getExtraParams());
        }
    }

    private void addInputParams(List<String> command, CodeExecutionContext context) {
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
                String type = (String) paramDetail.getOrDefault("type", "string");
                Object value = paramDetail.get("value");

                command.add("--conf");
                command.add("spark.app.params." + paramName + "=" +
                           (value != null ? value.toString() : ""));
                log.info("   --conf spark.app.params.{}={} (type={})", paramName, value, type);
            } else {
                command.add("--conf");
                command.add("spark.app.params." + paramName + "=" + paramValue.toString());
                log.info("   --conf spark.app.params.{}={}", paramName, paramValue);
            }
        }
    }

    private void addJarDependencies(List<String> command, CodeExecutionContext context) {
        String jarDependencies = context.getJarDependencies();
        if (jarDependencies != null && !jarDependencies.isEmpty()) {
            command.add("--jars");
            command.add(jarDependencies);
            log.info("📦 添加依赖包: {}", jarDependencies);
        }
    }

    private void addCodeTypeSpecificOptions(List<String> command, CodeExecutionContext context) {
        switch (context.getCodeType()) {
            case SCALA:
                command.add("--class");
                command.add("Main");
                log.info("📄 Scala模式: --class Main");
                break;
            case PYTHON:
                log.info("🐍 Python模式");
                break;
            case SQL:
                command.add("--conf");
                command.add("spark.sql.codegen.wholeStage=true");
                log.info("💾 SQL模式: 启用全阶段代码生成");
                break;
            case JAVA:
                command.add("--class");
                command.add("Main");
                log.info("☕ Java模式: --class Main");
                break;
            default:
                log.info("📄 默认代码类型: {}", context.getCodeType());
                break;
        }
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
