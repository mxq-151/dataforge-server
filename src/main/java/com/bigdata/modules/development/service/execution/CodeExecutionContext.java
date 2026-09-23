package com.bigdata.modules.development.service.execution;

import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class CodeExecutionContext {

    private String executionId;
    private String codeContent;
    /**
     * 权限改写后的实际下发内容（行级过滤注入 / 列白名单裁剪，见 SqlPermissionRewriter）。
     * 为 null 表示无需改写，此时以 {@link #codeContent} 为准。
     * <p>
     * 与 {@link #codeContent} 分离的目的是：日志、审计与回显仍然展示用户提交的原始代码，
     * 只有真正提交给引擎执行的内容被替换。
     * </p>
     */
    private String effectiveCodeContent;
    private CodeType codeType;
    private DatasourceType engineType;
    private List<DatasourceConfig> datasourceConfigs;
    private String inputParams;
    private Long submitBy;
    private String submitByName;
    private List<String> jarDependencies;
    private Map<String, Object> runParams;
    private Map<String, Object> computeResources;
    private String uploadedJarPath;
    private String mainClass;
    private String fileName;


    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public String getCodeContent() {
        return codeContent;
    }

    public void setCodeContent(String codeContent) {
        this.codeContent = codeContent;
    }

    public String getEffectiveCodeContent() {
        return effectiveCodeContent;
    }

    public void setEffectiveCodeContent(String effectiveCodeContent) {
        this.effectiveCodeContent = effectiveCodeContent;
    }

    /**
     * 真正提交给引擎执行的内容：优先使用权限改写后的内容，未改写时回退到用户原始代码。
     */
    public String getExecutionCodeContent() {
        return effectiveCodeContent != null ? effectiveCodeContent : codeContent;
    }

    /** 是否发生了权限改写。 */
    public boolean isCodeRewritten() {
        return effectiveCodeContent != null;
    }

    public CodeType getCodeType() {
        return codeType;
    }

    public void setCodeType(CodeType codeType) {
        this.codeType = codeType;
    }

    public DatasourceType getEngineType() {
        return engineType;
    }

    public void setEngineType(DatasourceType engineType) {
        this.engineType = engineType;
    }

    public List<DatasourceConfig> getDatasourceConfigs() {
        return datasourceConfigs != null ? datasourceConfigs : Collections.emptyList();
    }

    public void setDatasourceConfigs(List<DatasourceConfig> datasourceConfigs) {
        this.datasourceConfigs = datasourceConfigs;
    }

    public DatasourceConfig getPrimaryDatasourceConfig() {
        if (datasourceConfigs != null && !datasourceConfigs.isEmpty()) {
            return datasourceConfigs.get(0);
        }
        return null;
    }

    public String getInputParams() {
        return inputParams;
    }

    public void setInputParams(String inputParams) {
        this.inputParams = inputParams;
    }

    public Long getSubmitBy() {
        return submitBy;
    }

    public void setSubmitBy(Long submitBy) {
        this.submitBy = submitBy;
    }

    public String getSubmitByName() {
        return submitByName;
    }

    public void setSubmitByName(String submitByName) {
        this.submitByName = submitByName;
    }

    public List<String> getJarDependencies() {
        return jarDependencies;
    }

    public void setJarDependencies(List<String> jarDependencies) {
        this.jarDependencies = jarDependencies;
    }

    public Map<String, Object> getRunParams() {
        return runParams;
    }

    public void setRunParams(Map<String, Object> runParams) {
        this.runParams = runParams;
    }

    public Map<String, Object> getComputeResources() {
        return computeResources;
    }

    public void setComputeResources(Map<String, Object> computeResources) {
        this.computeResources = computeResources;
    }

    public String getUploadedJarPath() {
        return uploadedJarPath;
    }

    public void setUploadedJarPath(String uploadedJarPath) {
        this.uploadedJarPath = uploadedJarPath;
    }

    public String getMainClass() {
        return mainClass;
    }

    public void setMainClass(String mainClass) {
        this.mainClass = mainClass;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public DatasourceType getPrimaryDatasourceType() {
        DatasourceConfig config = getPrimaryDatasourceConfig();
        return config != null ? config.getDatasourceType() : null;
    }

    public String getPrimaryDatasourceUrl() {
        DatasourceConfig config = getPrimaryDatasourceConfig();
        return config != null ? config.getUrl() : null;
    }

    public String getPrimaryDatasourceUsername() {
        DatasourceConfig config = getPrimaryDatasourceConfig();
        return config != null ? config.getUsername() : null;
    }

    public String getPrimaryDatasourcePassword() {
        DatasourceConfig config = getPrimaryDatasourceConfig();
        return config != null ? config.getPassword() : null;
    }

    public String getPrimaryDatasourceDatabaseName() {
        DatasourceConfig config = getPrimaryDatasourceConfig();
        if (config != null && config.getDatabaseName() != null && !config.getDatabaseName().isEmpty()) {
            return config.getDatabaseName().get(0);
        }
        return null;
    }

    public DatasourceType getPrimaryStorageEngine() {
        DatasourceConfig config = getPrimaryDatasourceConfig();
        return config != null ? config.getDatasourceType() : null;
    }

    public boolean hasMultipleDatasources() {
        return datasourceConfigs != null && datasourceConfigs.size() > 1;
    }

    public boolean isJarExecutionMode() {
        return uploadedJarPath != null && !uploadedJarPath.isEmpty();
    }
}
