package com.bigdata.modules.telemetry.config.properties;

import java.util.ArrayList;
import java.util.List;

/**
 * DolphinScheduler 任务日志配置：带访问令牌按 taskInstanceId 读取 {@code /log/detail}，
 * 只承载 {@code log.task}，不用于服务自身日志。
 *
 * <p>认证走 DS 的 {@code token} 请求头（{@code t_ds_access_token.token}），不使用账号密码登录。</p>
 */
public class DsConfig {
    private String id = "ds-api";
    /** DS API 根地址，需包含上下文，如 http://ds:12345/dolphinscheduler。 */
    private String baseUrl = "";
    /** DS 访问令牌；生产建议留空，用 {@link #tokenRef} 引用环境变量或密钥文件。 */
    private String token = "";
    /** 令牌的 secret ref（env: / file: / config: / plain:）。 */
    private String tokenRef = "";
    /** 可选的项目范围；留空时遍历当前账号的授权项目。 */
    private List<Long> projectCodes = new ArrayList<>();
    private DsLimits limits = new DsLimits();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getTokenRef() {
        return tokenRef;
    }

    public void setTokenRef(String tokenRef) {
        this.tokenRef = tokenRef;
    }

    public List<Long> getProjectCodes() {
        return projectCodes;
    }

    public void setProjectCodes(List<Long> projectCodes) {
        this.projectCodes = projectCodes;
    }

    public DsLimits getLimits() {
        return limits;
    }

    public void setLimits(DsLimits limits) {
        this.limits = limits;
    }
}
