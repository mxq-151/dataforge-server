package com.bigdata.modules.telemetry.config.properties;

import java.util.ArrayList;
import java.util.List;

/**
 * YARN 容器日志配置：已聚合时走 {@code yarn logs} CLI；聚合未完成时回退到
 * NM 容器本地日志目录（RM {@code /ws/v1/cluster/nodes} → NM {@code /ws/v1/node/containers}
 * 定位 containerId，再按 {@code node-log-root/appId/containerId/stream} 直读）。
 *
 * <p>RM REST 同时用于把 {@code ref.jobName} 解析成 applicationId。</p>
 */
public class YarnConfig {
    private String id = "yarn";
    /** ResourceManager REST 根地址；只提供 jobName 或需要 node-local 回退时必填。 */
    private String rmBaseUrl = "";
    /** yarn 可执行文件（绝对路径或 PATH 中的命令名）。 */
    private String command = "yarn";
    /** 追加在 command 之后的固定参数（如 -D 配置）。 */
    private List<String> extraArgs = new ArrayList<>();
    /**
     * 本进程可见的 NM 容器日志根目录（如挂载到宿主后的 {@code /var/lib/dataforge-yarn/nm-logs}）。
     * 留空则 node-local 路径不可用；未挂载时即使配置了也读不到文件。
     */
    private String nodeLogRoot = "";
    /**
     * NM REST 根地址覆盖，单节点/端口映射场景使用（如 {@code http://127.0.0.1:8042}）。
     * 留空时按 RM 返回的 {@code nodeHTTPAddress} 逐个拼接。
     */
    private String nmBaseUrl = "";
    private Auth auth = new Auth();
    private YarnLimits limits = new YarnLimits();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRmBaseUrl() {
        return rmBaseUrl;
    }

    public void setRmBaseUrl(String rmBaseUrl) {
        this.rmBaseUrl = rmBaseUrl;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public List<String> getExtraArgs() {
        return extraArgs;
    }

    public void setExtraArgs(List<String> extraArgs) {
        this.extraArgs = extraArgs;
    }

    public String getNodeLogRoot() {
        return nodeLogRoot;
    }

    public void setNodeLogRoot(String nodeLogRoot) {
        this.nodeLogRoot = nodeLogRoot;
    }

    public String getNmBaseUrl() {
        return nmBaseUrl;
    }

    public void setNmBaseUrl(String nmBaseUrl) {
        this.nmBaseUrl = nmBaseUrl;
    }

    public Auth getAuth() {
        return auth;
    }

    public void setAuth(Auth auth) {
        this.auth = auth;
    }

    public YarnLimits getLimits() {
        return limits;
    }

    public void setLimits(YarnLimits limits) {
        this.limits = limits;
    }
}
