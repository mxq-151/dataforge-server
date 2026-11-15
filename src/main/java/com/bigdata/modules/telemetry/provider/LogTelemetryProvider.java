package com.bigdata.modules.telemetry.provider;

import com.bigdata.modules.logs.dto.LogQueryDTO;
import com.bigdata.modules.logs.model.ComponentRole;
import com.bigdata.modules.logs.model.LogKind;
import com.bigdata.modules.logs.model.LogSource;
import com.bigdata.modules.logs.model.ServiceType;
import com.bigdata.modules.logs.service.LogAccessControlService;
import com.bigdata.modules.logs.service.LogQueryService;
import com.bigdata.modules.logs.service.LogSourceCatalog;
import com.bigdata.modules.logs.vo.LogQueryResultVO;
import com.bigdata.modules.telemetry.model.Capability;
import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.DataTypes;
import com.bigdata.modules.telemetry.model.Requirement;
import com.bigdata.modules.telemetry.model.RequirementKind;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 日志类数据类型的取数实现，桥接现有 LogSourceCatalog / LogAdapter。
 *
 * <p>只服务能力树中的引擎服务类型（spark/flink）。{@code ds}/{@code yarn} 是接入与调度层、
 * {@code file} 是 transport，它们不是服务节点，仍由旧 {@code /api/logs/*} 提供。</p>
 */
@Component
public class LogTelemetryProvider implements TelemetryProvider {

    private static final Set<String> SERVICES = Set.of("spark", "flink");

    private static final Map<String, ComponentRole> ROLE_BY_COMPONENT = Map.ofEntries(
            Map.entry("spark.driver", ComponentRole.DRIVER),
            Map.entry("spark.executor", ComponentRole.EXECUTOR),
            Map.entry("flink.jobmanager", ComponentRole.JOB_MANAGER),
            Map.entry("flink.taskmanager", ComponentRole.TASK_MANAGER));

    private static final Map<String, LogKind> KIND_BY_DATA_TYPE = Map.of(
            DataTypes.LOG_RUNTIME, LogKind.RUNTIME,
            DataTypes.LOG_STDOUT, LogKind.STDOUT,
            DataTypes.LOG_AUDIT, LogKind.AUDIT,
            DataTypes.LOG_EXCEPTION_SUMMARY, LogKind.EXCEPTION);

    private static final Map<String, List<String>> TRANSPORTS = Map.of(
            "spark", List.of("spark-shs", "yarn-cli", "file"),
            "flink", List.of("flink-rest", "file"));

    private final LogSourceCatalog sourceCatalog;
    private final LogAccessControlService accessControl;
    private final LogQueryService logQueryService;

    public LogTelemetryProvider(LogSourceCatalog sourceCatalog,
                                LogAccessControlService accessControl,
                                LogQueryService logQueryService) {
        this.sourceCatalog = sourceCatalog;
        this.accessControl = accessControl;
        this.logQueryService = logQueryService;
    }

    @Override
    public String providerId() {
        return "log-adapter";
    }

    @Override
    public boolean supports(String serviceType, String componentId, String dataTypeId) {
        if (!SERVICES.contains(serviceType) || !componentId.startsWith(serviceType + ".")) {
            return false;
        }
        if (!ROLE_BY_COMPONENT.containsKey(componentId) || !KIND_BY_DATA_TYPE.containsKey(dataTypeId)) {
            return false;
        }
        // 异常摘要依赖 Flink REST 的作业异常接口，其他服务目前没有等价实现。
        return !DataTypes.LOG_EXCEPTION_SUMMARY.equals(dataTypeId) || "flink".equals(serviceType);
    }

    @Override
    public Capability evaluate(CapabilityContext context) {
        String serviceType = context.serviceType();
        String componentId = context.componentId();
        String dataTypeId = context.dataTypeId();
        List<String> transports = TRANSPORTS.getOrDefault(serviceType, List.of("file"));
        LogKind kind = KIND_BY_DATA_TYPE.get(dataTypeId);
        ComponentRole role = ROLE_BY_COMPONENT.get(componentId);

        ServiceType type;
        try {
            type = ServiceType.from(serviceType);
        } catch (IllegalArgumentException e) {
            return unavailable(dataTypeId, CapabilityCode.NO_PROVIDER,
                    "无法识别的服务类型: " + serviceType, transports,
                    requirement(RequirementKind.CONFIG, "logs.sources",
                            "在 logs.sources 中使用受支持的服务类型"));
        }
        if (type == null) {
            return unavailable(dataTypeId, CapabilityCode.NO_PROVIDER,
                    "无法识别的服务类型: " + serviceType, transports, List.of());
        }

        List<LogSource> matching = sourcesOf(type, role);
        boolean viaYarn = false;
        if (matching.isEmpty() && isSparkExecRole(serviceType, role)) {
            // spark.driver/executor 的日志由 YARN 聚合日志提供（spark-shs 没有文本日志接口）。
            matching = sourcesOf(ServiceType.YARN, null);
            viaYarn = true;
        }
        if (viaYarn) {
            transports = List.of("yarn-cli", "yarn-rest");
        }
        if (matching.isEmpty()) {
            return unavailable(dataTypeId, CapabilityCode.NOT_CONFIGURED,
                    "未配置 " + serviceType + " / " + componentId + " 的日志源", transports,
                    requirement(RequirementKind.CONFIG,
                            "logs.sources[" + componentId + "]",
                            "新增该服务与角色的日志源（type/id/roles/kinds）"));
        }

        List<LogSource> readable = matching.stream().filter(accessControl::canRead).toList();
        if (readable.isEmpty()) {
            return unavailable(dataTypeId, CapabilityCode.PERMISSION_DENIED,
                    "当前用户无权读取该日志源", transports,
                    requirement(RequirementKind.PERMISSION,
                            "logs.sources[" + matching.get(0).getId() + "].rbac",
                            "为当前角色授予该日志源读权限"));
        }

        List<LogSource> enabled = readable.stream().filter(LogSource::isEnabled).toList();
        if (enabled.isEmpty()) {
            LogSource disabled = readable.get(0);
            return unavailable(dataTypeId, CapabilityCode.SOURCE_DISABLED,
                    Optional.ofNullable(disabled.getDisabledReason()).orElse("日志源已禁用"),
                    transports,
                    requirement(RequirementKind.CONFIG,
                            "logs.sources[" + disabled.getId() + "].enabled",
                            "启用该日志源"));
        }

        LogKind effectiveKind = viaYarn ? LogKind.AGGREGATE : kind;
        String issue = null;
        for (LogSource source : enabled) {
            if (!source.getLogKinds().contains(effectiveKind)) {
                continue;
            }
            String kindIssue = source.getLogKindIssues().get(effectiveKind);
            if (kindIssue == null) {
                return Capability.available(dataTypeId, providerId(), transports);
            }
            if (issue == null) {
                issue = kindIssue;
            }
        }
        if (issue != null) {
            return unavailable(dataTypeId, CapabilityCode.NOT_CONFIGURED, issue, transports,
                    requirement(RequirementKind.CONFIG,
                            "logs.sources[" + enabled.get(0).getId() + "].kinds",
                            "补齐该日志种类所需的路径或接口配置"));
        }
        return unavailable(dataTypeId, CapabilityCode.NOT_CONFIGURED,
                "已启用的日志源未声明 " + effectiveKind.wireName() + " 日志", transports,
                requirement(RequirementKind.CONFIG,
                        "logs.sources[" + enabled.get(0).getId() + "].kinds",
                        "在日志源的 kinds 中声明 " + effectiveKind.wireName()));
    }

    @Override
    public Map<String, Object> fetch(TelemetryQueryContext context) {
        LogQueryDTO dto = new LogQueryDTO();
        ComponentRole role = ROLE_BY_COMPONENT.get(context.identity().componentId());
        boolean viaYarn = sourcesOf(ServiceType.from(context.identity().serviceType()), role).isEmpty()
                && isSparkExecRole(context.identity().serviceType(), role);
        dto.setServiceType(viaYarn ? "yarn" : context.identity().serviceType());
        dto.setComponentRole(viaYarn ? ComponentRole.NODE_MANAGER.wireName() : role.wireName());
        dto.setLogKind(viaYarn ? LogKind.AGGREGATE.wireName()
                : KIND_BY_DATA_TYPE.get(context.identity().dataTypeId()).wireName());
        dto.setRef(context.ref());
        dto.setCluster(context.ref() == null ? "default"
                : context.ref().getOrDefault("cluster", "default"));
        dto.setTimeRange(context.timeRange());
        dto.setKeyword(context.keyword());
        dto.setCursor(context.cursor());
        dto.setLimit(context.limit());
        dto.setMaxBytes(context.maxBytes());

        LogQueryResultVO result = logQueryService.query(dto);
        if (result.isDegraded()) {
            throw new TelemetryQueryException(mapErrorCode(result.getErrorCode()),
                    Optional.ofNullable(result.getDegradationReason())
                            .orElseGet(() -> Optional.ofNullable(result.getMessage())
                                    .orElse("日志查询失败")));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sourceId", result.getSourceId());
        data.put("lines", result.getLines());
        data.put("content", result.getContent());
        data.put("lineCount", result.getLineCount());
        data.put("byteSize", result.getByteSize());
        data.put("nextCursor", result.getNextCursor());
        data.put("hasMore", result.isHasMore());
        data.put("truncated", result.isTruncated());
        data.put("resolvedHost", result.getResolvedHost());
        data.put("resolvedPath", result.getResolvedPath());
        return data;
    }

    /** 把旧错误码收敛到可重试/不可重试两类。 */
    private CapabilityCode mapErrorCode(String errorCode) {
        if (errorCode == null) {
            return CapabilityCode.UPSTREAM_ERROR;
        }
        String code = errorCode.toUpperCase();
        if (code.contains("PERMISSION") || code.contains("FORBIDDEN")) {
            return CapabilityCode.PERMISSION_DENIED;
        }
        if (code.contains("UNSUPPORTED") || code.contains("NOT_CONFIGURED")
                || code.contains("DISABLED") || code.contains("NOT_FOUND")) {
            return CapabilityCode.NOT_CONFIGURED;
        }
        if (code.contains("TIMEOUT")) {
            return CapabilityCode.UPSTREAM_TIMEOUT;
        }
        return CapabilityCode.UPSTREAM_ERROR;
    }

    private List<LogSource> sourcesOf(ServiceType type, ComponentRole role) {
        return sourceCatalog.listAll().stream()
                .filter(source -> source.getServiceType() == type)
                .filter(source -> role == null || source.getRoles().contains(role))
                .toList();
    }

    /** spark.driver / spark.executor 的文本日志只有 YARN 聚合日志一条真实路径。 */
    private boolean isSparkExecRole(String serviceType, ComponentRole role) {
        return "spark".equals(serviceType)
                && (role == ComponentRole.DRIVER || role == ComponentRole.EXECUTOR);
    }

    private Capability unavailable(String dataTypeId,
                                   CapabilityCode code,
                                   String reason,
                                   List<String> transports,
                                   Requirement requirement) {
        return unavailable(dataTypeId, code, reason, transports, List.of(requirement));
    }

    private Capability unavailable(String dataTypeId,
                                   CapabilityCode code,
                                   String reason,
                                   List<String> transports,
                                   List<Requirement> requirements) {
        return Capability.unavailable(dataTypeId, code, reason, providerId(), transports, requirements);
    }

    private Requirement requirement(RequirementKind kind, String key, String description) {
        return new Requirement(kind, key, description);
    }
}
