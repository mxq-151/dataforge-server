package com.bigdata.modules.telemetry.v1.catalog;

import com.bigdata.modules.telemetry.v1.model.BackendBinding;
import com.bigdata.modules.telemetry.v1.model.BackendStatus;
import com.bigdata.modules.telemetry.v1.model.BackendType;
import com.bigdata.modules.telemetry.v1.model.MetricDefinition;
import com.bigdata.modules.telemetry.v1.model.MetricDefinition.LabelDefinition;
import com.bigdata.modules.telemetry.v1.model.MetricDiscoveryMode;
import com.bigdata.modules.telemetry.v1.model.MetricPattern;
import com.bigdata.modules.telemetry.v1.model.QueryMode;
import com.bigdata.modules.telemetry.v1.model.Sampling;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 指标语义声明的公共构造辅助；只供各后端语义 provider 使用。 */
final class MetricSemanticsSupport {

    static final Set<QueryMode> BOTH = Set.of(QueryMode.RANGE, QueryMode.INSTANT);
    static final Set<QueryMode> INSTANT = Set.of(QueryMode.INSTANT);
    static final Set<Sampling> STEP = Set.of(Sampling.STEP);

    private MetricSemanticsSupport() {
    }

    static MetricDefinition metric(String name, String type, String unit, String scope,
                                   List<LabelDefinition> labels, List<String> requiredRefs,
                                   Map<String, List<String>> preference,
                                   BackendBinding[] backends) {
        return new MetricDefinition(name, type, unit, scope, labels, requiredRefs, preference,
                List.of(backends), null, null, null, null,
                MetricPattern.exact(name), MetricDiscoveryMode.DISCOVERED);
    }

    static MetricDefinition rawMetric(String name, String type, String unit, String scope,
                                      List<LabelDefinition> labels, List<String> requiredRefs,
                                      Map<String, List<String>> preference,
                                      BackendBinding[] backends, String rawMetric,
                                      String endpoint, boolean verified, String note) {
        return new MetricDefinition(name, type, unit, scope, labels, requiredRefs, preference,
                List.of(backends), rawMetric, endpoint, verified, note,
                MetricPattern.exact(rawMetric), MetricDiscoveryMode.DISCOVERED);
    }

    /** 引擎没有列举能力、或该指标不在列举结果里的手工语义。 */
    static MetricDefinition manualRawMetric(String name, String type, String unit, String scope,
                                            List<LabelDefinition> labels, List<String> requiredRefs,
                                            Map<String, List<String>> preference,
                                            BackendBinding[] backends, String rawMetric,
                                            String endpoint, boolean verified, String note) {
        return new MetricDefinition(name, type, unit, scope, labels, requiredRefs, preference,
                List.of(backends), rawMetric, endpoint, verified, note,
                MetricPattern.exact(rawMetric), MetricDiscoveryMode.MANUAL);
    }

    /** 手工语义：pattern 用语义名，避免多个字段映射到同一 raw 名时启动期冲突。 */
    static MetricDefinition manualSemanticMetric(String name, String type, String unit, String scope,
                                                 List<LabelDefinition> labels,
                                                 List<String> requiredRefs,
                                                 Map<String, List<String>> preference,
                                                 BackendBinding[] backends, String rawMetric,
                                                 String endpoint, boolean verified, String note) {
        return new MetricDefinition(name, type, unit, scope, labels, requiredRefs, preference,
                List.of(backends), rawMetric, endpoint, verified, note,
                MetricPattern.exact(name), MetricDiscoveryMode.MANUAL);
    }

    static MetricDefinition familyMetric(String name, String type, String unit, String scope,
                                         List<LabelDefinition> labels, List<String> requiredRefs,
                                         Map<String, List<String>> preference,
                                         BackendBinding[] backends, MetricPattern pattern,
                                         String endpoint, boolean verified, String note) {
        String raw = pattern.expression().startsWith(".")
                ? pattern.expression().substring(1) : pattern.expression();
        return new MetricDefinition(name, type, unit, scope, labels, requiredRefs, preference,
                List.of(backends), raw, endpoint, verified, note, pattern,
                MetricDiscoveryMode.DISCOVERED);
    }

    static List<LabelDefinition> executorLabels() {
        return labels(label("application_id", true, "Spark 应用 ID"),
                label("executor_id", true, "Executor ID"));
    }

    static List<LabelDefinition> labels(LabelDefinition... labels) {
        return List.of(labels);
    }

    static LabelDefinition label(String name, boolean required, String description) {
        return new LabelDefinition(name, required, description);
    }

    static Map<String, List<String>> pref(List<String> instant, List<String> range) {
        return Map.of("instant", instant, "range", range);
    }

    static Map<String, List<String>> sparkPref() {
        return pref(List.of("spark-rest", "prometheus", "victoriametrics"),
                List.of("prometheus", "victoriametrics"));
    }

    static BackendBinding[] checkpointBackends() {
        return new BackendBinding[]{
                flinkRest(10, BOTH, Set.of(Sampling.RAW), BackendStatus.IMPLEMENTED,
                        "checkpoint history 是离散事件序列，不做等步长插值"),
                prometheus(20, false),
                victoriametrics(30, false)};
    }

    static BackendBinding[] throughputBackends() {
        return new BackendBinding[]{
                prometheus(10, true),
                victoriametrics(20, false)};
    }

    static BackendBinding[] jvmBackends() {
        return new BackendBinding[]{
                prometheus(10, false),
                victoriametrics(20, false)};
    }

    static BackendBinding[] vertexBackends() {
        return new BackendBinding[]{
                flinkRest(10, INSTANT, STEP, BackendStatus.IMPLEMENTED,
                        "背压只有当前快照，REST 不支持 range"),
                prometheus(20, false),
                victoriametrics(30, false)};
    }

    static BackendBinding[] sparkBackends() {
        return new BackendBinding[]{
                sparkRest(10),
                prometheus(20, false),
                victoriametrics(30, false)};
    }

    static BackendBinding[] vmOnly() {
        return new BackendBinding[]{victoriametrics(10, true)};
    }

    static Map<String, List<String>> vmPref() {
        return pref(List.of("victoriametrics"), List.of("victoriametrics"));
    }

    static BackendBinding prometheus(int priority, boolean authoritative) {
        return new BackendBinding("prometheus", BackendType.PROMETHEUS, BackendStatus.IMPLEMENTED,
                priority, BOTH, STEP, authoritative, null,
                "PromQL 历史序列；未配置 reporter 时指标缺席，不补 0");
    }

    static BackendBinding victoriametrics(int priority, boolean authoritative) {
        return new BackendBinding("victoriametrics", BackendType.VICTORIAMETRICS, BackendStatus.IMPLEMENTED,
                priority, BOTH, STEP, authoritative, null,
                "与 Prometheus 默认不互为等价组，不会自动互相回退");
    }

    static BackendBinding flinkRest(int priority, Set<QueryMode> modes, Set<Sampling> sampling,
                                    BackendStatus status, String notes) {
        return new BackendBinding("flink-rest", BackendType.FLINK_REST, status, priority,
                modes, sampling, true, null, notes);
    }

    static BackendBinding sparkRest(int priority) {
        return new BackendBinding("spark-rest", BackendType.SPARK_REST, BackendStatus.IMPLEMENTED,
                priority, INSTANT, Set.of(Sampling.RAW), true, null,
                "SHS 是完成态快照，只支持 instant；live 用 mode=live");
    }
}
