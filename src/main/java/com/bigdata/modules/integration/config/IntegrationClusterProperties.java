package com.bigdata.modules.integration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 数据集成集群连接配置（application.yml 中 integration.* 前缀）。
 *
 * <p>提交方式为<b>命令行</b>：可执行文件、启动参数、工作目录全部配置化，
 * 代码中不出现任何硬编码路径；留空表示「本机未安装」，提交时返回明确错误而不是静默失败。</p>
 *
 * <p>任务统一生成 Flink CDC 作业，只有 Flink 一种运行环境。
 * 参数模板中的占位符在运行时替换（逐个 argv 元素替换，不经过 shell）：
 * {@code {sqlFile}}（提交）、{@code {jobId}}（取消 / 状态）。
 * 未配置 {@code submit-args} 时按默认可执行文件补默认参数；若把可执行文件换成别的东西，
 * 必须显式配置对应参数，否则会给出可读的配置错误。</p>
 */
@Component
@ConfigurationProperties(prefix = "integration")
public class IntegrationClusterProperties {

    private Flink flink = new Flink();

    public Flink getFlink() {
        return flink;
    }

    public void setFlink(Flink flink) {
        this.flink = flink;
    }

    /**
     * Flink：通过命令行提交 SQL 作业。
     *
     * <p>默认用 {@code {home}/bin/sql-client.sh -f job.sql} 提交（Flink SQL 的原生命令行入口），
     * 取消与状态查询用 {@code {home}/bin/flink cancel|list}。</p>
     */
    public static class Flink {
        /** Flink 安装目录，如 /opt/flink；用于推导默认 bin 与工作目录 */
        private String home = "";
        /** 提交用可执行文件；为空则取 {home}/bin/sql-client.sh */
        private String submitBin = "";
        /** 取消 / 状态查询用可执行文件；为空则取 {home}/bin/flink */
        private String manageBin = "";
        /** 工作目录（生成的 .sql 落在这里）；为空则取 {home}/tmp 或系统临时目录 */
        private String workDir = "";
        /** 提交参数模板，支持 {sqlFile}；为空时对 sql-client 使用 -f {sqlFile} */
        private List<String> submitArgs = new ArrayList<>();
        /** 取消参数模板，支持 {jobId}；不含占位符时自动在末尾追加 jobId */
        private List<String> cancelArgs = new ArrayList<>(List.of("cancel"));
        /** 状态查询参数模板；默认 list -r（列出运行中的作业） */
        private List<String> statusArgs = new ArrayList<>(Arrays.asList("list", "-r"));
        /** sql-client 的 execution.target；提交到远端集群用 remote */
        private String executionTarget = "remote";
        /** JobManager REST 地址，如 http://127.0.0.1:8081；用于推导 -m host:port 与 rest.address/port */
        private String restUrl = "";
        /** 未指定并行度时的兜底值 */
        private int defaultParallelism = 1;
        /** checkpoint 间隔（毫秒），<=0 表示关闭 */
        private long checkpointInterval = 30000L;
        /** 数据集成要求的最低 Flink 版本：低于该版本直接拒绝提交 */
        private String minVersion = "2.2.0";
        /** 提交作业时携带的数据血缘（OpenLineage）配置 */
        private Lineage lineage = new Lineage();
        /** 取消 / 状态查询命令的超时（毫秒） */
        private int timeoutMs = 30000;
        /** 提交命令的超时（毫秒） */
        private long submitTimeoutMs = 120000L;

        public String getHome() {
            return home;
        }

        public void setHome(String home) {
            this.home = home;
        }

        public String getSubmitBin() {
            return submitBin;
        }

        public void setSubmitBin(String submitBin) {
            this.submitBin = submitBin;
        }

        public String getManageBin() {
            return manageBin;
        }

        public void setManageBin(String manageBin) {
            this.manageBin = manageBin;
        }

        public String getWorkDir() {
            return workDir;
        }

        public void setWorkDir(String workDir) {
            this.workDir = workDir;
        }

        public List<String> getSubmitArgs() {
            return submitArgs;
        }

        public void setSubmitArgs(List<String> submitArgs) {
            this.submitArgs = submitArgs;
        }

        public List<String> getCancelArgs() {
            return cancelArgs;
        }

        public void setCancelArgs(List<String> cancelArgs) {
            this.cancelArgs = cancelArgs;
        }

        public List<String> getStatusArgs() {
            return statusArgs;
        }

        public void setStatusArgs(List<String> statusArgs) {
            this.statusArgs = statusArgs;
        }

        public String getExecutionTarget() {
            return executionTarget;
        }

        public void setExecutionTarget(String executionTarget) {
            this.executionTarget = executionTarget;
        }

        public String getRestUrl() {
            return restUrl;
        }

        public void setRestUrl(String restUrl) {
            this.restUrl = restUrl;
        }

        public int getDefaultParallelism() {
            return defaultParallelism;
        }

        public void setDefaultParallelism(int defaultParallelism) {
            this.defaultParallelism = defaultParallelism;
        }

        public long getCheckpointInterval() {
            return checkpointInterval;
        }

        public void setCheckpointInterval(long checkpointInterval) {
            this.checkpointInterval = checkpointInterval;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public long getSubmitTimeoutMs() {
            return submitTimeoutMs;
        }

        public void setSubmitTimeoutMs(long submitTimeoutMs) {
            this.submitTimeoutMs = submitTimeoutMs;
        }

        public String getMinVersion() {
            return minVersion;
        }

        public void setMinVersion(String minVersion) {
            this.minVersion = minVersion;
        }

        public Lineage getLineage() {
            return lineage;
        }

        public void setLineage(Lineage lineage) {
            this.lineage = lineage;
        }
    }

    /**
     * 数据血缘：Flink 2.x 原生作业血缘接口（FLIP-314）+ OpenLineage，上报 Marquez。
     *
     * <p>生成的 SQL 会写入作业状态监听器与 OpenLineage 上报参数；
     * openlineage-flink jar 必须在 Flink 的 classpath 上（默认要求放在 {@code {flink.home}/lib}，
     * 也可通过 {@link #jar} 显式指定），缺失时提交直接失败，避免作业静默丢失血缘。</p>
     */
    public static class Lineage {
        /** 是否在提交作业时带上数据血缘配置 */
        private boolean enabled = true;
        /** openlineage-flink jar 路径；留空则取 {flink.home}/lib/openlineage-flink-*.jar */
        private String jar = "";
        /** OpenLineage HTTP 上报地址（Marquez API） */
        private String transportUrl = "http://127.0.0.1:5000";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getJar() {
            return jar;
        }

        public void setJar(String jar) {
            this.jar = jar;
        }

        public String getTransportUrl() {
            return transportUrl;
        }

        public void setTransportUrl(String transportUrl) {
            this.transportUrl = transportUrl;
        }
    }
}
