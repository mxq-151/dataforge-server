package com.bigdata.modules.telemetry.v1.metadata.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.telemetry.catalog.ServiceComponentCatalog;
import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.Requirement;
import com.bigdata.modules.telemetry.model.RequirementKind;
import com.bigdata.modules.telemetry.v1.metadata.config.TelemetryMetadataProperties;
import com.bigdata.modules.telemetry.v1.metadata.dto.MetadataQueryV1Request;
import com.bigdata.modules.telemetry.v1.metadata.model.ConfigEntryView;
import com.bigdata.modules.telemetry.v1.metadata.model.MetadataData;
import com.bigdata.modules.telemetry.v1.metadata.model.MetadataScope;
import com.bigdata.modules.telemetry.v1.metadata.model.MetadataTarget;
import com.bigdata.modules.telemetry.v1.metadata.model.MetricNameView;
import com.bigdata.modules.telemetry.v1.metadata.model.NormalizedVersion;
import com.bigdata.modules.telemetry.v1.metadata.model.TargetAttempt;
import com.bigdata.modules.telemetry.v1.metadata.model.VersionView;
import com.bigdata.modules.telemetry.v1.discovery.DiscoveredMetric;
import com.bigdata.modules.telemetry.v1.discovery.MetricDiscoveryResult;
import com.bigdata.modules.telemetry.v1.discovery.MetricDiscoveryService;
import com.bigdata.modules.telemetry.v1.metadata.registry.ServiceMetadataProviderRegistry;
import com.bigdata.modules.telemetry.v1.metadata.spi.MetadataCapability;
import com.bigdata.modules.telemetry.v1.metadata.spi.MetadataFetchResult;
import com.bigdata.modules.telemetry.v1.metadata.spi.MetadataQuery;
import com.bigdata.modules.telemetry.v1.metadata.spi.RawConfigEntry;
import com.bigdata.modules.telemetry.v1.metadata.spi.RawVersion;
import com.bigdata.modules.telemetry.v1.metadata.spi.ServiceMetadataProvider;
import com.bigdata.modules.telemetry.v1.metadata.support.MetadataMasker;
import com.bigdata.modules.telemetry.v1.metadata.support.VersionNormalizer;
import com.bigdata.modules.telemetry.v1.metadata.vo.TelemetryMetadataV1Response;
import com.bigdata.modules.telemetry.v1.model.BackendWarning;
import com.bigdata.modules.telemetry.v1.model.Target;
import com.bigdata.modules.telemetry.v1.spi.BackendException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
    private final MetadataMasker masker;
    private final VersionNormalizer versionNormalizer;
    private final TelemetryMetadataProperties properties;
    private final MetricDiscoveryService metricDiscoveryService;

    public MetadataTelemetryQueryService(ServiceMetadataProviderRegistry registry,
                                         MetadataMasker masker,
                                         VersionNormalizer versionNormalizer,
                                         TelemetryMetadataProperties properties,
                                         MetricDiscoveryService metricDiscoveryService) {
        this.registry = registry;
        this.masker = masker;
        this.versionNormalizer = versionNormalizer;
        this.properties = properties;
        this.metricDiscoveryService = metricDiscoveryService;
    }

    public TelemetryMetadataV1Response query(MetadataQueryV1Request request) {
        long start = System.currentTimeMillis();
        List<MetadataTarget> targets = requireTargets(request);
        Set<MetadataScope> scopes = requireScope(request.getScope());
        int maxConfigItems = properties.getQuery().getMaxConfigItems();
        int limit = request.getLimit() == null ? maxConfigItems : request.getLimit();
        if (limit < 1 || limit > maxConfigItems) {
            throw new BusinessException(400, "limit 必须在 1.." + maxConfigItems);
        }
        Duration timeout = Duration.ofMillis(Math.max(properties.getQuery().getTimeoutMs(), 1));
        List<String> sections = request.getSections() == null ? List.of() : request.getSections();
        List<String> keys = request.getKeys() == null ? List.of() : request.getKeys();
        long capturedAt = System.currentTimeMillis();

        if (scopes.size() == 1 && scopes.contains(MetadataScope.METRICS)) {
            return queryMetrics(targets, request, timeout, capturedAt, start);
        }

        List<VersionView> versions = new ArrayList<>();
        List<ConfigEntryView> configs = new ArrayList<>();
        List<TargetAttempt> attempts = new ArrayList<>();
        List<TelemetryMetadataV1Response.Selected> selected = new ArrayList<>();
        List<TelemetryMetadataV1Response.Skip> skipped = new ArrayList<>();
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
        TelemetryMetadataV1Response.ProviderView providers = new TelemetryMetadataV1Response.ProviderView(
                selected, attempts, skipped);
        TelemetryMetadataV1Response.ErrorView error = "ok".equals(outcome) ? null
                : new TelemetryMetadataV1Response.ErrorView(code, message, requirements);
        TelemetryMetadataV1Response.MetaView meta = new TelemetryMetadataV1Response.MetaView(
                System.currentTimeMillis() - start, partial, false, warnings,
                Instant.now().toString(), truncated);
        return new TelemetryMetadataV1Response(outcome, retryable, targets,
                MetadataScope.wire(scopes), providers, data, error, meta);
    }

    // ------------------------------------------------------------------ 指标名发现

    private TelemetryMetadataV1Response queryMetrics(List<MetadataTarget> targets,
                                                     MetadataQueryV1Request request,
                                                     Duration timeout, long capturedAt,
                                                     long start) {
        int maxMetrics = properties.getQuery().getMaxMetrics();
        int limit = request.getLimit() == null ? maxMetrics : request.getLimit();
        if (limit < 1 || limit > maxMetrics) {
            throw new BusinessException(400, "limit 必须在 1.." + maxMetrics);
        }

        List<MetricNameView> metrics = new ArrayList<>();
        List<TargetAttempt> attempts = new ArrayList<>();
        List<TelemetryMetadataV1Response.Selected> selected = new ArrayList<>();
        List<TelemetryMetadataV1Response.Skip> skipped = new ArrayList<>();
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
                selected.add(new TelemetryMetadataV1Response.Selected(target, result.providerId(),
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
        TelemetryMetadataV1Response.ProviderView providers = new TelemetryMetadataV1Response.ProviderView(
                selected, attempts, skipped);
        TelemetryMetadataV1Response.ErrorView error = "ok".equals(outcome) ? null
                : new TelemetryMetadataV1Response.ErrorView(code, message, requirements);
        TelemetryMetadataV1Response.MetaView meta = new TelemetryMetadataV1Response.MetaView(
                System.currentTimeMillis() - start, partial, false, warnings,
                Instant.now().toString(), truncated);
        return new TelemetryMetadataV1Response(outcome, retryable, targets, "metrics", providers,
                data, error, meta);
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
        List<TelemetryMetadataV1Response.Skip> skipped = new ArrayList<>();
        List<BackendWarning> warnings = new ArrayList<>();
        Failure lastFailure = null;

        for (ServiceMetadataProvider provider : candidates) {
            MetadataCapability capability = provider.capability();
            if (!provider.enabled()) {
                skipped.add(new TelemetryMetadataV1Response.Skip(target, capability.providerId(),
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
                                       List<TelemetryMetadataV1Response.Skip> skipped,
                                       List<BackendWarning> warnings) {
        List<VersionView> versions = new ArrayList<>();
        if (scopes.contains(MetadataScope.VERSION)) {
            if (fetched.versions().isEmpty()) {
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
                new TelemetryMetadataV1Response.Selected(target, capability.providerId(),
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

    private ConfigEntryView toConfigView(MetadataTarget target, String providerId,
                                         RawConfigEntry raw, MetadataCapability capability,
                                         long capturedAt) {
        MetadataMasker.MaskedValue masked = masker.mask(raw.section(), raw.key(), raw.value(),
                raw.sensitiveHint(), capability.maskingRules());
        return new ConfigEntryView(target, providerId, raw.section(), raw.key(), masked.value(),
                raw.valueType(), masked.sensitive(), masked.redacted(), raw.source(), capturedAt);
    }

    // ------------------------------------------------------------------ 校验

    private List<MetadataTarget> requireTargets(MetadataQueryV1Request request) {
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
            if (!ServiceComponentCatalog.components().containsKey(target.serviceType())) {
                throw new BusinessException(400, "未知服务类型: " + target.serviceType());
            }
            if (!ServiceComponentCatalog.supports(target.serviceType(), target.componentId())) {
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
                                 TelemetryMetadataV1Response.Selected selected,
                                 List<VersionView> versions, List<ConfigEntryView> configs,
                                 List<TargetAttempt> attempts,
                                 List<TelemetryMetadataV1Response.Skip> skipped,
                                 List<BackendWarning> warnings, boolean truncated,
                                 Failure failure) {

        static TargetOutcome success(MetadataTarget target,
                                     TelemetryMetadataV1Response.Selected selected,
                                     List<VersionView> versions, List<ConfigEntryView> configs,
                                     List<TargetAttempt> attempts,
                                     List<TelemetryMetadataV1Response.Skip> skipped,
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
                                     List<TelemetryMetadataV1Response.Skip> skipped,
                                     List<BackendWarning> warnings) {
            return new TargetOutcome(target, false, false, null, List.of(), List.of(), attempts,
                    skipped, warnings, false, failure);
        }
    }
}
