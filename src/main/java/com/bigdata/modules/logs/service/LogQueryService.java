package com.bigdata.modules.logs.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.utils.SecurityUtils;
import com.bigdata.modules.logs.adapter.AdapterResult;
import com.bigdata.modules.logs.adapter.BudgetLimiter;
import com.bigdata.modules.logs.adapter.LogAdapter;
import com.bigdata.modules.logs.adapter.LogAdapterException;
import com.bigdata.modules.logs.adapter.LogAdapterRegistry;
import com.bigdata.modules.logs.adapter.LogQueryContext;
import com.bigdata.modules.logs.config.LogAccessProperties;
import com.bigdata.modules.logs.dto.LogQueryDTO;
import com.bigdata.modules.logs.model.ComponentRole;
import com.bigdata.modules.logs.model.LogArtifact;
import com.bigdata.modules.logs.model.LogCursor;
import com.bigdata.modules.logs.model.LogKind;
import com.bigdata.modules.logs.model.LogSource;
import com.bigdata.modules.logs.vo.LogQueryResultVO;
import com.bigdata.modules.logs.vo.LogSourceVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 统一日志查询编排。
 *
 * <p>职责边界：参数校验 / RBAC / 源启用校验 / 限流 / 熔断 / 超时 / 脱敏 / 审计 / 指标，
 * 具体的「怎么取」全部交给适配器。上游不可达、超时、不支持都以降级结果返回，
 * 只有配置、参数与权限问题才抛业务异常。</p>
 */
@Service
public class LogQueryService {

    private static final Logger log = LoggerFactory.getLogger(LogQueryService.class);

    private final LogAccessProperties properties;
    private final LogSourceCatalog sourceCatalog;
    private final LogAdapterRegistry adapterRegistry;
    private final LogMaskingService maskingService;
    private final LogAccessControlService accessControlService;
    private final LogCircuitBreaker circuitBreaker;
    private final LogExecutorRegistry executorRegistry;
    private final LogCache cache;
    private final LogAuditService auditService;
    private final LogAccessMetrics metrics;

    /** 每个日志源一个公平信号量，限制对源服务的并发 */
    private final Map<String, Semaphore> semaphores = new ConcurrentHashMap<>();

    public LogQueryService(LogAccessProperties properties,
                           LogSourceCatalog sourceCatalog,
                           LogAdapterRegistry adapterRegistry,
                           LogMaskingService maskingService,
                           LogAccessControlService accessControlService,
                           LogCircuitBreaker circuitBreaker,
                           LogExecutorRegistry executorRegistry,
                           LogCache cache,
                           LogAuditService auditService,
                           LogAccessMetrics metrics) {
        this.properties = properties;
        this.sourceCatalog = sourceCatalog;
        this.adapterRegistry = adapterRegistry;
        this.maskingService = maskingService;
        this.accessControlService = accessControlService;
        this.circuitBreaker = circuitBreaker;
        this.executorRegistry = executorRegistry;
        this.cache = cache;
        this.auditService = auditService;
        this.metrics = metrics;
    }

    /** 对外暴露的日志源清单（按 RBAC 过滤，不返回地址与凭据） */
    public List<LogSourceVO> listSources() {
        List<LogSourceVO> result = new ArrayList<>();
        for (LogSource source : sourceCatalog.listAll()) {
            if (!accessControlService.canRead(source)) {
                continue;
            }
            if (source.getServiceType() == null) {
                continue;
            }
            LogSourceVO vo = new LogSourceVO();
            vo.setId(source.getId());
            vo.setName(source.getName());
            vo.setServiceType(source.getServiceType().wireName());
            vo.setCluster(source.getCluster());
            vo.setEnabled(source.isEnabled());
            vo.setDisabledReason(source.getDisabledReason());
            vo.setBaseUrlConfigured(source.getBaseUrl() != null && !source.getBaseUrl().isBlank());
            vo.setRoles(source.getRoles().stream().map(ComponentRole::wireName).collect(Collectors.toList()));
            vo.setLogKinds(source.getLogKinds().stream().map(LogKind::wireName).collect(Collectors.toList()));
            Map<String, String> unavailable = new java.util.LinkedHashMap<>();
            source.getLogKindIssues().forEach((kind, reason) -> unavailable.put(kind.wireName(), reason));
            vo.setUnavailableLogKinds(unavailable);
            result.add(vo);
        }
        return result;
    }

    /** 统一检索入口 */
    public LogQueryResultVO query(LogQueryDTO dto) {
        long start = System.currentTimeMillis();
        if (dto == null) {
            throw new BusinessException(400, "请求体不能为空");
        }
        if (!properties.isEnabled()) {
            throw new BusinessException(400, "日志访问层未启用（logs.enabled=false）");
        }
        LogKind kind = parseKind(dto.getLogKind());
        ComponentRole role = parseRole(dto.getComponentRole());
        LogSource source = sourceCatalog.resolve(dto.getSourceId(), dto.getServiceType(), dto.getCluster());
        accessControlService.check(source);
        ensureEnabled(source);
        if (!source.supportsRole(role)) {
            throw new BusinessException(400, "日志源 " + source.getId() + " 不支持角色 " + role.wireName());
        }
        if (!source.supportsLogKind(kind)) {
            throw new BusinessException(400, "日志源 " + source.getId() + " 不支持日志种类 " + kind.wireName());
        }
        String kindIssue = source.issueOf(kind);
        if (kindIssue != null) {
            throw new BusinessException(400, "日志源 " + source.getId() + " 的 "
                    + kind.wireName() + " 日志不可用: " + kindIssue);
        }

        int maxLines = clamp(dto.getLimit(), 1, properties.getQuery().getMaxLines());
        int maxBytes = clamp(dto.getMaxBytes(), 256, properties.getQuery().getMaxBytes());
        String keyword = normalizeKeyword(dto.getKeyword());
        validateTimeRange(dto.getTimeRange());
        LogCursor cursor = decodeCursor(dto.getCursor(), source.getId());
        long offset = cursor == null ? 0L : cursor.getOffset();

        LogQueryContext context = buildContext(source, kind, role, dto, offset, maxLines, maxBytes);
        String cacheKey = buildCacheKey(source, kind, role, dto, offset, maxLines, maxBytes);
        LogQueryResultVO cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        Semaphore semaphore = semaphore(source);
        int permits = Math.max(source.getConfig().getLimits().getMaxConcurrency(), 1);
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(300, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "日志查询被中断");
        }
        if (!acquired) {
            throw new BusinessException(429,
                    "日志源 " + source.getId() + " 并发已达上限(" + permits + ")，请稍后重试");
        }
        try {
            LogQueryResultVO result;
            if (circuitBreaker.isOpen(source.getId())) {
                long retryAfterMs = circuitBreaker.retryAfterMs(source.getId());
                result = buildDegraded(source, kind, role, "CIRCUIT_OPEN",
                        "日志源连续失败已熔断，约 " + Math.max(retryAfterMs / 1000, 1) + " 秒后重试", start);
            } else {
                result = execute(source, kind, role, context, offset, keyword, start);
            }
            if (!result.isDegraded()) {
                cache.put(cacheKey, result);
            }
            auditService.record(dto, result);
            metrics.record(source.getId(), kind, result.getElapsedMs(), result.getByteSize(),
                    result.isDegraded(), "FAILED".equals(result.getErrorCode()), SecurityUtils.getCurrentUsername());
            return result;
        } finally {
            semaphore.release();
        }
    }

    /** 列举日志产物（当前由 file 适配器支持） */
    public List<LogArtifact> listArtifacts(LogQueryDTO dto) {
        LogSource source = resolveForArtifacts(dto);
        LogQueryContext context = buildArtifactContext(source, dto);
        LogAdapter adapter = adapterRegistry.resolve(source.getServiceType(), LogKind.RUNTIME, ComponentRole.GENERIC);
        if (!adapter.supportsArtifacts()) {
            throw new BusinessException(400, "日志源 " + source.getId() + " 不支持列举产物");
        }
        return runArtifact(source, () -> adapter.listArtifacts(context));
    }

    /** 下载日志产物（大小受限，文本会先脱敏） */
    public byte[] fetchArtifact(LogQueryDTO dto, String artifactPath) {
        if (artifactPath == null || artifactPath.isBlank()) {
            throw new BusinessException(400, "缺少 artifactPath");
        }
        LogSource source = resolveForArtifacts(dto);
        LogQueryContext context = buildArtifactContext(source, dto);
        LogAdapter adapter = adapterRegistry.resolve(source.getServiceType(), LogKind.RUNTIME, ComponentRole.GENERIC);
        if (!adapter.supportsArtifacts()) {
            throw new BusinessException(400, "日志源 " + source.getId() + " 不支持下载产物");
        }
        byte[] bytes = runArtifact(source, () -> adapter.fetchArtifact(context, artifactPath));
        return maskIfText(bytes);
    }

    private LogQueryResultVO execute(LogSource source, LogKind kind, ComponentRole role,
                                     LogQueryContext context, long offset, String keyword, long start) {
        LogAdapter adapter;
        try {
            adapter = adapterRegistry.resolve(source.getServiceType(), kind, role);
        } catch (LogAdapterException e) {
            throw new BusinessException(400, e.getMessage());
        }

        AdapterResult adapterResult;
        Future<AdapterResult> future;
        try {
            future = executorRegistry.get(source.getId()).submit(() -> adapter.query(context));
        } catch (RejectedExecutionException e) {
            throw new BusinessException(429, "日志源线程池繁忙，请稍后重试");
        }
        try {
            adapterResult = future.get(properties.getQuery().getTimeoutMs(), TimeUnit.MILLISECONDS);
            circuitBreaker.recordSuccess(source.getId());
        } catch (TimeoutException e) {
            future.cancel(true);
            recordFailure(source);
            adapterResult = degradedAdapterResult("TIMEOUT",
                    "日志查询超时（" + properties.getQuery().getTimeoutMs() + "ms）");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "日志查询被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof LogAdapterException adapterException) {
                if (adapterException.getCode() == LogAdapterException.Code.BAD_REQUEST) {
                    throw new BusinessException(400, adapterException.getMessage());
                }
                recordFailure(source);
                adapterResult = degradedAdapterResult(errorCodeOf(adapterException.getCode()),
                        adapterException.getMessage());
            } else {
                recordFailure(source);
                log.warn("日志查询失败: source={}", source.getId(), cause);
                adapterResult = degradedAdapterResult("FAILED",
                        "日志查询失败: " + cause.getMessage());
            }
        }

        if (adapterResult.isDegraded()) {
            return buildDegraded(source, kind, role, adapterResult.getErrorCode(),
                    adapterResult.getMessage() == null
                            ? adapterResult.getDegradationReason() : adapterResult.getMessage(), start);
        }
        return assemble(source, kind, role, offset, adapterResult, keyword, start);
    }

    private LogQueryResultVO assemble(LogSource source, LogKind kind, ComponentRole role, long offset,
                                      AdapterResult adapterResult, String keyword, long start) {
        LogQueryResultVO vo = baseResult(source, kind, role);
        String masked = maskingService.mask(adapterResult.getContent());
        List<String> allLines = BudgetLimiter.splitLines(masked);
        List<String> visible = filterByKeyword(allLines, keyword);
        String content = String.join("\n", visible);

        vo.setContent(content);
        vo.setLines(visible);
        vo.setLineCount(visible.size());
        vo.setByteSize(content.getBytes(StandardCharsets.UTF_8).length);
        vo.setTruncated(adapterResult.isTruncated());
        vo.setHasMore(adapterResult.isHasMore());
        vo.setResolvedHost(adapterResult.getResolvedHost());
        vo.setResolvedPath(adapterResult.getResolvedPath());
        if (adapterResult.isHasMore()) {
            long nextOffset = adapterResult.getNextOffset();
            if (nextOffset > offset) {
                vo.setNextCursor(new LogCursor(source.getId(), nextOffset, null).encode());
            } else {
                vo.setHasMore(false);
                vo.setMessage("上游未提供有效游标推进，已停止翻页");
            }
        }
        vo.setElapsedMs(System.currentTimeMillis() - start);
        return vo;
    }

    private LogQueryResultVO buildDegraded(LogSource source, LogKind kind, ComponentRole role,
                                           String errorCode, String message, long start) {
        LogQueryResultVO vo = baseResult(source, kind, role);
        vo.setDegraded(true);
        vo.setErrorCode(errorCode);
        vo.setMessage(message);
        vo.setDegradationReason(message);
        vo.setElapsedMs(System.currentTimeMillis() - start);
        return vo;
    }

    private LogQueryResultVO baseResult(LogSource source, LogKind kind, ComponentRole role) {
        LogQueryResultVO vo = new LogQueryResultVO();
        vo.setSourceId(source.getId());
        vo.setServiceType(source.getServiceType().wireName());
        vo.setCluster(source.getCluster());
        vo.setComponentRole(role.wireName());
        vo.setLogKind(kind.wireName());
        return vo;
    }

    private AdapterResult degradedAdapterResult(String errorCode, String message) {
        return AdapterResult.degraded(errorCode, message);
    }

    private LogQueryContext buildContext(LogSource source, LogKind kind, ComponentRole role,
                                         LogQueryDTO dto, long offset, int maxLines, int maxBytes) {
        LogQueryContext context = new LogQueryContext();
        context.setSource(source);
        context.setLogKind(kind);
        context.setRole(role);
        context.setRef(dto.getRef());
        context.setKeyword(dto.getKeyword());
        context.setOffset(offset);
        context.setMaxLines(maxLines);
        context.setMaxBytes(maxBytes);
        context.setTimeoutMs(properties.getQuery().getTimeoutMs());
        context.setUser(SecurityUtils.getCurrentUsername());
        if (dto.getTimeRange() != null) {
            context.setStartTime(dto.getTimeRange().getStart());
            context.setEndTime(dto.getTimeRange().getEnd());
        }
        return context;
    }

    private LogQueryContext buildArtifactContext(LogSource source, LogQueryDTO dto) {
        LogQueryContext context = new LogQueryContext();
        context.setSource(source);
        context.setLogKind(LogKind.RUNTIME);
        context.setRole(ComponentRole.GENERIC);
        context.setRef(dto == null ? null : dto.getRef());
        context.setOffset(0L);
        context.setMaxLines(properties.getQuery().getMaxLines());
        context.setMaxBytes(properties.getQuery().getMaxBytes());
        context.setTimeoutMs(properties.getQuery().getTimeoutMs());
        context.setUser(SecurityUtils.getCurrentUsername());
        return context;
    }

    private LogSource resolveForArtifacts(LogQueryDTO dto) {
        if (dto == null) {
            throw new BusinessException(400, "请求参数不能为空");
        }
        if (!properties.isEnabled()) {
            throw new BusinessException(400, "日志访问层未启用（logs.enabled=false）");
        }
        LogSource source = sourceCatalog.resolve(dto.getSourceId(), dto.getServiceType(), dto.getCluster());
        accessControlService.check(source);
        ensureEnabled(source);
        return source;
    }

    private <T> T runArtifact(LogSource source, Supplier<T> supplier) {
        Future<T> future;
        try {
            future = executorRegistry.get(source.getId()).submit(supplier::get);
        } catch (RejectedExecutionException e) {
            throw new BusinessException(429, "日志源线程池繁忙，请稍后重试");
        }
        try {
            return future.get(properties.getQuery().getTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new BusinessException(504, "日志产物操作超时（" + properties.getQuery().getTimeoutMs() + "ms）");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "日志产物操作被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof LogAdapterException adapterException) {
                int code = adapterException.getCode() == LogAdapterException.Code.BAD_REQUEST ? 400 : 503;
                throw new BusinessException(code, adapterException.getMessage());
            }
            throw new BusinessException(503, "日志产物操作失败: " + cause.getMessage());
        }
    }

    private byte[] maskIfText(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return bytes;
        }
        int probe = Math.min(bytes.length, 512);
        for (int i = 0; i < probe; i++) {
            if (bytes[i] == 0) {
                return bytes;
            }
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        String masked = maskingService.mask(text);
        return masked.equals(text) ? bytes : masked.getBytes(StandardCharsets.UTF_8);
    }

    private void ensureEnabled(LogSource source) {
        if (!source.isEnabled()) {
            throw new BusinessException(400,
                    "日志源 " + source.getId() + " 未启用: "
                            + (source.getDisabledReason() == null || source.getDisabledReason().isBlank()
                            ? "未配置访问地址" : source.getDisabledReason()));
        }
    }

    private Semaphore semaphore(LogSource source) {
        return semaphores.computeIfAbsent(source.getId(),
                id -> new Semaphore(Math.max(source.getConfig().getLimits().getMaxConcurrency(), 1), true));
    }

    private void recordFailure(LogSource source) {
        LogAccessProperties.Limits limits = source.getConfig().getLimits();
        circuitBreaker.recordFailure(source.getId(), limits.getFailureThreshold(), limits.getOpenMs());
    }

    private String errorCodeOf(LogAdapterException.Code code) {
        return switch (code) {
            case NOT_FOUND -> "SOURCE_NOT_FOUND";
            case UNAVAILABLE -> "SOURCE_UNREACHABLE";
            case TIMEOUT -> "TIMEOUT";
            case UNSUPPORTED -> "UNSUPPORTED";
            default -> "FAILED";
        };
    }

    private List<String> filterByKeyword(List<String> lines, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return lines;
        }
        String needle = keyword.trim().toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            if (line != null && line.toLowerCase(Locale.ROOT).contains(needle)) {
                result.add(line);
            }
        }
        return result;
    }

    private LogKind parseKind(String value) {
        try {
            return LogKind.from(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, e.getMessage());
        }
    }

    private ComponentRole parseRole(String value) {
        try {
            return ComponentRole.from(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, e.getMessage());
        }
    }

    private LogCursor decodeCursor(String raw, String sourceId) {
        LogCursor cursor;
        try {
            cursor = LogCursor.decode(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, e.getMessage());
        }
        if (cursor != null && !cursor.getSourceId().equalsIgnoreCase(sourceId)) {
            throw new BusinessException(400, "游标与日志源不匹配，请从第一页重新查询");
        }
        return cursor;
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        int max = properties.getQuery().getMaxKeywordLength();
        if (trimmed.length() > max) {
            throw new BusinessException(400, "关键字长度超过上限 " + max);
        }
        return trimmed;
    }

    private void validateTimeRange(LogQueryDTO.TimeRange timeRange) {
        if (timeRange == null) {
            return;
        }
        LocalDateTime start = timeRange.getStart();
        LocalDateTime end = timeRange.getEnd();
        if (start != null && end != null && start.isAfter(end)) {
            throw new BusinessException(400, "timeRange.start 不能晚于 timeRange.end");
        }
    }

    private int clamp(Integer value, int min, int max) {
        int effective = value == null ? max : value;
        return Math.max(min, Math.min(effective, Math.max(max, min)));
    }

    private String buildCacheKey(LogSource source, LogKind kind, ComponentRole role, LogQueryDTO dto,
                                 long offset, int maxLines, int maxBytes) {
        StringBuilder builder = new StringBuilder();
        builder.append(source.getId()).append('|').append(kind.wireName()).append('|')
                .append(role.wireName()).append('|').append(offset).append('|')
                .append(maxLines).append('|').append(maxBytes).append('|')
                .append(dto.getKeyword() == null ? "" : dto.getKeyword());
        if (dto.getTimeRange() != null) {
            builder.append('|').append(dto.getTimeRange().getStart()).append('~')
                    .append(dto.getTimeRange().getEnd());
        }
        if (dto.getRef() != null && !dto.getRef().isEmpty()) {
            Map<String, String> sorted = new TreeMap<>(dto.getRef());
            sorted.forEach((key, value) -> builder.append('|').append(key).append('=').append(value));
        }
        return builder.toString();
    }
}
