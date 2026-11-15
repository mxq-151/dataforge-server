package com.bigdata.modules.telemetry.v1.backend.promql;

import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.v1.model.QueryMode;
import com.bigdata.modules.telemetry.v1.spi.BackendException;
import com.bigdata.modules.telemetry.v1.spi.MetricQuerySpec;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 指标名 + 标签匹配器 → PromQL selector。
 *
 * <p>固定注入 {@code service_type}、{@code component_id}，非 cluster 目标再注入 {@code host_id}；
 * 已知业务引用转换为标准标签，未知引用不拼进 selector，避免把查询打空。</p>
 */
public final class PromqlSelectorBuilder {

    private static final Pattern METRIC_NAME = Pattern.compile("[a-zA-Z_:][a-zA-Z0-9_:]*");
    private static final Map<String, String> REF_LABELS = Map.of(
            "jobId", "job_id",
            "applicationId", "application_id",
            "taskInstanceId", "task_instance_id",
            "containerId", "container_id");

    private PromqlSelectorBuilder() {
    }

    public static String build(MetricQuerySpec spec) {
        if (spec.rate()) {
            requireRateAllowed(spec);
        }
        String metric = spec.definition().name();
        if (!METRIC_NAME.matcher(metric).matches()) {
            throw new BackendException(CapabilityCode.INVALID_ARGUMENT, "非法指标名: " + metric);
        }
        Map<String, String> matchers = new LinkedHashMap<>();
        matchers.put("service_type", spec.target().serviceType());
        matchers.put("component_id", spec.target().componentId());
        if (!spec.target().clusterScoped()) {
            matchers.put("host_id", spec.target().hostId());
        }
        for (Map.Entry<String, String> entry : spec.refs().entrySet()) {
            String label = REF_LABELS.get(entry.getKey());
            if (label != null && entry.getValue() != null && !entry.getValue().isBlank()) {
                matchers.put(label, entry.getValue());
            }
        }
        matchers.putAll(spec.labelMatchers());

        StringBuilder selector = new StringBuilder(metric).append('{');
        boolean first = true;
        for (Map.Entry<String, String> entry : matchers.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            if (!first) {
                selector.append(',');
            }
            selector.append(entry.getKey()).append("=\"").append(escape(entry.getValue())).append('"');
            first = false;
        }
        String built = selector.append('}').toString();
        return spec.rate() ? "rate(" + built + "[" + formatWindow(spec.step()) + "])" : built;
    }

    /**
     * rate 只允许 counter + range；窗口取 {@code [step]}，PromQL 要求查询步长能被窗口整除。
     * 当前窗口与 step 相同，整除条件恒成立；保留校验是为了将来引入独立窗口时不静默取整。
     */
    private static void requireRateAllowed(MetricQuerySpec spec) {
        if (spec.mode() != QueryMode.RANGE) {
            throw new BackendException(CapabilityCode.INVALID_ARGUMENT,
                    "rate 只允许用于 range 查询，当前 mode=" + spec.mode().wire());
        }
        String type = spec.definition().type();
        if (!"counter".equalsIgnoreCase(type)) {
            throw new BackendException(CapabilityCode.INVALID_ARGUMENT,
                    "rate 只允许用于 counter 指标，当前类型=" + type);
        }
        if (spec.step() == null || spec.step().isZero() || spec.step().isNegative()) {
            throw new BackendException(CapabilityCode.INVALID_ARGUMENT,
                    "rate 查询必须提供大于 0 的 step 作为窗口");
        }
        long stepMillis = spec.step().toMillis();
        long windowMillis = spec.step().toMillis();
        if (windowMillis <= 0 || stepMillis % windowMillis != 0) {
            throw new BackendException(CapabilityCode.INVALID_ARGUMENT,
                    "step 必须能被 rate 窗口整除：step=" + stepMillis + "ms, window=" + windowMillis + "ms");
        }
    }

    /** PromQL 窗口字面量：优先分钟，其次秒，最后毫秒。 */
    static String formatWindow(Duration window) {
        long millis = window.toMillis();
        if (millis % 60_000 == 0) {
            return (millis / 60_000) + "m";
        }
        if (millis % 1_000 == 0) {
            return (millis / 1_000) + "s";
        }
        return millis + "ms";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
