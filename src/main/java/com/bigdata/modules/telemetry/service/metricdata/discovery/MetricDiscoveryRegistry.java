package com.bigdata.modules.telemetry.service.metricdata.discovery;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.Requirement;
import com.bigdata.modules.telemetry.common.model.RequirementKind;
import com.bigdata.modules.telemetry.common.model.BackendWarning;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.bigdata.modules.telemetry.service.metricdata.model.DiscoveredMetric;
import com.bigdata.modules.telemetry.service.metricdata.spi.MetricDiscoveryProvider;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDiscoveryQuery;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDiscoveryResult;
import com.bigdata.modules.telemetry.util.Strings;

/**
 * 指标发现 provider 注册表：去重、排序、合并结果。
 *
 * <p>providerId 重复启动失败；每个 provider 的失败只降级为 warning，不让其它 provider 的结果丢失。</p>
 */
@Component
public class MetricDiscoveryRegistry {

    private final List<MetricDiscoveryProvider> providers;

    public MetricDiscoveryRegistry(List<MetricDiscoveryProvider> providers) {
        Map<String, MetricDiscoveryProvider> byId = new LinkedHashMap<>();
        for (MetricDiscoveryProvider provider : providers) {
            if (provider.providerId() == null || provider.providerId().isBlank()) {
                throw new IllegalStateException("MetricDiscoveryProvider.providerId 不能为空");
            }
            if (byId.putIfAbsent(provider.providerId(), provider) != null) {
                throw new IllegalStateException("重复的指标发现 providerId: " + provider.providerId());
            }
        }
        this.providers = byId.values().stream()
                .sorted(Comparator.comparingInt(MetricDiscoveryProvider::order)
                        .thenComparing(MetricDiscoveryProvider::providerId))
                .toList();
    }

    public MetricDiscoveryResult discover(MetricDiscoveryQuery query) {
        List<MetricDiscoveryProvider> candidates = providers.stream()
                .filter(MetricDiscoveryProvider::enabled)
                .filter(provider -> provider.supports(query.target()))
                .toList();
        if (candidates.isEmpty()) {
            return MetricDiscoveryResult.unsupported("metric-discovery",
                    "没有可用的指标名发现 provider（引擎不支持列举，或未配置发现地址）",
                    List.of(new Requirement(RequirementKind.IMPLEMENTATION,
                            "MetricDiscoveryProvider",
                            "实现并注册指标发现 provider，或配置对应引擎的 metrics 地址")));
        }

        Map<String, DiscoveredMetric> merged = new LinkedHashMap<>();
        List<BackendWarning> warnings = new ArrayList<>();
        List<Requirement> requirements = new ArrayList<>();
        CapabilityCode firstFailureCode = null;
        String firstFailureReason = null;
        boolean anySupported = false;

        for (MetricDiscoveryProvider provider : candidates) {
            try {
                MetricDiscoveryResult result = provider.discover(query);
                if (result.supported()) {
                    anySupported = true;
                    for (DiscoveredMetric metric : result.metrics()) {
                        merged.putIfAbsent(metric.name(), metric);
                    }
                }
                warnings.addAll(result.warnings());
                requirements.addAll(result.requirements());
                if (!result.supported() && firstFailureCode == null) {
                    firstFailureCode = result.code();
                    firstFailureReason = result.reason();
                }
            } catch (BackendException e) {
                warnings.add(BackendWarning.of("METRIC_DISCOVERY_PROVIDER_FAILED",
                        provider.providerId() + ": " + Strings.safeMessage(e.getMessage(), "指标发现失败")));
                requirements.addAll(e.getRequirements());
                if (firstFailureCode == null) {
                    firstFailureCode = e.getCode();
                    firstFailureReason = e.getMessage();
                }
            } catch (RuntimeException e) {
                warnings.add(BackendWarning.of("METRIC_DISCOVERY_PROVIDER_FAILED",
                        provider.providerId() + " 内部错误: " + e.getClass().getSimpleName()));
                if (firstFailureCode == null) {
                    firstFailureCode = CapabilityCode.PARSE_ERROR;
                    firstFailureReason = "指标发现 provider 内部错误";
                }
            }
        }

        if (anySupported) {
            return MetricDiscoveryResult.supported("metric-discovery",
                    new ArrayList<>(merged.values()), warnings);
        }
        return MetricDiscoveryResult.failed("metric-discovery",
                firstFailureCode == null ? CapabilityCode.METRIC_DISCOVERY_UNSUPPORTED
                        : firstFailureCode,
                firstFailureReason == null ? "指标发现失败" : firstFailureReason,
                requirements, warnings);
    }

}
