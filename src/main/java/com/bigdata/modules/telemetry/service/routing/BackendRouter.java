package com.bigdata.modules.telemetry.service.routing;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.BackendBinding;
import com.bigdata.modules.telemetry.common.model.BackendStatus;
import com.bigdata.modules.telemetry.common.model.BackendType;
import com.bigdata.modules.telemetry.service.logdata.model.LogDataType;
import com.bigdata.modules.telemetry.service.logdata.model.LogStreamDefinition;
import com.bigdata.modules.telemetry.service.metricdata.model.MetricDefinition;
import com.bigdata.modules.telemetry.common.model.QueryMode;
import com.bigdata.modules.telemetry.common.model.Target;
import com.bigdata.modules.telemetry.service.logdata.spi.LogBackend;
import com.bigdata.modules.telemetry.service.metricdata.spi.MetricBackend;
import com.bigdata.modules.telemetry.config.TelemetryBackendRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import com.bigdata.modules.telemetry.config.RoutingProperties;
import com.bigdata.modules.telemetry.service.routing.model.BackendCandidate;
import com.bigdata.modules.telemetry.service.routing.model.BackendSelection;
import com.bigdata.modules.telemetry.service.routing.model.BackendSkip;

/**
 * 后端选择：目录候选 → 已注册实例展开 → 排序 → 暴露实际候选。
 *
 * <p>选择不发起网络调用；只有可发起的候选会进入取数阶段。被跳过的候选保留类型化 code，
 * 让调用方看到「为什么没选它」。</p>
 */
@Component
public class BackendRouter {

    private final RoutingProperties routing;
    private final TelemetryBackendRegistry registry;

    public BackendRouter(RoutingProperties routing, TelemetryBackendRegistry registry) {
        this.routing = routing;
        this.registry = registry;
    }

    public BackendSelection selectMetric(Target target, MetricDefinition definition, QueryMode mode) {
        List<BackendCandidate> candidates = new ArrayList<>();
        List<BackendSkip> skipped = new ArrayList<>();
        for (BackendBinding binding : definition.supportedBackends()) {
            collect(binding, mode, candidates, skipped);
        }
        RoutingProperties.Rule rule = matchingRule(routing.getRouting().getMetric().getRules(),
                target, definition.name(), null);
        sort(candidates, rule, definition.preferenceFor(mode),
                routing.getRouting().getMetric().getDefaultOrder());
        return new BackendSelection(candidates, skipped, preferred(candidates));
    }

    public BackendSelection selectLog(Target target, LogStreamDefinition stream) {
        List<BackendCandidate> candidates = new ArrayList<>();
        List<BackendSkip> skipped = new ArrayList<>();
        for (BackendBinding binding : stream.supportedBackends()) {
            if (!stream.dataType().allowsBackend(binding.backendType())) {
                skipped.add(new BackendSkip(binding.backendId(), binding.backendType().wire(),
                        CapabilityCode.LOG_DATA_TYPE_MISMATCH,
                        binding.backendType().wire() + " 不能承载 " + stream.dataType().wire()));
                continue;
            }
            collect(binding, null, candidates, skipped);
        }
        RoutingProperties.Rule rule = matchingRule(routing.getRouting().getLog().getRules(),
                target, null, stream.stream());
        sort(candidates, rule, List.of(), routing.getRouting().getLog().getDefaultOrder());
        return new BackendSelection(candidates, skipped, preferred(candidates));
    }

    /**
     * 引擎结构化证据（如 Flink 异常摘要）的后端选择。
     *
     * <p>复用指标后端的实例展开与排序规则，不新建注册表；证据目录只声明 binding，
     * 实际实例仍来自 {@code MetricBackend} 注册表。</p>
     */
    public BackendSelection selectEvidence(Target target, String evidenceName,
                                           List<BackendBinding> bindings) {
        List<BackendCandidate> candidates = new ArrayList<>();
        List<BackendSkip> skipped = new ArrayList<>();
        for (BackendBinding binding : bindings) {
            collect(binding, null, candidates, skipped);
        }
        RoutingProperties.Rule rule = matchingRule(routing.getRouting().getMetric().getRules(),
                target, evidenceName, null);
        sort(candidates, rule, List.of(), routing.getRouting().getMetric().getDefaultOrder());
        return new BackendSelection(candidates, skipped, preferred(candidates));
    }

    /**
     * 两个候选是否允许互相回退：目录声明同一等价组，且配置显式批准该组包含两者。
     *
     * <p>Prometheus 与 VictoriaMetrics 的目录声明没有等价组，因此永远不互相回退。</p>
     */
    public boolean equivalentBackends(BackendCandidate first, BackendCandidate second, String serviceType) {
        if (!sameLogDataTypeDomain(first.backendType(), second.backendType())) {
            return false;
        }
        if (first.equivalenceGroup() == null || !first.equivalenceGroup().equals(second.equivalenceGroup())) {
            return false;
        }
        RoutingProperties.EquivalenceGroup group =
                routing.getRouting().getEquivalenceGroups().get(first.equivalenceGroup());
        if (group == null) {
            return false;
        }
        if (group.getServiceType() != null && !group.getServiceType().isBlank()
                && !group.getServiceType().equals(serviceType)) {
            return false;
        }
        return contains(group.getBackends(), first) && contains(group.getBackends(), second);
    }

    public RoutingProperties.Circuit circuit() {
        return routing.getRouting().getCircuit();
    }

    // ------------------------------------------------------------------ 内部

    private void collect(BackendBinding binding, QueryMode mode,
                         List<BackendCandidate> candidates, List<BackendSkip> skipped) {
        if (mode != null && !binding.supports(mode)) {
            skipped.add(new BackendSkip(binding.backendId(), binding.backendType().wire(),
                    CapabilityCode.QUERY_MODE_UNSUPPORTED,
                    "该后端不支持 " + mode.wire() + " 查询"));
            return;
        }
        if (binding.status() != BackendStatus.IMPLEMENTED) {
            CapabilityCode code = binding.status() == BackendStatus.DECLARED_NOT_IMPLEMENTED
                    ? CapabilityCode.DATA_TYPE_NOT_IMPLEMENTED : CapabilityCode.BACKEND_UNAVAILABLE;
            skipped.add(new BackendSkip(binding.backendId(), binding.backendType().wire(), code,
                    binding.notes() == null ? "后端未实现或当前不可用" : binding.notes()));
            return;
        }

        List<String> ids = instanceIds(binding);
        if (ids.isEmpty()) {
            skipped.add(new BackendSkip(binding.backendId(), binding.backendType().wire(),
                    CapabilityCode.NOT_CONFIGURED,
                    "未配置 " + binding.backendType().wire() + " 实例（配置键 "
                            + configKey(binding.backendType()) + "）"));
            return;
        }
        for (String id : ids) {
            candidates.add(new BackendCandidate(id, binding.backendType(), BackendStatus.IMPLEMENTED,
                    CapabilityCode.OK, null, List.of(), binding.queryModes(),
                    binding.equivalenceGroup(), binding.authoritative(), binding.priority(),
                    binding.notes()));
        }
    }

    /** 按类型展开已注册实例；若目录写的是具体实例 id，则只取该实例。 */
    private List<String> instanceIds(BackendBinding binding) {
        List<String> all = new ArrayList<>();
        if (binding.backendType().category() == BackendType.Category.METRIC) {
            for (MetricBackend backend : registry.metricBackends()) {
                if (backend.backendType() == binding.backendType()) {
                    all.add(backend.backendId());
                }
            }
        } else {
            for (LogBackend backend : registry.logBackends()) {
                if (backend.backendType() == binding.backendType()) {
                    all.add(backend.backendId());
                }
            }
        }
        if (all.contains(binding.backendId())) {
            return List.of(binding.backendId());
        }
        return all;
    }

    private void sort(List<BackendCandidate> candidates, RoutingProperties.Rule rule,
                      List<String> preference, List<String> defaultOrder) {
        Map<BackendCandidate, OrderKey> keys = new LinkedHashMap<>();
        for (BackendCandidate candidate : candidates) {
            keys.put(candidate, orderKey(candidate, rule, preference, defaultOrder));
        }
        candidates.sort(Comparator.<BackendCandidate>comparingInt(c -> keys.get(c).rank())
                .thenComparingInt(c -> keys.get(c).index())
                .thenComparing(BackendCandidate::backendId));
    }

    private OrderKey orderKey(BackendCandidate candidate, RoutingProperties.Rule rule,
                              List<String> preference, List<String> defaultOrder) {
        if (rule != null) {
            int index = indexOf(rule.getOrder(), candidate);
            if (index >= 0) {
                return new OrderKey(0, index);
            }
        }
        int preferredIndex = indexOf(preference, candidate);
        if (preferredIndex >= 0) {
            return new OrderKey(1, preferredIndex);
        }
        int defaultIndex = indexOf(defaultOrder, candidate);
        if (defaultIndex >= 0) {
            return new OrderKey(2, defaultIndex);
        }
        return new OrderKey(3, candidate.priority());
    }

    private RoutingProperties.Rule matchingRule(List<RoutingProperties.Rule> rules, Target target,
                                                String metric, String stream) {
        RoutingProperties.Rule best = null;
        int bestScore = -1;
        for (RoutingProperties.Rule rule : rules) {
            if (!blank(rule.getServiceType()) && !rule.getServiceType().equals(target.serviceType())) {
                continue;
            }
            if (!blank(rule.getComponentId()) && !rule.getComponentId().equals(target.componentId())) {
                continue;
            }
            if (!blank(rule.getMetric()) && (metric == null || !glob(rule.getMetric(), metric))) {
                continue;
            }
            if (!blank(rule.getStream()) && (stream == null || !glob(rule.getStream(), stream))) {
                continue;
            }
            int score = 0;
            if (!blank(rule.getComponentId())) {
                score += 2;
            }
            if (!blank(rule.getServiceType())) {
                score += 1;
            }
            if (score > bestScore) {
                best = rule;
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean contains(List<String> values, BackendCandidate candidate) {
        return values != null
                && (values.contains(candidate.backendId())
                || values.contains(candidate.backendType().wire()));
    }

    private static int indexOf(List<String> order, BackendCandidate candidate) {
        if (order == null) {
            return -1;
        }
        for (int i = 0; i < order.size(); i++) {
            String value = order.get(i);
            if (value.equals(candidate.backendId()) || value.equals(candidate.backendType().wire())) {
                return i;
            }
        }
        return -1;
    }

    private static String preferred(List<BackendCandidate> candidates) {
        return candidates.isEmpty() ? null : candidates.get(0).backendId();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean glob(String pattern, String value) {
        StringBuilder regex = new StringBuilder();
        for (char ch : pattern.toCharArray()) {
            switch (ch) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        return value.matches(regex.toString());
    }

    private static String configKey(BackendType type) {
        return switch (type) {
            case PROMETHEUS -> "telemetry.backends.prometheus[]";
            case VICTORIAMETRICS -> "telemetry.backends.victoriametrics[]";
            case LOCAL_DISK -> "telemetry.backends.local-disk[]";
            case ELASTICSEARCH -> "telemetry.backends.elasticsearch[]";
            case FLINK_REST -> "telemetry.backends.flink-rest[]";
            case SPARK_SHS_REST -> "telemetry.backends.spark-shs-rest[]";
            case YARN -> "telemetry.backends.yarn[]";
            case DS_API -> "telemetry.backends.ds-api[]";
            case DORIS_AUDIT -> "telemetry.backends.doris-audit[]";
        };
    }

    /**
     * 服务日志后端（local-disk / ES）与任务日志后端（YARN）属于不同证据域，不能互相回退。
     * 即使配置误把两者写进同一 equivalenceGroup，这里也会拦下。
     */
    private static boolean sameLogDataTypeDomain(BackendType first, BackendType second) {
        if (first.category() != BackendType.Category.LOG
                || second.category() != BackendType.Category.LOG) {
            return true;
        }
        boolean firstService = LogDataType.SERVICE.allowsBackend(first);
        boolean secondService = LogDataType.SERVICE.allowsBackend(second);
        return firstService == secondService;
    }

    private record OrderKey(int rank, int index) {
    }
}
