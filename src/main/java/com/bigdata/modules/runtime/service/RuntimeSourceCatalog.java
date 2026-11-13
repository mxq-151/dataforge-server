package com.bigdata.modules.runtime.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.logs.model.ComponentRole;
import com.bigdata.modules.logs.model.ServiceType;
import com.bigdata.modules.logs.service.SecretResolver;
import com.bigdata.modules.runtime.config.RuntimeProperties;
import com.bigdata.modules.runtime.model.RuntimeMode;
import com.bigdata.modules.runtime.model.RuntimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行时源目录：解析 {@code runtime.sources}，并给出启用状态与原因。
 *
 * <p>复用日志模块的凭据解析（{@link SecretResolver}）与配置模型；但启用规则按「运行中 / 结束后」区分：
 * Flink 只支持 live；Spark history 必须有 History Server 地址；Spark live 可以是 base-url、
 * tracking-url-template 或 YARN RM（运行时反查 trackingUrl）。</p>
 */
@Service
public class RuntimeSourceCatalog {

    private static final Logger log = LoggerFactory.getLogger(RuntimeSourceCatalog.class);

    private final RuntimeProperties properties;
    private final SecretResolver secretResolver;

    public RuntimeSourceCatalog(RuntimeProperties properties, SecretResolver secretResolver) {
        this.properties = properties;
        this.secretResolver = secretResolver;
    }

    public List<RuntimeSource> listAll() {
        List<RuntimeSource> result = new ArrayList<>();
        if (properties.getSources() == null) {
            return result;
        }
        for (RuntimeProperties.Source config : properties.getSources()) {
            if (config != null) {
                result.add(build(config));
            }
        }
        return result;
    }

    public RuntimeSource findById(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return null;
        }
        for (RuntimeSource source : listAll()) {
            if (sourceId.trim().equalsIgnoreCase(source.getId())) {
                return source;
            }
        }
        return null;
    }

    /**
     * 按 sourceId 或 serviceType + cluster + mode 定位。
     *
     * @throws BusinessException 400/404：配置缺失或无法唯一定位
     */
    public RuntimeSource resolve(String sourceId, String serviceType, String cluster, String mode) {
        if (sourceId != null && !sourceId.isBlank()) {
            RuntimeSource source = findById(sourceId);
            if (source == null) {
                throw new BusinessException(404, "未配置运行时源: " + sourceId);
            }
            return source;
        }
        if (serviceType == null || serviceType.isBlank()) {
            throw new BusinessException(400, "必须指定 sourceId 或 serviceType");
        }
        ServiceType type = parseServiceType(serviceType);
        RuntimeMode runtimeMode = parseMode(mode);
        String wantedCluster = cluster == null || cluster.isBlank() ? "default" : cluster.trim();

        List<RuntimeSource> candidates = new ArrayList<>();
        for (RuntimeSource source : listAll()) {
            if (source.getServiceType() != type) {
                continue;
            }
            if (!source.getCluster().equalsIgnoreCase(wantedCluster)) {
                continue;
            }
            if (runtimeMode != null && source.getMode() != runtimeMode) {
                continue;
            }
            candidates.add(source);
        }
        if (candidates.isEmpty()) {
            // 集群不匹配时退回该服务下第一个源，避免调用方必须知道集群标识
            for (RuntimeSource source : listAll()) {
                if (source.getServiceType() == type
                        && (runtimeMode == null || source.getMode() == runtimeMode)) {
                    log.debug("未找到 cluster={} 的 {} 运行时源，回退到 {}", wantedCluster,
                            type.wireName(), source.getId());
                    candidates.add(source);
                }
            }
        }
        if (candidates.isEmpty()) {
            throw new BusinessException(404,
                    "未配置 " + type.wireName() + "（cluster=" + wantedCluster
                            + (runtimeMode == null ? "" : ", mode=" + runtimeMode.wireName()) + "）的运行时源");
        }
        if (candidates.size() > 1 && runtimeMode == null && type == ServiceType.SPARK) {
            throw new BusinessException(400,
                    "存在多个 Spark 运行时源（history/live），请显式指定 mode 或 sourceId");
        }
        return candidates.get(0);
    }

    private RuntimeSource build(RuntimeProperties.Source config) {
        String id = config.getId() == null ? "" : config.getId().trim();
        ServiceType type;
        try {
            type = ServiceType.from(config.getServiceType());
        } catch (IllegalArgumentException e) {
            return disabled(config, null, RuntimeMode.LIVE, List.of(), "", "", e.getMessage());
        }
        if (type == null) {
            return disabled(config, null, RuntimeMode.LIVE, List.of(), "", "", "未配置 service-type");
        }
        if (type != ServiceType.FLINK && type != ServiceType.SPARK) {
            return disabled(config, type, RuntimeMode.LIVE, List.of(), "", "",
                    "runtime 仅支持 flink / spark，当前为 " + type.wireName());
        }
        RuntimeMode mode;
        try {
            mode = RuntimeMode.from(config.getMode());
        } catch (IllegalArgumentException e) {
            return disabled(config, type, RuntimeMode.LIVE, List.of(), "", "", e.getMessage());
        }
        List<ComponentRole> roles = new ArrayList<>();
        try {
            for (String role : safeList(config.getRoles())) {
                roles.add(ComponentRole.from(role));
            }
        } catch (IllegalArgumentException e) {
            return disabled(config, type, mode, List.of(), "", "", e.getMessage());
        }

        String baseUrl = config.getBaseUrl() == null ? "" : config.getBaseUrl().trim();
        String token = "";
        String credentialError = null;
        try {
            token = secretResolver.resolve(
                    config.getAuth() == null ? "" : config.getAuth().getToken(),
                    config.getAuth() == null ? "" : config.getAuth().getTokenRef(),
                    "runtime.sources[" + id + "].auth.token");
        } catch (IllegalStateException e) {
            credentialError = e.getMessage();
        }

        boolean enabled = true;
        String reason = "";
        RuntimeProperties.Options options = config.getOptions() == null
                ? new RuntimeProperties.Options() : config.getOptions();
        if (!properties.isEnabled()) {
            enabled = false;
            reason = "运行时指标层总开关 runtime.enabled=false";
        } else if (!config.isEnabled()) {
            enabled = false;
            reason = "该运行时源已在配置中关闭（enabled=false）";
        } else if (credentialError != null) {
            enabled = false;
            reason = credentialError;
        } else if (type == ServiceType.FLINK) {
            if (mode != RuntimeMode.LIVE) {
                enabled = false;
                reason = "Flink 仅支持 live 模式（JobManager REST）；历史数据需接入 Flink History Server（P1）";
            } else if (baseUrl.isBlank()) {
                enabled = false;
                reason = "未配置 base-url（Flink JobManager REST）";
            }
        } else if (mode == RuntimeMode.HISTORY) {
            if (baseUrl.isBlank()) {
                enabled = false;
                reason = "未配置 base-url（Spark History Server），无法查询已结束作业";
            }
        } else {
            boolean liveReachable = !baseUrl.isBlank()
                    || !trim(options.getTrackingUrlTemplate()).isBlank()
                    || !trim(options.getYarnRmUrl()).isBlank();
            if (!liveReachable) {
                enabled = false;
                reason = "未配置 Spark live UI 的 base-url / tracking-url-template / yarn-rm-url";
            }
        }

        return new RuntimeSource(config, type, mode, roles, baseUrl, token, enabled, reason);
    }

    private RuntimeSource disabled(RuntimeProperties.Source config, ServiceType type, RuntimeMode mode,
                                   List<ComponentRole> roles, String baseUrl, String token,
                                   String reason) {
        return new RuntimeSource(config, type, mode, roles, baseUrl, token, false, reason);
    }

    private ServiceType parseServiceType(String value) {
        try {
            ServiceType type = ServiceType.from(value);
            if (type != ServiceType.FLINK && type != ServiceType.SPARK) {
                throw new BusinessException(400, "runtime 仅支持 flink / spark");
            }
            return type;
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, e.getMessage());
        }
    }

    private RuntimeMode parseMode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return RuntimeMode.from(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, e.getMessage());
        }
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
