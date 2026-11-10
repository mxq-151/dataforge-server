package com.bigdata.modules.integration.service.submit;

import com.bigdata.modules.integration.enums.ClusterType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据集成集群提交适配器。
 *
 * <p>每种集群实现自己的作业生成、提交、取消与状态查询方式，
 * 上层的任务生命周期（状态流转、落库、提交记录）与具体集群无关。
 * 新增集群类型只需实现本接口并注册为 Spring Bean，工厂按 {@link #clusterType()} 路由。</p>
 */
public interface DataIntegrationSubmitter {

    ClusterType clusterType();

    /** 提交任务到集群，成功返回 jobId；失败抛出 BusinessException（含集群原始错误） */
    SubmitResult submit(SubmitContext context);

    /** 取消 / 停止集群上的作业 */
    SubmitResult cancel(SubmitContext context);

    /** 查询集群侧运行状态；集群不可达时返回 reachable=false 而不是抛异常 */
    ClusterJobStatus queryStatus(SubmitContext context);

    /**
     * 一次提交所需的全部上下文（任务定义 + 一份源连接 + 一份目标连接 + 表清单 + 动态参数）。
     */
    class SubmitContext {
        private final Long taskId;
        private final String taskName;
        private final String taskCode;
        /** 所属项目编码：数据血缘的作业 namespace，提交时必须非空 */
        private final String projectCode;
        private final String jobMode;
        private final String sourceType;
        private final Map<String, Object> sourceConfig;
        private final String targetType;
        private final Map<String, Object> targetConfig;
        private final List<TableSpec> tables;
        private final Map<String, Object> extraParams;
        private final Integer readParallelism;
        private final Integer writeParallelism;
        /** 取消 / 查状态时使用：任务已落库的集群作业 ID */
        private String jobId;
        /** 本次生成的作业配置（Flink CDC SQL），失败时也用于落日志 */
        private String generatedConfig;

        public SubmitContext(Long taskId, String taskName, String taskCode, String projectCode, String jobMode,
                             String sourceType, Map<String, Object> sourceConfig,
                             String targetType, Map<String, Object> targetConfig,
                             List<TableSpec> tables, Map<String, Object> extraParams,
                             Integer readParallelism, Integer writeParallelism) {
            this.taskId = taskId;
            this.taskName = taskName;
            this.taskCode = taskCode;
            this.projectCode = projectCode;
            this.jobMode = jobMode;
            this.sourceType = sourceType;
            this.sourceConfig = sourceConfig == null ? new LinkedHashMap<>() : sourceConfig;
            this.targetType = targetType;
            this.targetConfig = targetConfig == null ? new LinkedHashMap<>() : targetConfig;
            this.tables = tables == null ? List.of() : tables;
            this.extraParams = extraParams == null ? new LinkedHashMap<>() : extraParams;
            this.readParallelism = readParallelism;
            this.writeParallelism = writeParallelism;
        }

        public Long getTaskId() {
            return taskId;
        }

        public String getTaskName() {
            return taskName;
        }

        public String getTaskCode() {
            return taskCode;
        }

        public String getProjectCode() {
            return projectCode;
        }

        public String getJobMode() {
            return jobMode;
        }

        public String getSourceType() {
            return sourceType;
        }

        public Map<String, Object> getSourceConfig() {
            return sourceConfig;
        }

        public String getTargetType() {
            return targetType;
        }

        public Map<String, Object> getTargetConfig() {
            return targetConfig;
        }

        public List<TableSpec> getTables() {
            return tables;
        }

        public Map<String, Object> getExtraParams() {
            return extraParams;
        }

        public Integer getReadParallelism() {
            return readParallelism;
        }

        public Integer getWriteParallelism() {
            return writeParallelism;
        }

        public boolean isStreamMode() {
            return "STREAM".equalsIgnoreCase(jobMode);
        }

        public String getJobId() {
            return jobId;
        }

        public void setJobId(String jobId) {
            this.jobId = jobId;
        }

        public String getGeneratedConfig() {
            return generatedConfig;
        }

        public void setGeneratedConfig(String generatedConfig) {
            this.generatedConfig = generatedConfig;
        }
    }
}
