package com.bigdata.modules.telemetry.service.metadata;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.telemetry.service.catalog.ServiceIdentityCatalog;
import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.Requirement;
import com.bigdata.modules.telemetry.common.model.RequirementKind;
import com.bigdata.modules.telemetry.config.TelemetryMetadataProperties;
import com.bigdata.modules.telemetry.dto.MetadataQueryRequest;
import com.bigdata.modules.telemetry.vo.ConfigEntryView;
import com.bigdata.modules.telemetry.vo.MetadataData;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataScope;
import com.bigdata.modules.telemetry.dto.MetadataTarget;
import com.bigdata.modules.telemetry.vo.MetricNameView;
import com.bigdata.modules.telemetry.service.metadata.model.NormalizedVersion;
import com.bigdata.modules.telemetry.vo.TargetAttempt;
import com.bigdata.modules.telemetry.vo.TargetView;
import com.bigdata.modules.telemetry.vo.VersionView;
import com.bigdata.modules.telemetry.service.metricdata.model.DiscoveredMetric;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDiscoveryResult;
import com.bigdata.modules.telemetry.service.metricdata.discovery.MetricDiscoveryService;
import com.bigdata.modules.telemetry.service.metadata.registry.ServiceMetadataProviderRegistry;
import com.bigdata.modules.telemetry.service.metadata.registry.TargetDiscoveryProviderRegistry;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataCapability;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataFetchResult;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataQuery;
import com.bigdata.modules.telemetry.service.metadata.model.RawConfigEntry;
import com.bigdata.modules.telemetry.service.metadata.model.RawTarget;
import com.bigdata.modules.telemetry.service.metadata.model.RawVersion;
import com.bigdata.modules.telemetry.service.metadata.spi.ServiceMetadataProvider;
import com.bigdata.modules.telemetry.service.metadata.spi.TargetDiscoveryProvider;
import com.bigdata.modules.telemetry.service.metadata.model.TargetDiscoveryQuery;
import com.bigdata.modules.telemetry.service.metadata.support.MetadataMasker;
import com.bigdata.modules.telemetry.service.metadata.support.VersionNormalizer;
import com.bigdata.modules.telemetry.vo.MetadataQueryResponse;
import com.bigdata.modules.telemetry.common.model.BackendWarning;
import com.bigdata.modules.telemetry.common.model.Target;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 服务版本与配置查询编排：校验 → 按 target 选 provider → 取数 → 标准化 / 脱敏 / 限流 → 四态聚合。
 *
 * <p>聚合规则：</p>
 * <ul>
 *   <li>全部 target 成功 → {@code ok}；部分成功 → {@code ok + meta.partial=true}；</li>
 *   <li>全部失败且都是可用性 / 配置类 → {@code unavailable}；</li>
 *   <li>全部失败且含参数 / 权限 / 解析类 → {@code failed}，code 取第一条非可用性失败；</li>
 *   <li>没有任何 provider 匹配 → {@code not_implemented} + IMPLEMENTATION 补齐项。</li>
 * </ul>
 *
 * <p>同一个 target 的版本与配置只取第一个成功的 provider，不合并多 provider 结果。</p>
 */
@Service
public class MetadataTelemetryQueryService {

    private static final String PROVIDERS_CONFIG_KEY = "telemetry.metadata.providers";

    private final ServiceMetadataProviderRegistry registry;
    private final TargetDiscoveryProviderRegistry targetRegistry;
    private final MetadataMasker masker;
    private final VersionNormalizer versionNormalizer;
    private final TelemetryMetadataProperties properties;
    private final MetricDiscoveryService metricDiscoveryService;

    public MetadataTelemetryQueryService(ServiceMetadataProviderRegistry registry,
                                         TargetDiscoveryProviderRegistry targetRegistry,
                                         MetadataMasker masker,
                                         VersionNormalizer versionNormalizer,
                                         TelemetryMetadataProperties properties,
                                         MetricDiscoveryService metricDiscoveryService) {
        this.registry = registry;
        this.targetRegistry = targetRegistry;
        this.masker = masker;
        this.versionNormalizer = versionNormalizer;
        this.properties = properties;
        this.metricDiscoveryService = metricDiscoveryService;
    }

    public MetadataQueryResponse query(MetadataQueryRequest request) {
        long start = System.currentTimeMillis();
        Set<MetadataScope> scopes = requireScope(request.getScope());
        Duration timeout = Duration.ofMillis(Math.max(properties.getQuery().getTimeoutMs(), 1));
        long capturedAt = System.currentTimeMillis();

        if (scopes.size() == 1 && scopes.contains(MetadataScope.TARGETS)) {
            return queryTargets(request, timeout, capturedAt, start);
        }

        List<MetadataTarget> targets = requireTargets(request);
        int maxConfigItems = properties.getQuery().getMaxConfigItems();
        int limit = request.getLimit() == null ? maxConfigItems : request.getLimit();
        if (limit < 1 || limit > maxConfigItems) {
            throw new BusinessException(400, "limit 必须在 1.." + maxConfigItems);
        }
        List<String> sections = request.getSections() == null ? List.of() : request.getSections();
        List<String> keys = request.getKeys() == null ? List.of() : request.getKeys();

        if (scopes.size() == 1 && scopes.contains(MetadataScope.METRICS)) {
            return queryMetrics(targets, request, timeout, capturedAt, start);
        }

        List<VersionView> versions = new ArrayList<>();
        List<ConfigEntryView> configs = new ArrayList<>();
        List<TargetAttempt> attempts = new ArrayList<>();
        List<MetadataQueryResponse.Selected> selected = new ArrayList<>();
        List<MetadataQueryResponse.Skip> skipped = new ArrayList<>();
        List<BackendWarning> warnings = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        boolean truncated = false;
        int success = 0;
        int noProvider = 0;

        for (MetadataTarget target : targets) {
            TargetOutcome result = queryTarget(target, scopes, sections, keys, limit, timeout,
                    capturedAt);
            attempts.addAll(result.attempts());
            skipped.addAll(result.skipped());
            warnings.addAll(result.warnings());
            if (result.success()) {
                success++;
                versions.addAll(result.versions());
                configs.addAll(result.configs());
                selected.add(result.selected());
                truncated |= result.truncated();
            } else if (result.noProvider()) {
                noProvider++;
            } else {
                failures.add(result.failure());
            }
        }

        boolean partial = success > 0 && success < targets.size();
        if (partial) {
            warnings.add(BackendWarning.of("PARTIAL",
                    "部分 target 失败，失败原因见 providers.attempts[]"));
        }

        String outcome;
        CapabilityCode code = CapabilityCode.OK;
        String message = null;
        List<Requirement> requirements = List.of();
        boolean retryable = false;

        if (success > 0) {
            outcome = "ok";
        } else if (!failures.isEmpty()) {
            boolean allAvailability = failures.stream().allMatch(Failure::availability);
            Failure first = allAvailability ? failures.get(0) : firstNonAvailability(failures);
            code = first.code();
            message = first.reason();
            requirements = first.requirements();
            retryable = allAvailability && failures.stream().allMatch(Failure::retryable);
            outcome = allAvailability ? "unavailable" : "failed";
        } else {
            outcome = "not_implemented";
            code = CapabilityCode.NO_PROVIDER;
            message = "没有 provider 支持请求的服务 / 组件 / 范围（targets=" + noProvider + "）";
            requirements = List.of(new Requirement(RequirementKind.IMPLEMENTATION,
                    "ServiceMetadataProvider",
                    "实现并注册 ServiceMetadataProvider，或启用内置 provider"));
        }

        MetadataData data = "ok".equals(outcome)
                ? new MetadataData(MetadataScope.wire(scopes), capturedAt, versions, configs, warnings)
                : null;
        MetadataQueryResponse.ProviderView providers = new MetadataQueryResponse.ProviderView(
                selected, attempts, skipped);
        MetadataQueryResponse.ErrorView error = "ok".equals(outcome) ? null
                : new MetadataQueryResponse.ErrorView(code, message, requirements);
        MetadataQueryResponse.MetaView meta = new MetadataQueryResponse.MetaView(
                System.currentTimeMillis() - start, partial, false, warnings,
                Instant.now().toString(), truncated);
        return new MetadataQueryResponse(outcome, retryable, targets,
                MetadataScope.wire(scopes), providers, data, error, meta);
    }

    // ------------------------------------------------------------------ 指标名发现

    private MetadataQueryResponse queryMetrics(List<MetadataTarget> targets,
                                                     MetadataQueryRequest request,
                                                     Duration timeout, long capturedAt,
                                                     long start) {
        int maxMetrics = properties.getQuery().getMaxMetrics();
        int limit = request.getLimit() == null ? maxMetrics : request.getLimit();
        if (limit < 1 || limit > maxMetrics) {
            throw new BusinessException(400, "limit 必须在 1.." + maxMetrics);
        }

        List<MetricNameView> metrics = new ArrayList<>();
        List<TargetAttempt> attempts = new ArrayList<>();
        List<MetadataQueryResponse.Selected> selected = new ArrayList<>();
        List<MetadataQueryResponse.Skip> skipped = new ArrayList<>();
        List<BackendWarning> warnings = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        int success = 0;
        boolean truncated = false;

        for (MetadataTarget target : targets) {
            long began = System.currentTimeMillis();
            MetricDiscoveryResult result = metricDiscoveryService.discover(
                    target.target(), target.ref(), timeout, limit);
            long elapsed = System.currentTimeMillis() - began;
            warnings.addAll(result.warnings());
            if (result.supported()) {
                success++;
                for (DiscoveredMetric metric : result.metrics()) {
                    metrics.add(new MetricNameView(target, metric.providerId(), metric.name(),
                            metric.source(), metric.type(), metric.unit(), result.manual()));
                }
                selected.add(new MetadataQueryResponse.Selected(target, result.providerId(),
                        result.manual() ? "manual" : "metric-discovery", 0));
                attempts.add(TargetAttempt.success(target, result.providerId(), elapsed));
                if (result.metrics().isEmpty()) {
                    warnings.add(BackendWarning.of("NO_METRICS_DISCOVERED",
                            target.target().serviceType() + "/" + target.target().componentId()
                                    + " 的发现结果为空"));
                }
                if (result.metrics().size() >= limit) {
                    truncated = true;
                    warnings.add(BackendWarning.of("RESULT_TRUNCATED",
                            "指标名发现条数达到上限 " + limit));
                }
            } else {
                CapabilityCode code = result.code() == null
                        ? CapabilityCode.METRIC_DISCOVERY_UNSUPPORTED : result.code();
                String reason = result.reason() == null ? "指标名发现不可用" : result.reason();
                attempts.add(new TargetAttempt(target, result.providerId(), outcomeOf(code), code,
                        elapsed, code.retryable(), safeMessage(reason)));
                failures.add(new Failure(target, code, reason, result.requirements(),
                        isAvailability(code), code.retryable()));
            }
        }

        boolean partial = success > 0 && success < targets.size();
        if (partial) {
            warnings.add(BackendWarning.of("PARTIAL",
                    "部分 target 的指标名发现失败，失败原因见 providers.attempts[]"));
        }

        String outcome;
        CapabilityCode code = CapabilityCode.OK;
        String message = null;
        List<Requirement> requirements = List.of();
        boolean retryable = false;
        if (success > 0) {
            outcome = "ok";
        } else if (!failures.isEmpty()) {
            boolean allAvailability = failures.stream().allMatch(Failure::availability);
            Failure first = allAvailability ? failures.get(0) : firstNonAvailability(failures);
            code = first.code();
            message = first.reason();
            requirements = first.requirements();
            retryable = allAvailability && failures.stream().allMatch(Failure::retryable);
            outcome = allAvailability ? "unavailable" : "failed";
        } else {
            outcome = "not_implemented";
            code = CapabilityCode.METRIC_DISCOVERY_UNSUPPORTED;
            message = "没有可用的指标名发现 provider";
            requirements = List.of(new Requirement(RequirementKind.IMPLEMENTATION,
                    "MetricDiscoveryProvider", "实现并注册指标发现 provider"));
        }

        MetadataData data = "ok".equals(outcome)
                ? new MetadataData("metrics", capturedAt, List.of(), List.of(), warnings, metrics)
                : null;
        MetadataQueryResponse.ProviderView providers = new MetadataQueryResponse.ProviderView(
                selected, attempts, skipped);
        MetadataQueryResponse.ErrorView error = "ok".equals(outcome) ? null
                : new MetadataQueryResponse.ErrorView(code, message, requirements);
        MetadataQueryResponse.MetaView meta = new MetadataQueryResponse.MetaView(
                System.currentTimeMillis() - start, partial, false, warnings,
                Instant.now().toString(), truncated);
        return new MetadataQueryResponse(outcome, retryable, targets, "metrics", providers,
                data, error, meta);
    }

    // ------------------------------------------------------------------ 作业目标发现

    private MetadataQueryResponse queryTargets(MetadataQueryRequest request,
                                                     Duration timeout, long capturedAt,
                                                     long start) {
        int maxTargets = properties.getQuery().getMaxTargets();
        int limit = request.getLimit() == null ? maxTargets : request.getLimit();
        if (limit < 1 || limit > maxTargets) {
            throw new BusinessException(400, "limit 必须在 1.." + maxTargets);
        }
        List<MetadataTarget> filters = targetFilters(request.getTargets());
        List<TargetDiscoveryProvider> candidates = targetCandidates(filters);

        List<TargetAttempt> attempts = new ArrayList<>();
        List<MetadataQueryResponse.Selected> selected = new ArrayList<>();
        List<MetadataQueryResponse.Skip> skipped = new ArrayList<>();
        List<BackendWarning> warnings = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        List<TargetView> matched = new ArrayList<>();
        int success = 0;
        int configured = 0;

        // limit 只在这里用：provider 返回全部匹配，截断与 truncated 标记由本方法统一处理
        TargetDiscoveryQuery discovery = new TargetDiscoveryQuery(
                request.getTaskName(), request.getMatchMode(), timeout);

        for (TargetDiscoveryProvider provider : candidates) {
            MetadataTarget providerTarget = providerTarget(provider, filters);
            if (!provider.enabled()) {
                skipped.add(new MetadataQueryResponse.Skip(providerTarget,
                        provider.providerId(), "未启用"));
                continue;
            }
            configured++;
            long began = System.currentTimeMillis();
            try {
                List<RawTarget> rawTargets = provider.discover(discovery);
                long elapsed = System.currentTimeMillis() - began;
                success++;
                attempts.add(TargetAttempt.success(providerTarget, provider.providerId(), elapsed));
                selected.add(new MetadataQueryResponse.Selected(providerTarget,
                        provider.providerId(), "target-discovery", provider.order()));
                for (RawTarget raw : rawTargets) {
                    matched.add(new TargetView(raw.serviceType(), raw.componentId(), raw.ref(),
                            raw.name(), raw.state()));
                }
            } catch (BackendException e) {
                long elapsed = System.currentTimeMillis() - began;
                attempts.add(new TargetAttempt(providerTarget, provider.providerId(),
                        outcomeOf(e.getCode()), e.getCode(), elapsed, e.retryable(),
                        safeMessage(e.getMessage())));
                failures.add(new Failure(providerTarget, e.getCode(), e.getMessage(),
                        e.getRequirements(), isAvailability(e.getCode()), e.retryable()));
            } catch (RuntimeException e) {
                long elapsed = System.currentTimeMillis() - began;
                String reason = "provider 内部错误: " + e.getClass().getSimpleName();
                attempts.add(new TargetAttempt(providerTarget, provider.providerId(),
                        TargetAttempt.FAILED, CapabilityCode.PARSE_ERROR, elapsed, false, reason));
                failures.add(new Failure(providerTarget, CapabilityCode.PARSE_ERROR, reason,
                        List.of(), false, false));
            }
        }

        String taskName = request.getTaskName() == null ? null : request.getTaskName().trim();
        boolean fuzzy = false;
        if (taskName != null && !taskName.isBlank()) {
            List<TargetView> exact = new ArrayList<>();
            for (TargetView target : matched) {
                if (taskName.equals(target.name())) {
                    exact.add(target);
                }
            }
            if (!exact.isEmpty()) {
                matched = exact;
            } else {
                fuzzy = !matched.isEmpty();
            }
        }
        matched = dedupe(matched);
        int totalMatched = matched.size();
        boolean nameQuery = taskName != null && !taskName.isBlank();
        // 歧义判定必须在截断之前：limit 小于命中数时截断会让「多条同名」看起来像唯一命中，
        // 变成静默挑第一条。歧义路径不截断候选，只在消息里列前 5 条。
        boolean ambiguous = nameQuery && totalMatched > 1;
        boolean truncated = !ambiguous && matched.size() > limit;
        if (truncated) {
            matched = new ArrayList<>(matched.subList(0, limit));
        }
        if (fuzzy && matched.size() == 1) {
            warnings.add(BackendWarning.of("FUZZY_MATCH",
                    "任务名未精确命中，按模糊匹配返回唯一候选 " + matched.get(0).name()));
        }

        String outcome;
        CapabilityCode code = CapabilityCode.OK;
        String message = null;
        List<Requirement> requirements = List.of();
        boolean retryable = false;
        if (candidates.isEmpty()) {
            outcome = "not_implemented";
            code = CapabilityCode.NO_PROVIDER;
            message = "没有注册目标发现 provider";
            requirements = List.of(new Requirement(RequirementKind.IMPLEMENTATION,
                    "TargetDiscoveryProvider", "实现并注册 TargetDiscoveryProvider"));
        } else if (ambiguous) {
            outcome = "failed";
            code = CapabilityCode.AMBIGUOUS_REFERENCE;
            message = "任务名「" + taskName + "」命中 " + totalMatched
                    + " 条作业，拒绝猜测：" + candidatesText(matched);
            requirements = List.of(new Requirement(RequirementKind.TARGET, "taskName / ref",
                    "使用更精确的任务名，或直接提供 applicationId / jobId"));
        } else if (!matched.isEmpty()) {
            outcome = "ok";
        } else if (configured == 0) {
            outcome = "unavailable";
            code = CapabilityCode.NOT_CONFIGURED;
            message = "没有启用的目标发现 provider";
            requirements = List.of(new Requirement(RequirementKind.CONFIG,
                    "telemetry.metadata.providers.spark-shs.enabled / telemetry.backends.flink-rest[].base-url",
                    "启用并配置 Spark SHS 或 Flink REST，再重试目标发现"));
        } else if (!failures.isEmpty()) {
            boolean allAvailability = failures.stream().allMatch(Failure::availability);
            Failure first = allAvailability ? failures.get(0) : firstNonAvailability(failures);
            code = first.code();
            message = first.reason();
            requirements = first.requirements();
            retryable = allAvailability && failures.stream().allMatch(Failure::retryable);
            outcome = allAvailability ? "unavailable" : "failed";
        } else {
            outcome = "unavailable";
            code = CapabilityCode.NOT_FOUND;
            message = taskName == null || taskName.isBlank()
                    ? "目标发现返回空清单" : "没有匹配任务名「" + taskName + "」的作业";
            requirements = List.of(new Requirement(RequirementKind.TARGET, "taskName",
                    "确认任务名；Spark SHS 只保留历史应用，Flink 只列当前集群作业"));
        }

        boolean partial = success > 0 && success < configured;
        if (partial) {
            warnings.add(BackendWarning.of("PARTIAL",
                    "部分目标发现 provider 失败，失败原因见 providers.attempts[]"));
        }
        if (truncated) {
            warnings.add(BackendWarning.of("RESULT_TRUNCATED",
                    "目标清单达到上限 " + limit));
        }

        MetadataData data = "ok".equals(outcome)
                ? new MetadataData("targets", capturedAt, List.of(), List.of(), warnings,
                List.of(), matched)
                : null;
        MetadataQueryResponse.ProviderView providers = new MetadataQueryResponse.ProviderView(
                selected, attempts, skipped);
        MetadataQueryResponse.ErrorView error = "ok".equals(outcome) ? null
                : new MetadataQueryResponse.ErrorView(code, message, requirements);
        MetadataQueryResponse.MetaView meta = new MetadataQueryResponse.MetaView(
                System.currentTimeMillis() - start, partial, false, warnings,
                Instant.now().toString(), truncated);
        return new MetadataQueryResponse(outcome, retryable, filters, "targets",
                providers, data, error, meta);
    }

    private List<MetadataTarget> targetFilters(List<MetadataTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        List<MetadataTarget> normalized = new ArrayList<>();
        for (MetadataTarget item : targets) {
            if (item == null || item.target() == null) {
                throw new BusinessException(400, "targets[].target 必填");
            }
            Target target = item.target();
            requireText(target.serviceType(), "targets[].target.serviceType");
            requireText(target.componentId(), "targets[].target.componentId");
            if (!ServiceIdentityCatalog.components().containsKey(target.serviceType())) {
                throw new BusinessException(400, "未知服务类型: " + target.serviceType());
            }
            if (!ServiceIdentityCatalog.supports(target.serviceType(), target.componentId())) {
                throw new BusinessException(400, "组件不属于该服务: " + target.componentId());
            }
            String hostId = target.hostId() == null || target.hostId().isBlank()
                    ? Target.CLUSTER_HOST : target.hostId();
            normalized.add(new MetadataTarget(
                    new Target(target.serviceType(), target.componentId(), hostId), item.ref()));
        }
        return normalized;
    }

    private List<TargetDiscoveryProvider> targetCandidates(List<MetadataTarget> filters) {
        if (filters.isEmpty()) {
            return targetRegistry.providers();
        }
        List<TargetDiscoveryProvider> result = new ArrayList<>();
        for (MetadataTarget filter : filters) {
            for (TargetDiscoveryProvider provider : targetRegistry.candidates(filter.target())) {
                if (!result.contains(provider)) {
                    result.add(provider);
                }
            }
        }
        return result;
    }

    private MetadataTarget providerTarget(TargetDiscoveryProvider provider,
                                          List<MetadataTarget> filters) {
        if (!filters.isEmpty()) {
            for (MetadataTarget filter : filters) {
                if (provider.serviceTypes().contains(filter.target().serviceType())) {
                    return filter;
                }
            }
        }
        String serviceType = provider.serviceTypes().iterator().next();
        String componentId = provider.componentIds().isEmpty()
                ? Target.CLUSTER_HOST : provider.componentIds().iterator().next();
        return new MetadataTarget(new Target(serviceType, componentId, Target.CLUSTER_HOST),
                Map.of());
    }

    private static List<TargetView> dedupe(List<TargetView> targets) {
        Map<String, TargetView> unique = new java.util.LinkedHashMap<>();
        for (TargetView target : targets) {
            String ref = target.ref().getOrDefault("applicationId",
                    target.ref().getOrDefault("jobId", ""));
            unique.putIfAbsent(target.serviceType() + "|" + target.componentId() + "|"
                    + ref + "|" + target.name(), target);
        }
        return new ArrayList<>(unique.values());
    }

    /** 歧义消息里最多列 5 条，其余只报总数，避免模糊匹配把消息撑爆。 */
    private static String candidatesText(List<TargetView> targets) {
        List<String> names = new ArrayList<>();
        for (TargetView target : targets.subList(0, Math.min(targets.size(), 5))) {
            String ref = target.ref().getOrDefault("applicationId",
                    target.ref().getOrDefault("jobId", ""));
            names.add(target.serviceType() + ":" + target.name() + "(" + ref + ")");
        }
        String text = String.join("; ", names);
        return targets.size() > 5 ? text + " 等 " + targets.size() + " 条" : text;
    }

    // ------------------------------------------------------------------ 单 target

    private TargetOutcome queryTarget(MetadataTarget target, Set<MetadataScope> scopes,
                                      List<String> sections, List<String> keys, int limit,
                                      Duration timeout, long capturedAt) {
        List<ServiceMetadataProvider> candidates = registry.candidates(target.target(), scopes);
        if (candidates.isEmpty()) {
            return TargetOutcome.noProvider(target);
        }

        List<TargetAttempt> attempts = new ArrayList<>();
        List<MetadataQueryResponse.Skip> skipped = new ArrayList<>();
        List<BackendWarning> warnings = new ArrayList<>();
        Failure lastFailure = null;

        for (ServiceMetadataProvider provider : candidates) {
            MetadataCapability capability = provider.capability();
            if (!provider.enabled()) {
                skipped.add(new MetadataQueryResponse.Skip(target, capability.providerId(),
                        "未启用"));
                continue;
            }
            long began = System.currentTimeMillis();
            try {
                MetadataFetchResult fetched = provider.fetch(new MetadataQuery(
                        target, scopes, sections, keys, limit, timeout));
                long elapsed = System.currentTimeMillis() - began;
                attempts.add(TargetAttempt.success(target, capability.providerId(), elapsed));
                return buildSuccess(target, capability, fetched, scopes, sections, keys, limit,
                        capturedAt, attempts, skipped, warnings);
            } catch (BackendException e) {
                long elapsed = System.currentTimeMillis() - began;
                CapabilityCode code = e.getCode();
                attempts.add(new TargetAttempt(target, capability.providerId(),
                        outcomeOf(code), code, elapsed, e.retryable(), safeMessage(e.getMessage())));
                lastFailure = new Failure(target, code, e.getMessage(), e.getRequirements(),
                        isAvailability(code), e.retryable());
                if (!canFallback(code, capability)) {
                    break;
                }
            } catch (RuntimeException e) {
                long elapsed = System.currentTimeMillis() - began;
                String reason = "provider 内部错误: " + e.getClass().getSimpleName();
                attempts.add(new TargetAttempt(target, capability.providerId(),
                        TargetAttempt.FAILED, CapabilityCode.PARSE_ERROR, elapsed, false, reason));
                lastFailure = new Failure(target, CapabilityCode.PARSE_ERROR, reason, List.of(),
                        false, false);
                break;
            }
        }

        if (lastFailure == null) {
            List<Requirement> requirements = List.of(new Requirement(RequirementKind.CONFIG,
                    PROVIDERS_CONFIG_KEY, "至少启用一个 metadata provider"));
            lastFailure = new Failure(target, CapabilityCode.NOT_CONFIGURED,
                    "所有 provider 均未启用", requirements, true, false);
        }
        return TargetOutcome.failure(target, lastFailure, attempts, skipped, warnings);
    }

    private TargetOutcome buildSuccess(MetadataTarget target, MetadataCapability capability,
                                       MetadataFetchResult fetched, Set<MetadataScope> scopes,
                                       List<String> sections, List<String> keys, int limit,
                                       long capturedAt, List<TargetAttempt> attempts,
                                       List<MetadataQueryResponse.Skip> skipped,
                                       List<BackendWarning> warnings) {
        List<VersionView> versions = new ArrayList<>();
        if (scopes.contains(MetadataScope.VERSION)) {
            if (!capability.supportsVersion()) {
                // scope=both 时允许「只支持 config」的 provider 出配置，版本显式标 NOT_SUPPORTED
                versions.add(notSupportedVersion(target, capability.providerId(), capturedAt));
                warnings.add(BackendWarning.of("SCOPE_NOT_SUPPORTED",
                        capability.providerId() + " 不支持版本查询，本次只返回配置"));
            } else if (fetched.versions().isEmpty()) {
                versions.add(unavailableVersion(target, capability.providerId(),
                        "PROVIDER_RETURNED_NO_VERSION", capturedAt));
            } else {
                for (RawVersion raw : fetched.versions()) {
                    versions.add(toVersionView(target, capability.providerId(), raw, capturedAt));
                }
            }
        }

        List<ConfigEntryView> configs = new ArrayList<>();
        boolean truncated = false;
        if (scopes.contains(MetadataScope.CONFIG) && !capability.supportsConfig()) {
            warnings.add(BackendWarning.of("SCOPE_NOT_SUPPORTED",
                    capability.providerId() + " 不支持配置查询，本次只返回版本"));
        }
        if (scopes.contains(MetadataScope.CONFIG)) {
            List<RawConfigEntry> kept = new ArrayList<>();
            for (RawConfigEntry raw : fetched.configs()) {
                if (raw == null || raw.key() == null) {
                    continue;
                }
                if (!sections.isEmpty() && !sections.contains(raw.section())) {
                    continue;
                }
                if (!keys.isEmpty() && !keys.contains(raw.key())) {
                    continue;
                }
                kept.add(raw);
            }
            if (kept.size() > limit) {
                kept = new ArrayList<>(kept.subList(0, limit));
                truncated = true;
            }
            for (RawConfigEntry raw : kept) {
                configs.add(toConfigView(target, capability.providerId(), raw, capability, capturedAt));
            }
        }

        for (String warning : fetched.warnings()) {
            warnings.add(BackendWarning.of("PROVIDER_WARNING",
                    capability.providerId() + ": " + warning));
        }

        return TargetOutcome.success(target,
                new MetadataQueryResponse.Selected(target, capability.providerId(),
                        capability.providerType(), capability.order()),
                versions, configs, attempts, skipped, warnings, truncated);
    }

    private VersionView toVersionView(MetadataTarget target, String providerId, RawVersion raw,
                                      long capturedAt) {
        String componentId = raw.componentId() == null || raw.componentId().isBlank()
                ? target.target().componentId() : raw.componentId();
        String hostId = raw.hostId() == null || raw.hostId().isBlank()
                ? target.target().hostId() : raw.hostId();
        if (raw.rawVersion() == null || raw.rawVersion().isBlank()) {
            return new VersionView(target, providerId, componentId, hostId, null, null,
                    raw.buildHash(), raw.vendor(), raw.distribution(), VersionView.UNAVAILABLE,
                    "VERSION_NOT_EXPOSED", raw.source(), capturedAt);
        }
        NormalizedVersion normalized = versionNormalizer.normalize(raw.rawVersion());
        return new VersionView(target, providerId, componentId, hostId, raw.rawVersion(),
                normalized, raw.buildHash(), raw.vendor(), raw.distribution(),
                VersionView.AVAILABLE,
                normalized == null ? VersionView.VERSION_UNPARSEABLE : null,
                raw.source(), capturedAt);
    }

    private VersionView unavailableVersion(MetadataTarget target, String providerId, String reason,
                                           long capturedAt) {
        return new VersionView(target, providerId, target.target().componentId(),
                target.target().hostId(), null, null, null, null, null,
                VersionView.UNAVAILABLE, reason, null, capturedAt);
    }

    /** provider 本身不声明版本能力：显式 NOT_SUPPORTED，不当成「取不到」。 */
    private VersionView notSupportedVersion(MetadataTarget target, String providerId,
                                            long capturedAt) {
        return new VersionView(target, providerId, target.target().componentId(),
                target.target().hostId(), null, null, null, null, null,
                VersionView.NOT_SUPPORTED, "该 provider 不支持版本查询", null, capturedAt);
    }

    private ConfigEntryView toConfigView(MetadataTarget target, String providerId,
                                         RawConfigEntry raw, MetadataCapability capability,
                                         long capturedAt) {
        MetadataMasker.MaskedValue masked = masker.mask(raw.section(), raw.key(), raw.value(),
                raw.sensitiveHint(), capability.maskingRules());
        return new ConfigEntryView(target, providerId, raw.section(), raw.key(), masked.value(),
                raw.valueType(), masked.sensitive(), masked.redacted(), raw.source(), capturedAt);
    }

    // ------------------------------------------------------------------ 校验

    private List<MetadataTarget> requireTargets(MetadataQueryRequest request) {
        if (request == null || request.getTargets() == null || request.getTargets().isEmpty()) {
            throw new BusinessException(400, "缺少必填参数: targets");
        }
        int maxTargets = properties.getQuery().getMaxTargets();
        if (request.getTargets().size() > maxTargets) {
            throw new BusinessException(400, "targets 数量超过上限 " + maxTargets);
        }
        List<MetadataTarget> normalized = new ArrayList<>();
        for (MetadataTarget item : request.getTargets()) {
            if (item == null || item.target() == null) {
                throw new BusinessException(400, "targets[].target 必填");
            }
            Target target = item.target();
            requireText(target.serviceType(), "targets[].target.serviceType");
            requireText(target.componentId(), "targets[].target.componentId");
            if (!ServiceIdentityCatalog.components().containsKey(target.serviceType())) {
                throw new BusinessException(400, "未知服务类型: " + target.serviceType());
            }
            if (!ServiceIdentityCatalog.supports(target.serviceType(), target.componentId())) {
                throw new BusinessException(400, "组件不属于该服务: " + target.componentId());
            }
            String hostId = target.hostId() == null || target.hostId().isBlank()
                    ? Target.CLUSTER_HOST : target.hostId();
            normalized.add(new MetadataTarget(
                    new Target(target.serviceType(), target.componentId(), hostId), item.ref()));
        }
        return normalized;
    }

    private Set<MetadataScope> requireScope(String scope) {
        try {
            return MetadataScope.parse(scope);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, e.getMessage());
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(400, "缺少必填参数: " + field);
        }
    }

    // ------------------------------------------------------------------ 错误归类

    /** 可用性 / 配置类 → unavailable；权限、参数、解析类 → failed。 */
    private boolean isAvailability(CapabilityCode code) {
        return switch (code) {
            case UPSTREAM_TIMEOUT, UPSTREAM_ERROR, IO_ERROR, RATE_LIMITED, BACKEND_UNAVAILABLE,
                    NOT_CONFIGURED, SOURCE_DISABLED, NO_PROVIDER, METRIC_NOT_EXPOSED,
                    METRIC_DISCOVERY_UNSUPPORTED -> true;
            default -> false;
        };
    }

    private String outcomeOf(CapabilityCode code) {
        return isAvailability(code) ? TargetAttempt.UNAVAILABLE : TargetAttempt.FAILED;
    }

    /** 只有可用性 / 配置类故障允许回退，且 provider 显式声明 fallbackAllowed。 */
    private boolean canFallback(CapabilityCode code, MetadataCapability capability) {
        return capability.fallbackAllowed() && isAvailability(code);
    }

    private Failure firstNonAvailability(List<Failure> failures) {
        return failures.stream().filter(failure -> !failure.availability()).findFirst()
                .orElse(failures.get(0));
    }

    /** 异常消息不能携带原始配置值；截断并保证非空。 */
    private String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "provider 调用失败";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private record Failure(MetadataTarget target, CapabilityCode code, String reason,
                           List<Requirement> requirements, boolean availability,
                           boolean retryable) {
    }

    private record TargetOutcome(MetadataTarget target, boolean success, boolean noProvider,
                                 MetadataQueryResponse.Selected selected,
                                 List<VersionView> versions, List<ConfigEntryView> configs,
                                 List<TargetAttempt> attempts,
                                 List<MetadataQueryResponse.Skip> skipped,
                                 List<BackendWarning> warnings, boolean truncated,
                                 Failure failure) {

        static TargetOutcome success(MetadataTarget target,
                                     MetadataQueryResponse.Selected selected,
                                     List<VersionView> versions, List<ConfigEntryView> configs,
                                     List<TargetAttempt> attempts,
                                     List<MetadataQueryResponse.Skip> skipped,
                                     List<BackendWarning> warnings, boolean truncated) {
            return new TargetOutcome(target, true, false, selected, versions, configs, attempts,
                    skipped, warnings, truncated, null);
        }

        static TargetOutcome noProvider(MetadataTarget target) {
            return new TargetOutcome(target, false, true, null, List.of(), List.of(), List.of(),
                    List.of(), List.of(), false, null);
        }

        static TargetOutcome failure(MetadataTarget target, Failure failure,
                                     List<TargetAttempt> attempts,
                                     List<MetadataQueryResponse.Skip> skipped,
                                     List<BackendWarning> warnings) {
            return new TargetOutcome(target, false, false, null, List.of(), List.of(), attempts,
                    skipped, warnings, false, failure);
        }
    }
}
