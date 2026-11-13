package com.bigdata.modules.logs.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.logs.config.LogAccessProperties;
import com.bigdata.modules.logs.model.ComponentRole;
import com.bigdata.modules.logs.model.LogKind;
import com.bigdata.modules.logs.model.LogSource;
import com.bigdata.modules.logs.model.ServiceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志源目录：把 {@code logs.sources} 配置解析成运行期 {@link LogSource}，并计算启用状态。
 *
 * <p>启用判定完全基于配置：地址为空、凭据解析失败、总开关关闭、file 适配器未配置根目录，
 * 都会给出可读的 {@code disabledReason}，而不是等到调用时抛异常。</p>
 */
@Service
public class LogSourceCatalog {

    private static final Logger log = LoggerFactory.getLogger(LogSourceCatalog.class);

    private final LogAccessProperties properties;
    private final SecretResolver secretResolver;
    private final Environment environment;

    public LogSourceCatalog(LogAccessProperties properties, SecretResolver secretResolver,
                            Environment environment) {
        this.properties = properties;
        this.secretResolver = secretResolver;
        this.environment = environment;
    }

    public List<LogSource> listAll() {
        List<LogSource> sources = new ArrayList<>();
        if (properties.getSources() == null) {
            return sources;
        }
        for (LogAccessProperties.Source config : properties.getSources()) {
            if (config == null) {
                continue;
            }
            sources.add(build(config));
        }
        return sources;
    }

    public LogSource findById(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return null;
        }
        for (LogSource source : listAll()) {
            if (sourceId.trim().equalsIgnoreCase(source.getId())) {
                return source;
            }
        }
        return null;
    }

    /**
     * 按 sourceId 或 serviceType + cluster 定位日志源。
     *
     * @throws BusinessException 404：配置里没有对应日志源
     */
    public LogSource resolve(String sourceId, String serviceType, String cluster) {
        if (sourceId != null && !sourceId.isBlank()) {
            LogSource source = findById(sourceId);
            if (source == null) {
                throw new BusinessException(404, "未配置日志源: " + sourceId);
            }
            return source;
        }
        if (serviceType == null || serviceType.isBlank()) {
            throw new BusinessException(400, "必须指定 sourceId 或 serviceType");
        }
        ServiceType type = parseServiceType(serviceType);
        String wantedCluster = (cluster == null || cluster.isBlank()) ? "default" : cluster.trim();
        for (LogSource source : listAll()) {
            if (source.getServiceType() == type && source.getCluster().equalsIgnoreCase(wantedCluster)) {
                return source;
            }
        }
        // 集群名不匹配时退回该服务下第一个源，避免调用方必须知道集群标识
        for (LogSource source : listAll()) {
            if (source.getServiceType() == type) {
                log.debug("未找到 cluster={} 的 {} 日志源，回退到 {}", wantedCluster,
                        type.wireName(), source.getId());
                return source;
            }
        }
        throw new BusinessException(404,
                "未配置 " + type.wireName() + "（cluster=" + wantedCluster + "）的日志源");
    }

    private LogSource build(LogAccessProperties.Source config) {
        String id = config.getId() == null ? "" : config.getId().trim();
        ServiceType type;
        try {
            type = ServiceType.from(config.getServiceType());
        } catch (IllegalArgumentException e) {
            return disabled(config, null, List.of(), List.of(), "", "", e.getMessage());
        }
        if (type == null) {
            return disabled(config, null, List.of(), List.of(), "", "", "未配置 service-type");
        }

        List<ComponentRole> roles = new ArrayList<>();
        List<LogKind> logKinds = new ArrayList<>();
        try {
            for (String role : safeList(config.getRoles())) {
                roles.add(ComponentRole.from(role));
            }
            for (String kind : safeList(config.getLogKinds())) {
                logKinds.add(LogKind.from(kind));
            }
        } catch (IllegalArgumentException e) {
            return disabled(config, type, List.of(), List.of(), "", "", e.getMessage());
        }

        String baseUrl = normalizeUrl(config.getBaseUrl());
        String credentialError = null;
        String token = "";
        LogAccessProperties.Auth auth = config.getAuth() == null
                ? new LogAccessProperties.Auth() : config.getAuth();
        try {
            token = secretResolver.resolve(auth.getToken(), auth.getTokenRef(),
                    "logs.sources[" + id + "].auth.token");
        } catch (IllegalStateException e) {
            credentialError = e.getMessage();
        }
        if (token.isBlank() && type == ServiceType.DS) {
            // 与既有调度配置保持兼容：DS 地址/token 留空时复用 scheduler.dolphinscheduler.*
            if (baseUrl.isBlank()) {
                baseUrl = normalizeUrl(environment.getProperty("scheduler.dolphinscheduler.base-url", ""));
            }
            token = environment.getProperty("scheduler.dolphinscheduler.token", "");
        }

        boolean enabled = true;
        String reason = "";
        if (!properties.isEnabled()) {
            enabled = false;
            reason = "日志访问层总开关 logs.enabled=false";
        } else if (!config.isEnabled()) {
            enabled = false;
            reason = "该日志源已在配置中关闭（enabled=false）";
        } else if (type == ServiceType.FILE) {
            List<String> rootDirs = config.getOptions() == null ? List.of()
                    : config.getOptions().getRootDirs();
            if (rootDirs == null || rootDirs.isEmpty()) {
                enabled = false;
                reason = "未配置 options.root-dirs";
            }
        } else if (type == ServiceType.FLINK) {
            // Flink 允许只配 REST 或只配日志目录；两者都没有才算未启用
            boolean hasRest = !baseUrl.isBlank();
            boolean hasDirs = !flinkDirs(config).isEmpty();
            if (!hasRest && !hasDirs) {
                enabled = false;
                reason = "未配置 base-url（JobManager REST）与 jobmanager/taskmanager 日志目录";
            } else if (credentialError != null && !hasDirs) {
                enabled = false;
                reason = credentialError;
            }
        } else if (baseUrl.isBlank()) {
            enabled = false;
            reason = "未配置 base-url";
        } else if (credentialError != null) {
            enabled = false;
            reason = credentialError;
        }

        LogSource source = new LogSource(config, type, roles, logKinds, baseUrl, token, enabled, reason);
        source.setLogKindIssues(computeLogKindIssues(type, config, baseUrl));
        return source;
    }

    /**
     * 计算「已声明但当前配置下不可用」的日志种类，供 /sources 如实展示。
     * <p>Flink 的两层能力边界不同：REST 只能拿异常/运行摘要，原始日志必须配置目录；
     * K8s 容器日志与对象存储适配器属于 P1，配置了也只标注原因，不假装可用。</p>
     */
    private Map<LogKind, String> computeLogKindIssues(ServiceType type,
                                                      LogAccessProperties.Source config,
                                                      String baseUrl) {
        Map<LogKind, String> issues = new LinkedHashMap<>();
        if (type != ServiceType.FLINK) {
            return issues;
        }
        LogAccessProperties.Options options = config.getOptions() == null
                ? new LogAccessProperties.Options() : config.getOptions();
        boolean hasRest = baseUrl != null && !baseUrl.isBlank();
        boolean hasAnyDir = !flinkDirs(config).isEmpty();
        boolean k8sConfigured = options.getPodSelector() != null && !options.getPodSelector().isBlank();
        boolean objectStoreConfigured = options.getObjectStorePath() != null
                && !options.getObjectStorePath().isBlank();

        if (!hasRest) {
            issues.put(LogKind.EXCEPTION, "未配置 base-url（Flink JobManager REST），无法读取作业异常摘要");
        }
        if (!hasAnyDir) {
            String reason = k8sConfigured
                    ? "K8s 容器日志适配器计划在 P1 提供（已配置 pod-selector，但 P0 未实现）"
                    : objectStoreConfigured
                    ? "对象存储日志适配器计划在 P1 提供（已配置 object-store-path，但 P0 未实现）"
                    : "未配置 jobmanager-log-dirs / taskmanager-log-dirs";
            issues.put(LogKind.RUNTIME, reason);
            issues.put(LogKind.STDOUT, reason);
        }
        return issues;
    }

    private List<String> flinkDirs(LogAccessProperties.Source config) {
        LogAccessProperties.Options options = config.getOptions();
        if (options == null) {
            return List.of();
        }
        List<String> dirs = new ArrayList<>();
        if (options.getJobManagerLogDirs() != null) {
            dirs.addAll(options.getJobManagerLogDirs());
        }
        if (options.getTaskManagerLogDirs() != null) {
            dirs.addAll(options.getTaskManagerLogDirs());
        }
        dirs.removeIf(dir -> dir == null || dir.isBlank());
        return dirs;
    }

    private LogSource disabled(LogAccessProperties.Source config, ServiceType type,
                               List<ComponentRole> roles, List<LogKind> logKinds,
                               String baseUrl, String token, String reason) {
        return new LogSource(config, type, roles, logKinds, baseUrl, token, false, reason);
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String normalizeUrl(String value) {
        return value == null ? "" : value.trim();
    }

    private ServiceType parseServiceType(String value) {
        try {
            return ServiceType.from(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, e.getMessage());
        }
    }
}
