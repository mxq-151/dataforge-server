package com.bigdata.modules.runtime.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.logs.model.ComponentRole;
import com.bigdata.modules.logs.model.ServiceType;
import com.bigdata.modules.logs.service.LogCircuitBreaker;
import com.bigdata.modules.logs.service.LogExecutorRegistry;
import com.bigdata.modules.logs.service.LogMaskingService;
import com.bigdata.modules.runtime.adapter.RuntimeAdapter;
import com.bigdata.modules.runtime.adapter.RuntimeAdapterException;
import com.bigdata.modules.runtime.adapter.RuntimeAdapterRegistry;
import com.bigdata.modules.runtime.adapter.RuntimeQueryContext;
import com.bigdata.modules.runtime.config.RuntimeProperties;
import com.bigdata.modules.runtime.dto.RuntimeQueryDTO;
import com.bigdata.modules.runtime.model.MetricCatalog;
import com.bigdata.modules.runtime.model.MetricFamily;
import com.bigdata.modules.runtime.model.MetricSpec;
import com.bigdata.modules.runtime.model.RuntimeSource;
import com.bigdata.modules.runtime.vo.RuntimeCompareVO;
import com.bigdata.modules.runtime.vo.RuntimeComponentVO;
import com.bigdata.modules.runtime.vo.RuntimeComponentsVO;
import com.bigdata.modules.runtime.vo.RuntimeConfigVO;
import com.bigdata.modules.runtime.vo.RuntimeDiscoveryVO;
import com.bigdata.modules.runtime.vo.RuntimeJobVO;
import com.bigdata.modules.runtime.vo.RuntimeMetricDefinitionVO;
import com.bigdata.modules.runtime.vo.RuntimeMetricVO;
import com.bigdata.modules.runtime.vo.RuntimeSnapshotVO;
import com.bigdata.modules.runtime.vo.RuntimeSourceVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 运行时指标查询编排。
 *
 * <p>与日志模块共享：凭据解析、RBAC、脱敏、审计、熔断、按源线程池、并发限流；
 * 不共享游标语义 —— 指标是快照，没有翻页。上游不可达/超时返回 {@code degraded=true} 的
 * 结构化响应，配置与权限问题才抛业务异常。</p>
 */
@Service
public class RuntimeQueryService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeQueryService.class);
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(password|passwd|pwd|secret|token|access[._-]?key|credential).*");
    private static final Set<String> COUNTER_UNITS = Set.of("count", "bytes", "ms", "records");

    private final RuntimeProperties properties;
    private final RuntimeSourceCatalog sourceCatalog;
    private final RuntimeAdapterRegistry adapterRegistry;
    private final RuntimeAccessControlService accessControl;
    private final RuntimeCache cache;
    private final RuntimeSnapshotStore snapshotStore;
    private final RuntimeAuditService auditService;
    private final LogCircuitBreaker circuitBreaker;
    private final LogExecutorRegistry executorRegistry;
    private final LogMaskingService maskingService;

    /** 按源隔离的并发信号量 */
    private final Map<String, Semaphore> semaphores = new ConcurrentHashMap<>();

    public RuntimeQueryService(RuntimeProperties properties, RuntimeSourceCatalog sourceCatalog,
                               RuntimeAdapterRegistry adapterRegistry,
                               RuntimeAccessControlService accessControl,
                               RuntimeCache cache, RuntimeSnapshotStore snapshotStore,
                               RuntimeAuditService auditService, LogCircuitBreaker circuitBreaker,
                               LogExecutorRegistry executorRegistry, LogMaskingService maskingService) {
        this.properties = properties;
        this.sourceCatalog = sourceCatalog;
        this.adapterRegistry = adapterRegistry;
        this.accessControl = accessControl;
        this.cache = cache;
        this.snapshotStore = snapshotStore;
        this.auditService = auditService;
        this.circuitBreaker = circuitBreaker;
        this.executorRegistry = executorRegistry;
        this.maskingService = maskingService;
    }

    // ------------------------------------------------------------------
    // 源与指标目录
    // ------------------------------------------------------------------

    public List<RuntimeSourceVO> listSources() {
        List<RuntimeSourceVO> result = new ArrayList<>();
        for (RuntimeSource source : sourceCatalog.listAll()) {
            if (!accessControl.canRead(source) || source.getServiceType() == null) {
                continue;
            }
            RuntimeSourceVO vo = new RuntimeSourceVO();
            vo.setId(source.getId());
            vo.setName(source.getName());
            vo.setServiceType(source.getServiceType().wireName());
            vo.setCluster(source.getCluster());
            vo.setMode(source.getMode().wireName());
            vo.setEnabled(source.isEnabled());
            vo.setDisabledReason(source.getDisabledReason());
            vo.setBaseUrlConfigured(source.getBaseUrl() != null && !source.getBaseUrl().isBlank());
            vo.setRoles(source.getRoles().stream().map(ComponentRole::wireName)
                    .collect(Collectors.toList()));
            vo.setSupportedFamilies(MetricCatalog.supportedFamilies(source.getServiceType())
                    .stream().map(MetricFamily::wireName).collect(Collectors.toList()));
            vo.setUnavailableFamilies(MetricCatalog.unavailableFamilies(source.getServiceType()));
            vo.setCacheTtlMs(properties.getCache().getTtlMs());
            result.add(vo);
        }
        return result;
    }

    /** 指标对照表（Flink + Spark） */
    public List<RuntimeMetricDefinitionVO> catalog() {
        List<RuntimeMetricDefinitionVO> result = new ArrayList<>();
        for (ServiceType type : List.of(ServiceType.FLINK, ServiceType.SPARK)) {
            for (MetricSpec spec : MetricCatalog.definitions(type)) {
                RuntimeMetricDefinitionVO vo = new RuntimeMetricDefinitionVO();
                vo.setServiceType(type.wireName());
                vo.setFamily(spec.getFamily().wireName());
                vo.setName(spec.getName());
                vo.setRawMetric(spec.getRawMetric());
                vo.setScope(spec.getScope().name().toLowerCase(Locale.ROOT));
                vo.setEndpoint(spec.getEndpoint());
                vo.setUnit(spec.getUnit());
                vo.setVerified(spec.isVerified());
                vo.setNote(spec.getNote());
                result.add(vo);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public RuntimeDiscoveryVO listJobs(RuntimeQueryDTO dto) {
        long start = System.currentTimeMillis();
        RuntimeSource source = resolve(dto);
        RuntimeQueryContext context = context(source, dto);
        String key = cacheKey("jobs", source, dto, context);
        Object cached = cache.get(key);
        if (cached instanceof RuntimeDiscoveryVO discovery) {
            return discovery;
        }
        try {
            RuntimeAdapter adapter = adapterRegistry.resolve(source.getServiceType(), source.getMode());
            List<RuntimeJobVO> jobs = call(source, adapter, a -> a.listJobs(context));
            RuntimeDiscoveryVO vo = new RuntimeDiscoveryVO();
            vo.setJobs(jobs);
            vo.setCapturedAt(System.currentTimeMillis());
            cache.put(key, vo, properties.getCache().getTtlMs());
            audit(dto, source, "LIST_RUNTIME_JOBS", start, false, null);
            return vo;
        } catch (RuntimeAdapterException e) {
            handleFatal(e, "运行时作业发现失败");
            RuntimeDiscoveryVO vo = degradedDiscovery(e);
            audit(dto, source, "LIST_RUNTIME_JOBS", start, true, errorCode(e));
            return vo;
        }
    }

    public RuntimeComponentsVO listComponents(RuntimeQueryDTO dto) {
        long start = System.currentTimeMillis();
        RuntimeSource source = resolve(dto);
        RuntimeQueryContext context = context(source, dto);
        String key = cacheKey("components", source, dto, context);
        Object cached = cache.get(key);
        if (cached instanceof RuntimeComponentsVO components) {
            return components;
        }
        RuntimeComponentsVO vo = baseComponents(source, dto);
        try {
            RuntimeAdapter adapter = adapterRegistry.resolve(source.getServiceType(), source.getMode());
            List<RuntimeComponentVO> components = call(source, adapter, a -> a.listComponents(context));
            vo.setComponents(filterComponents(components, dto.getComponents()));
            vo.setCapturedAt(System.currentTimeMillis());
            cache.put(key, vo, properties.getCache().getTtlMs());
            audit(dto, source, "LIST_RUNTIME_COMPONENTS", start, false, null);
            return vo;
        } catch (RuntimeAdapterException e) {
            handleFatal(e, "运行时组件查询失败");
            vo.setDegraded(true);
            vo.setDegradationReason(e.getMessage());
            vo.setCapturedAt(System.currentTimeMillis());
            audit(dto, source, "LIST_RUNTIME_COMPONENTS", start, true, errorCode(e));
            return vo;
        }
    }

    public RuntimeSnapshotVO snapshot(RuntimeQueryDTO dto) {
        long start = System.currentTimeMillis();
        RuntimeSource source = resolve(dto);
        RuntimeQueryContext context = context(source, dto);
        String cacheKey = cacheKey("snapshot", source, dto, context);
        Object cached = cache.get(cacheKey);
        if (cached instanceof RuntimeSnapshotVO snapshot) {
            return snapshot;
        }
        RuntimeSnapshotVO vo;
        try {
            vo = executeSnapshot(source, context, dto);
            cache.put(cacheKey, vo, properties.getCache().getTtlMs());
            audit(dto, source, "QUERY_RUNTIME_SNAPSHOT", start, false, null);
            return vo;
        } catch (RuntimeAdapterException e) {
            handleFatal(e, "运行时快照查询失败");
            vo = degradedSnapshot(source, dto, e);
            audit(dto, source, "QUERY_RUNTIME_SNAPSHOT", start, true, errorCode(e));
            return vo;
        }
    }

    public RuntimeConfigVO config(RuntimeQueryDTO dto) {
        long start = System.currentTimeMillis();
        RuntimeSource source = resolve(dto);
        RuntimeQueryContext context = context(source, dto);
        String key = cacheKey("config", source, dto, context);
        Object cached = cache.get(key);
        if (cached instanceof RuntimeConfigVO config) {
            return config;
        }
        RuntimeConfigVO vo = baseConfig(source, dto);
        try {
            RuntimeAdapter adapter = adapterRegistry.resolve(source.getServiceType(), source.getMode());
            RuntimeConfigVO result = call(source, adapter, a -> a.config(context));
            result.setComponents(filterComponents(result.getComponents(), dto.getComponents()));
            maskConfig(result);
            cache.put(key, result, properties.getCache().getConfigTtlMs());
            audit(dto, source, "QUERY_RUNTIME_CONFIG", start, false, null);
            return result;
        } catch (RuntimeAdapterException e) {
            handleFatal(e, "运行时配置查询失败");
            vo.setAvailable(false);
            vo.setReason(e.getMessage());
            vo.setCapturedAt(System.currentTimeMillis());
            audit(dto, source, "QUERY_RUNTIME_CONFIG", start, true, errorCode(e));
            return vo;
        }
    }

    /**
     * 时间点对比：以当前快照与进程内更早的一份快照做差，给出累计指标的增量与每秒速率。
     */
    public RuntimeCompareVO compare(RuntimeQueryDTO dto) {
        long start = System.currentTimeMillis();
        RuntimeSource source = resolve(dto);
        RuntimeQueryContext context = context(source, dto);
        context.setBypassCache(true);
        long window = dto.getWindowSeconds() == null ? 60L : Math.max(dto.getWindowSeconds(), 1L);

        RuntimeCompareVO vo = new RuntimeCompareVO();
        vo.setSourceId(source.getId());
        vo.setServiceType(source.getServiceType().wireName());
        vo.setJobId(dto.getJobId());
        vo.setWindowSeconds(window);

        RuntimeSnapshotVO current;
        try {
            current = executeSnapshot(source, context, dto);
        } catch (RuntimeAdapterException e) {
            handleFatal(e, "运行时对比查询失败");
            vo.setComparable(false);
            vo.setReason(e.getMessage());
            audit(dto, source, "COMPARE_RUNTIME", start, true, errorCode(e));
            return vo;
        }

        String key = snapshotKey(source, dto.getJobId());
        RuntimeSnapshotVO prior = snapshotStore.findPrior(key, window);
        if (prior == null) {
            vo.setComparable(false);
            vo.setReason("窗口内没有更早的快照；请先多次调用快照接口（本服务只在进程内保留最近 10 份）");
            vo.setToCapturedAt(current.getCapturedAt());
            audit(dto, source, "COMPARE_RUNTIME", start, false, null);
            return vo;
        }

        vo.setComparable(true);
        vo.setFromCapturedAt(prior.getCapturedAt());
        vo.setToCapturedAt(current.getCapturedAt());
        double seconds = Math.max((current.getCapturedAt() - prior.getCapturedAt()) / 1000.0, 0.001);
        Map<String, RuntimeMetricVO> before = flatten(prior);
        Map<String, RuntimeMetricVO> after = flatten(current);
        List<RuntimeCompareVO.Row> rows = new ArrayList<>();
        for (Map.Entry<String, RuntimeMetricVO> entry : after.entrySet()) {
            RuntimeMetricVO to = entry.getValue();
            RuntimeMetricVO from = before.get(entry.getKey());
            if (from == null || from.getValue() == null || to.getValue() == null) {
                continue;
            }
            if (!COUNTER_UNITS.contains(to.getUnit()) || !to.getUnit().equals(from.getUnit())) {
                continue;
            }
            RuntimeCompareVO.Row row = new RuntimeCompareVO.Row();
            row.setComponent(to.getComponent());
            row.setComponentType(to.getComponentType());
            row.setFamily(to.getFamily());
            row.setName(to.getName());
            row.setUnit(to.getUnit());
            row.setFrom(from.getValue());
            row.setTo(to.getValue());
            double delta = to.getValue() - from.getValue();
            row.setDelta(delta);
            row.setPerSecond(delta / seconds);
            rows.add(row);
        }
        rows.sort(Comparator.comparing(RuntimeCompareVO.Row::getComponent,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(RuntimeCompareVO.Row::getName,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        vo.setRows(rows);
        audit(dto, source, "COMPARE_RUNTIME", start, false, null);
        return vo;
    }

    // ------------------------------------------------------------------
    // 快照执行
    // ------------------------------------------------------------------

    private RuntimeSnapshotVO executeSnapshot(RuntimeSource source, RuntimeQueryContext context,
                                              RuntimeQueryDTO dto) {
        RuntimeAdapter adapter = adapterRegistry.resolve(source.getServiceType(), source.getMode());
        RuntimeSnapshotVO snapshot = call(source, adapter, a -> a.snapshot(context));
        snapshot.setComponents(filterComponents(snapshot.getComponents(), dto.getComponents()));
        if (snapshot.getJobConfig() != null) {
            Object masked = maskObject(snapshot.getJobConfig());
            if (masked instanceof Map<?, ?> map) {
                Map<String, Object> config = new LinkedHashMap<>();
                map.forEach((key, value) -> config.put(String.valueOf(key), value));
                snapshot.setJobConfig(config);
            }
        }
        if (snapshot.getCapturedAt() == null) {
            snapshot.setCapturedAt(System.currentTimeMillis());
        }
        snapshotStore.record(snapshotKey(source, snapshot.getJobId()), snapshot);
        return snapshot;
    }

    // ------------------------------------------------------------------
    // 参数、定位、权限
    // ------------------------------------------------------------------

    private RuntimeSource resolve(RuntimeQueryDTO dto) {
        if (dto == null) {
            throw new BusinessException(400, "请求参数不能为空");
        }
        if (!properties.isEnabled()) {
            throw new BusinessException(400, "运行时指标层未启用（runtime.enabled=false）");
        }
        RuntimeSource source = sourceCatalog.resolve(dto.getSourceId(), dto.getServiceType(),
                dto.getCluster(), dto.getMode());
        accessControl.check(source);
        if (!source.isEnabled()) {
            throw new BusinessException(400, "运行时源 " + source.getId() + " 未启用: "
                    + (source.getDisabledReason() == null || source.getDisabledReason().isBlank()
                    ? "未配置访问地址" : source.getDisabledReason()));
        }
        if (dto.getComponentRole() != null && !dto.getComponentRole().isBlank()) {
            ComponentRole role;
            try {
                role = ComponentRole.from(dto.getComponentRole());
            } catch (IllegalArgumentException e) {
                throw new BusinessException(400, e.getMessage());
            }
            if (!source.supportsRole(role)) {
                throw new BusinessException(400, "运行时源 " + source.getId()
                        + " 不支持角色 " + role.wireName());
            }
        }
        return source;
    }

    private RuntimeQueryContext context(RuntimeSource source, RuntimeQueryDTO dto) {
        RuntimeQueryContext context = new RuntimeQueryContext();
        context.setSource(source);
        context.setJobId(dto.getJobId());
        context.setFamilies(parseFamilies(source, dto.getFamilies()));
        context.setExplicitMetrics(parseMetrics(dto.getMetrics()));
        context.setIncludeConfig(Boolean.TRUE.equals(dto.getIncludeConfig()));
        context.setIncludeComponents(dto.getIncludeComponents() == null || dto.getIncludeComponents());
        context.setIncludeVertices(dto.getIncludeVertices() == null || dto.getIncludeVertices());
        RuntimeProperties.Options options = source.getConfig().getOptions();
        context.setMaxComponents(options != null && options.getMaxComponents() > 0
                ? options.getMaxComponents() : properties.getQuery().getMaxComponents());
        context.setMaxVertices(options != null && options.getMaxVertices() > 0
                ? options.getMaxVertices() : properties.getQuery().getMaxVertices());
        context.setCheckpointHistory(options != null && options.getCheckpointHistoryLimit() > 0
                ? options.getCheckpointHistoryLimit() : properties.getQuery().getCheckpointHistoryLimit());
        context.setTimeoutMs(properties.getQuery().getTimeoutMs());
        context.setUser(com.bigdata.common.utils.SecurityUtils.getCurrentUsername());
        return context;
    }

    private List<MetricFamily> parseFamilies(RuntimeSource source, List<String> raw) {
        List<MetricFamily> families = new ArrayList<>();
        try {
            for (String value : raw == null ? List.<String>of() : raw) {
                if (value != null && !value.isBlank()) {
                    families.add(MetricFamily.from(value));
                }
            }
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, e.getMessage());
        }
        if (families.isEmpty()) {
            RuntimeProperties.Options options = source.getConfig().getOptions();
            if (options != null && options.getDefaultMetricFamilies() != null) {
                try {
                    for (String value : options.getDefaultMetricFamilies()) {
                        families.add(MetricFamily.from(value));
                    }
                } catch (IllegalArgumentException e) {
                    throw new BusinessException(400,
                            "runtime.sources[" + source.getId() + "].options.default-metric-families 配置非法: "
                                    + e.getMessage());
                }
            }
        }
        if (families.isEmpty()) {
            families.add(MetricFamily.JVM_GC);
            families.add(MetricFamily.THROUGHPUT);
        }
        return families;
    }

    private List<String> parseMetrics(List<String> raw) {
        List<String> metrics = new ArrayList<>();
        for (String value : raw == null ? List.<String>of() : raw) {
            if (value != null && !value.isBlank()) {
                metrics.add(value.trim());
            }
        }
        if (metrics.size() > properties.getQuery().getMaxMetricNames()) {
            throw new BusinessException(400, "显式指标名数量超过上限 "
                    + properties.getQuery().getMaxMetricNames());
        }
        return metrics;
    }

    // ------------------------------------------------------------------
    // 执行：信号量 + 熔断 + 超时 + 线程池
    // ------------------------------------------------------------------

    private <T> T call(RuntimeSource source, RuntimeAdapter adapter, Function<RuntimeAdapter, T> action) {
        Semaphore semaphore = semaphores.computeIfAbsent(source.getId(),
                id -> new Semaphore(Math.max(source.getConfig().getLimits().getMaxConcurrency(), 1), true));
        try {
            if (!semaphore.tryAcquire(300, TimeUnit.MILLISECONDS)) {
                throw new BusinessException(429,
                        "运行时源 " + source.getId() + " 并发已达上限，请稍后重试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "运行时查询被中断");
        }
        try {
            if (circuitBreaker.isOpen(source.getId())) {
                long retryAfter = circuitBreaker.retryAfterMs(source.getId());
                throw new CircuitOpenException("运行时源连续失败已熔断，约 "
                        + Math.max(retryAfter / 1000, 1) + " 秒后重试");
            }
            Future<T> future;
            try {
                future = executorRegistry.get(source.getId()).submit(() -> action.apply(adapter));
            } catch (RejectedExecutionException e) {
                throw new BusinessException(429, "运行时源线程池繁忙，请稍后重试");
            }
            try {
                T result = future.get(properties.getQuery().getTimeoutMs(), TimeUnit.MILLISECONDS);
                circuitBreaker.recordSuccess(source.getId());
                return result;
            } catch (TimeoutException e) {
                future.cancel(true);
                recordFailure(source);
                throw new RuntimeAdapterException(RuntimeAdapterException.Code.TIMEOUT,
                        "运行时查询超时（" + properties.getQuery().getTimeoutMs() + "ms）");
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new BusinessException(503, "运行时查询被中断");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof RuntimeAdapterException adapterException) {
                    if (adapterException.getCode() != RuntimeAdapterException.Code.BAD_REQUEST
                            && adapterException.getCode() != RuntimeAdapterException.Code.NOT_FOUND) {
                        recordFailure(source);
                    }
                    throw adapterException;
                }
                recordFailure(source);
                log.warn("运行时查询失败: source={}", source.getId(), cause);
                throw new RuntimeAdapterException(RuntimeAdapterException.Code.FAILED,
                        "运行时查询失败: " + cause.getMessage(), cause);
            }
        } finally {
            semaphore.release();
        }
    }

    private void recordFailure(RuntimeSource source) {
        RuntimeProperties.Source config = source.getConfig();
        int threshold = config.getLimits().getFailureThreshold();
        long openMs = config.getLimits().getOpenMs();
        circuitBreaker.recordFailure(source.getId(), threshold, openMs);
    }

    /** 配置/参数问题直接抛业务异常；权限同理 */
    private void handleFatal(RuntimeAdapterException e, String action) {
        if (e.getCode() == RuntimeAdapterException.Code.BAD_REQUEST) {
            throw new BusinessException(400, e.getMessage());
        }
        if (e.getCode() == RuntimeAdapterException.Code.NOT_FOUND) {
            throw new BusinessException(404, e.getMessage());
        }
        if (e.getCode() == RuntimeAdapterException.Code.UNSUPPORTED) {
            throw new BusinessException(400, e.getMessage());
        }
        log.debug("{}: {}", action, e.getMessage());
    }

    // ------------------------------------------------------------------
    // 降级响应
    // ------------------------------------------------------------------

    private RuntimeDiscoveryVO degradedDiscovery(RuntimeAdapterException e) {
        RuntimeDiscoveryVO vo = new RuntimeDiscoveryVO();
        vo.setDegraded(true);
        vo.setDegradationReason(e.getMessage());
        vo.setCapturedAt(System.currentTimeMillis());
        return vo;
    }

    private RuntimeComponentsVO baseComponents(RuntimeSource source, RuntimeQueryDTO dto) {
        RuntimeComponentsVO vo = new RuntimeComponentsVO();
        vo.setSourceId(source.getId());
        vo.setServiceType(source.getServiceType().wireName());
        vo.setCluster(source.getCluster());
        vo.setMode(source.getMode().wireName());
        vo.setJobId(dto.getJobId());
        return vo;
    }

    private RuntimeSnapshotVO degradedSnapshot(RuntimeSource source, RuntimeQueryDTO dto,
                                               RuntimeAdapterException e) {
        RuntimeSnapshotVO vo = new RuntimeSnapshotVO();
        vo.setSourceId(source.getId());
        vo.setServiceType(source.getServiceType().wireName());
        vo.setCluster(source.getCluster());
        vo.setMode(source.getMode().wireName());
        vo.setJobId(dto.getJobId());
        vo.setCapturedAt(System.currentTimeMillis());
        vo.setDegraded(true);
        vo.setDegradationReason(e.getMessage());
        vo.setErrorCode(errorCode(e));
        return vo;
    }

    private RuntimeConfigVO baseConfig(RuntimeSource source, RuntimeQueryDTO dto) {
        RuntimeConfigVO vo = new RuntimeConfigVO();
        vo.setSourceId(source.getId());
        vo.setServiceType(source.getServiceType().wireName());
        vo.setCluster(source.getCluster());
        vo.setMode(source.getMode().wireName());
        vo.setJobId(dto.getJobId());
        return vo;
    }

    private String errorCode(RuntimeAdapterException e) {
        if (e instanceof CircuitOpenException) {
            return "CIRCUIT_OPEN";
        }
        return switch (e.getCode()) {
            case NOT_FOUND -> "SOURCE_NOT_FOUND";
            case UNAVAILABLE -> "SOURCE_UNREACHABLE";
            case TIMEOUT -> "TIMEOUT";
            case UNSUPPORTED -> "UNSUPPORTED";
            default -> "FAILED";
        };
    }

    private void audit(RuntimeQueryDTO dto, RuntimeSource source, String action, long start,
                       boolean degraded, String errorCode) {
        auditService.record(dto, source, action, System.currentTimeMillis() - start, degraded, errorCode);
    }

    // ------------------------------------------------------------------
    // 组件过滤、脱敏、缓存键、对比
    // ------------------------------------------------------------------

    private List<RuntimeComponentVO> filterComponents(List<RuntimeComponentVO> components,
                                                      List<String> wanted) {
        if (components == null || components.isEmpty()) {
            return components == null ? new ArrayList<>() : components;
        }
        List<String> filters = new ArrayList<>();
        for (String value : wanted == null ? List.<String>of() : wanted) {
            if (value != null && !value.isBlank()) {
                filters.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (filters.isEmpty()) {
            return components;
        }
        List<RuntimeComponentVO> result = new ArrayList<>();
        for (RuntimeComponentVO component : components) {
            String id = component.getId() == null ? "" : component.getId().toLowerCase(Locale.ROOT);
            String name = component.getName() == null ? "" : component.getName().toLowerCase(Locale.ROOT);
            if (filters.contains(id) || filters.contains(name)) {
                result.add(component);
            }
        }
        return result;
    }

    private void maskConfig(RuntimeConfigVO vo) {
        Map<String, Map<String, String>> masked = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> section : vo.getSections().entrySet()) {
            masked.put(section.getKey(), maskValues(section.getValue()));
        }
        vo.setSections(masked);
    }

    private Map<String, String> maskValues(Map<String, String> values) {
        Map<String, String> result = new LinkedHashMap<>();
        if (values == null) {
            return result;
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key != null && SENSITIVE_KEY.matcher(key).matches()) {
                result.put(key, "******");
            } else {
                result.put(key, maskingService.mask(value));
            }
        }
        return result;
    }

    private Object maskObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (SENSITIVE_KEY.matcher(key).matches()) {
                    result.put(key, "******");
                } else {
                    result.put(key, maskObject(entry.getValue()));
                }
            }
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                result.add(maskObject(item));
            }
            return result;
        }
        if (value instanceof String text) {
            return maskingService.mask(text);
        }
        return value;
    }

    private Map<String, RuntimeMetricVO> flatten(RuntimeSnapshotVO snapshot) {
        Map<String, RuntimeMetricVO> result = new LinkedHashMap<>();
        for (RuntimeMetricVO metric : snapshot.getJobMetrics()) {
            result.put(metricKey(metric), metric);
        }
        for (RuntimeComponentVO component : snapshot.getComponents()) {
            for (RuntimeMetricVO metric : component.getMetrics()) {
                result.put(metricKey(metric), metric);
            }
        }
        return result;
    }

    private String metricKey(RuntimeMetricVO metric) {
        return (metric.getComponentType() == null ? "" : metric.getComponentType()) + '|'
                + (metric.getComponent() == null ? "" : metric.getComponent()) + '|'
                + (metric.getName() == null ? "" : metric.getName());
    }

    private String snapshotKey(RuntimeSource source, String jobId) {
        return source.getId() + '|' + (jobId == null ? "" : jobId);
    }

    private String cacheKey(String op, RuntimeSource source, RuntimeQueryDTO dto,
                            RuntimeQueryContext context) {
        StringBuilder builder = new StringBuilder(op).append('|')
                .append(source.getId()).append('|').append(source.getMode().wireName()).append('|')
                .append(dto.getJobId() == null ? "" : dto.getJobId()).append('|')
                .append(context.getFamilies().stream().map(MetricFamily::wireName)
                        .collect(Collectors.joining(","))).append('|')
                .append(String.join(",", context.getExplicitMetrics())).append('|')
                .append(context.isIncludeConfig()).append('|')
                .append(context.isIncludeComponents()).append('|')
                .append(context.isIncludeVertices()).append('|')
                .append(dto.getComponents() == null ? "" : String.join(",", dto.getComponents()));
        return builder.toString();
    }

    /** 熔断打开：不算上游失败，也不再打上游 */
    private static final class CircuitOpenException extends RuntimeAdapterException {
        private CircuitOpenException(String message) {
            super(Code.UNAVAILABLE, message);
        }
    }
}
