package com.bigdata.modules.runtime.adapter;

import com.bigdata.modules.logs.model.ServiceType;
import com.bigdata.modules.runtime.model.MetricCatalog;
import com.bigdata.modules.runtime.model.MetricFamily;
import com.bigdata.modules.runtime.model.MetricScope;
import com.bigdata.modules.runtime.model.MetricSpec;
import com.bigdata.modules.runtime.model.RuntimeMode;
import com.bigdata.modules.runtime.model.RuntimeSource;
import com.bigdata.modules.runtime.config.RuntimeProperties;
import com.bigdata.modules.runtime.vo.RuntimeCheckpointVO;
import com.bigdata.modules.runtime.vo.RuntimeComponentVO;
import com.bigdata.modules.runtime.vo.RuntimeConfigVO;
import com.bigdata.modules.runtime.vo.RuntimeJobVO;
import com.bigdata.modules.runtime.vo.RuntimeMetricVO;
import com.bigdata.modules.runtime.vo.RuntimeSnapshotVO;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Flink 运行时指标适配器（JobManager REST，live 模式）。
 *
 * <h3>端点</h3>
 * <ul>
 *   <li>{@code GET /jobs/overview}：作业发现</li>
 *   <li>{@code GET /jobs/{id}}：状态、时间、tasks.failed、vertices</li>
 *   <li>{@code GET /jobs/{id}/checkpoints}：checkpoint 计数、最近一次成功/失败、历史</li>
 *   <li>{@code GET /jobs/{id}/config}：作业执行配置</li>
 *   <li>{@code GET /jobmanager/config}：集群配置（JM/TM 内存等）</li>
 *   <li>{@code GET /taskmanagers}、{@code GET /taskmanagers/{id}/metrics?get=...}：TM 列表与 JVM/GC</li>
 *   <li>{@code GET /jobs/{id}/metrics?get=...}：作业级指标</li>
 *   <li>{@code GET /jobs/{id}/vertices/{vid}/subtasks/metrics?get=...}：vertex/subtask 指标</li>
 * </ul>
 *
 * <p>Flink 的 {@code get=} 只接受显式指标名（逗号分隔）；{@code *} 通配虽可用，但一次会返回该作用域下
 * 所有匹配项且行为随版本变化。因此本适配器默认只对 GC collector 使用通配，并对缺失/非数值统一标记
 * {@code UNAVAILABLE}，不把「取不到」当成 0。显式 {@code metrics} 参数可覆盖白名单。</p>
 */
@Component
public class FlinkRuntimeAdapter implements RuntimeAdapter {

    private static final long HEARTBEAT_STALE_MS = 30_000L;
    private static final String TM = "taskmanager";
    private static final String VERTEX = "vertex";
    private static final String JOB = "job";

    private final RuntimeRestClient rest;

    public FlinkRuntimeAdapter(RuntimeRestClient rest) {
        this.rest = rest;
    }

    @Override
    public boolean supports(ServiceType serviceType, RuntimeMode mode) {
        return serviceType == ServiceType.FLINK && mode == RuntimeMode.LIVE;
    }

    @Override
    public List<RuntimeJobVO> listJobs(RuntimeQueryContext context) {
        RuntimeSource source = context.getSource();
        JsonNode root = rest.requireJson(source.getBaseUrl(), "/jobs/overview", source.getToken(),
                context.restTimeoutMs(), "Flink JobManager 未提供作业列表（/jobs/overview）");
        List<RuntimeJobVO> jobs = new ArrayList<>();
        for (JsonNode job : array(root, "jobs")) {
            RuntimeJobVO vo = new RuntimeJobVO();
            vo.setSourceId(source.getId());
            vo.setMode(source.getMode().wireName());
            vo.setJobId(text(job, "jid", text(job, "id", "")));
            vo.setName(text(job, "name", ""));
            vo.setState(text(job, "state", ""));
            vo.setStartTime(epoch(job, "start-time"));
            vo.setEndTime(epoch(job, "end-time"));
            vo.setDurationMs(epoch(job, "duration"));
            int failed = job.path("tasks").path("failed").asInt(0);
            int total = job.path("tasks").path("total").asInt(0);
            vo.setNote("tasks " + (total - failed) + "/" + total + " 正常");
            jobs.add(vo);
        }
        return jobs;
    }

    @Override
    public List<RuntimeComponentVO> listComponents(RuntimeQueryContext context) {
        return taskManagers(context, context.familyEnabled(MetricFamily.JVM_GC)
                || hasExplicitTmMetric(context));
    }

    @Override
    public RuntimeSnapshotVO snapshot(RuntimeQueryContext context) {
        RuntimeSource source = context.getSource();
        String jobId = requireJobId(context);
        long capturedAt = System.currentTimeMillis();
        String base = source.getBaseUrl();
        String token = source.getToken();
        long timeout = context.restTimeoutMs();

        JsonNode job = rest.requireJson(base, "/jobs/" + RuntimeRestClient.encode(jobId), token, timeout,
                "Flink 作业不存在或已归档: " + jobId);

        RuntimeSnapshotVO snapshot = new RuntimeSnapshotVO();
        snapshot.setSourceId(source.getId());
        snapshot.setServiceType(source.getServiceType().wireName());
        snapshot.setCluster(source.getCluster());
        snapshot.setMode(source.getMode().wireName());
        snapshot.setJobId(jobId);
        snapshot.setJobName(text(job, "name", ""));
        snapshot.setState(text(job, "state", ""));
        snapshot.setStartTime(epoch(job, "start-time"));
        snapshot.setEndTime(epoch(job, "end-time"));
        snapshot.setDurationMs(epoch(job, "duration"));
        snapshot.setCapturedAt(capturedAt);
        snapshot.setUnavailableFamilies(unavailableFamilies(context));

        List<RuntimeComponentVO> components = new ArrayList<>();
        boolean needTm = context.isIncludeComponents() || context.familyEnabled(MetricFamily.JVM_GC)
                || hasExplicitTmMetric(context);
        if (needTm) {
            components.addAll(taskManagers(context, context.familyEnabled(MetricFamily.JVM_GC)
                    || hasExplicitTmMetric(context)));
        }

        // ---- 作业级指标（checkpoint / throughput / failures）----
        List<MetricSpec> jobSpecs = new ArrayList<>();
        for (MetricFamily family : MetricFamily.values()) {
            if (!context.familyEnabled(family)) {
                continue;
            }
            if (family == MetricFamily.JVM_GC || family == MetricFamily.BACKPRESSURE) {
                continue;
            }
            for (MetricSpec spec : MetricCatalog.forScope(ServiceType.FLINK, family, MetricScope.JOB)) {
                if (spec.getRawMetric() != null && spec.getRawMetric().contains("/")) {
                    continue; // checkpoint.history 由 /checkpoints 组装
                }
                if ("tasks.failed".equals(spec.getRawMetric())) {
                    continue; // 失败任务数直接来自 /jobs/{id}，不走 metrics 端点
                }
                jobSpecs.add(spec);
            }
        }
        Set<String> jobMetricNames = new LinkedHashSet<>();
        for (MetricSpec spec : jobSpecs) {
            if (!"tasks.failed".equals(spec.getRawMetric())) {
                jobMetricNames.add(spec.getRawMetric());
            }
        }
        jobMetricNames.addAll(explicitMetrics(context, false));
        if (!jobMetricNames.isEmpty()) {
            Map<String, List<Double>> values = fetchMetricValues(base,
                    "/jobs/" + RuntimeRestClient.encode(jobId) + "/metrics?get="
                            + RuntimeRestClient.encode(String.join(",", jobMetricNames)),
                    token, timeout);
            snapshot.getJobMetrics().addAll(mapSpecs(jobSpecs, values, Agg.SUM, jobId, JOB,
                    capturedAt, "作业已结束或该指标未注册"));
        }

        // 作业级失败任务数直接来自 /jobs/{id}
        if (context.familyEnabled(MetricFamily.FAILURES)) {
            snapshot.getJobMetrics().add(metric("tasks.failed", MetricFamily.FAILURES, jobId, JOB,
                    "tasks.failed", job.path("tasks").path("failed").asDouble(0), "count",
                    RuntimeMetricVO.AVAILABLE, null, capturedAt));
        }

        // ---- checkpoint 摘要（只在请求了 checkpoint / failures 时才多打一次端点）----
        if (context.familyEnabled(MetricFamily.CHECKPOINT)
                || context.familyEnabled(MetricFamily.FAILURES)) {
            snapshot.setCheckpoint(checkpoint(context, jobId));
        }

        // ---- vertex 级指标（throughput / backpressure / shuffle）----
        if (context.isIncludeVertices()) {
            components.addAll(vertices(context, job, capturedAt, snapshot.getWarnings()));
        }

        if (context.isIncludeConfig()) {
            Map<String, Object> config = new LinkedHashMap<>();
            JsonNode jobConfig = rest.optionalJson(base,
                    "/jobs/" + RuntimeRestClient.encode(jobId) + "/config", token, timeout);
            config.put("jobId", jobId);
            config.put("name", snapshot.getJobName());
            config.put("executionConfig", jobConfig == null ? Map.of()
                    : flatten(jobConfig.path("execution-config")));
            snapshot.setJobConfig(config);
        }

        if (isTerminal(snapshot.getState()) && snapshot.getJobMetrics().isEmpty()
                && snapshot.getComponents().isEmpty()) {
            snapshot.getWarnings().add("作业状态为 " + snapshot.getState()
                    + "，JobManager 已不提供运行时指标；需要历史数据请接入 Flink History Server（P1）");
        }
        snapshot.setComponents(components);
        return snapshot;
    }

    @Override
    public RuntimeConfigVO config(RuntimeQueryContext context) {
        RuntimeSource source = context.getSource();
        String jobId = requireJobId(context);
        long timeout = context.restTimeoutMs();
        String base = source.getBaseUrl();
        String token = source.getToken();

        RuntimeConfigVO vo = new RuntimeConfigVO();
        vo.setSourceId(source.getId());
        vo.setServiceType(source.getServiceType().wireName());
        vo.setCluster(source.getCluster());
        vo.setMode(source.getMode().wireName());
        vo.setJobId(jobId);
        vo.setCapturedAt(System.currentTimeMillis());

        JsonNode jobConfig = rest.optionalJson(base,
                "/jobs/" + RuntimeRestClient.encode(jobId) + "/config", token, timeout);
        if (jobConfig == null) {
            vo.setAvailable(false);
            vo.setReason("未获取到 /jobs/" + jobId + "/config（作业可能已归档或该版本不提供）");
        } else {
            vo.setAvailable(true);
            vo.getSections().put("job", flatten(jobConfig.path("execution-config")));
        }

        JsonNode jmConfig = rest.optionalJson(base, "/jobmanager/config", token, timeout);
        if (jmConfig == null) {
            vo.getWarnings().add("未获取到 /jobmanager/config（集群配置不可用）");
        } else if (jmConfig.isArray()) {
            Map<String, String> values = new LinkedHashMap<>();
            for (JsonNode item : jmConfig) {
                values.put(text(item, "key", ""), text(item, "value", ""));
            }
            vo.getSections().put("jobmanager", values);
        }

        vo.setComponents(taskManagers(context, false));
        vo.getWarnings().add("Flink REST 不暴露单个 TaskManager 的 JVM 启动参数；"
                + "TM 级资源以上述 totalResource/hardware 为准，JVM 参数需从部署配置获取");
        return vo;
    }

    // ------------------------------------------------------------------
    // TaskManager
    // ------------------------------------------------------------------

    private List<RuntimeComponentVO> taskManagers(RuntimeQueryContext context, boolean withMetrics) {
        RuntimeSource source = context.getSource();
        String token = source.getToken();
        long timeout = context.restTimeoutMs();
        JsonNode root = rest.requireJson(source.getBaseUrl(), "/taskmanagers", token, timeout,
                "Flink 未返回 TaskManager 列表（/taskmanagers）");
        List<RuntimeComponentVO> result = new ArrayList<>();
        List<MetricSpec> specs = new ArrayList<>();
        if (withMetrics && context.familyEnabled(MetricFamily.JVM_GC)) {
            specs.addAll(MetricCatalog.forScope(ServiceType.FLINK, MetricFamily.JVM_GC,
                    MetricScope.TASKMANAGER));
        }
        List<String> explicit = explicitMetrics(context, true);
        Set<String> names = new LinkedHashSet<>();
        for (MetricSpec spec : specs) {
            names.add(resolveWildcard(spec.getRawMetric(), context));
        }
        names.addAll(explicit);

        int limit = maxComponents(context);
        for (JsonNode tm : array(root, "taskmanagers")) {
            if (result.size() >= limit) {
                break;
            }
            RuntimeComponentVO component = mapTaskManager(tm);
            if (withMetrics && !names.isEmpty()) {
                String path = "/taskmanagers/" + RuntimeRestClient.encode(component.getId())
                        + "/metrics?get=" + RuntimeRestClient.encode(String.join(",", names));
                Map<String, List<Double>> values = fetchMetricValues(source.getBaseUrl(), path, token, timeout);
                component.setMetrics(mapSpecs(specs, values, Agg.SUM, component.getId(), TM,
                        System.currentTimeMillis(), "TM 未返回该指标（可能版本不支持通配符）"));
                for (String name : explicit) {
                    component.getMetrics().add(explicitMetric(name, values, component.getId(), TM,
                            null, System.currentTimeMillis()));
                }
            }
            result.add(component);
        }
        return result;
    }

    private RuntimeComponentVO mapTaskManager(JsonNode tm) {
        RuntimeComponentVO component = new RuntimeComponentVO();
        component.setType(TM);
        component.setId(text(tm, "id", ""));
        component.setName(component.getId());
        component.setHost(tmHost(tm));
        long heartbeat = tm.path("timeSinceLastHeartbeat").asLong(Long.MAX_VALUE);
        component.setStatus(heartbeat <= HEARTBEAT_STALE_MS ? "ACTIVE" : "STALE");
        component.setSlots(tm.path("slotsNumber").asInt(0));
        component.setFreeSlots(tm.path("freeSlots").asInt(0));
        JsonNode total = tm.path("totalResource");
        JsonNode hardware = tm.path("hardware");
        component.setCores((int) Math.round(number(total, "cpuCores", 0)));
        long memory = Math.round(number(total, "memory", 0));
        if (memory <= 0) {
            memory = Math.round(number(hardware, "physicalMemory", 0));
        }
        component.setMaxMemoryBytes(memory > 0 ? memory : null);
        long managed = Math.round(number(total, "managedMemory", 0));
        component.setUsedMemoryBytes(managed > 0 ? managed : null);
        if ("STALE".equals(component.getStatus())) {
            component.setNote("超过 " + (HEARTBEAT_STALE_MS / 1000) + "s 未收到心跳");
        }
        return component;
    }

    private String tmHost(JsonNode tm) {
        String path = text(tm, "path", "");
        int at = path.indexOf('@');
        if (at < 0) {
            return null;
        }
        String host = path.substring(at + 1);
        int slash = host.indexOf('/');
        if (slash > 0) {
            host = host.substring(0, slash);
        }
        int colon = host.lastIndexOf(':');
        return colon > 0 ? host.substring(0, colon) : host;
    }

    // ------------------------------------------------------------------
    // Vertex
    // ------------------------------------------------------------------

    private List<RuntimeComponentVO> vertices(RuntimeQueryContext context, JsonNode job,
                                              long capturedAt, List<String> warnings) {
        List<MetricFamily> vertexFamilies = new ArrayList<>();
        for (MetricFamily family : MetricFamily.values()) {
            if (context.familyEnabled(family)
                    && (family == MetricFamily.THROUGHPUT || family == MetricFamily.BACKPRESSURE
                    || family == MetricFamily.SHUFFLE)) {
                vertexFamilies.add(family);
            }
        }
        List<String> explicit = explicitMetrics(context, false);
        if (vertexFamilies.isEmpty() && explicit.isEmpty()) {
            return List.of();
        }

        Set<String> names = new LinkedHashSet<>();
        for (MetricFamily family : vertexFamilies) {
            for (MetricSpec spec : MetricCatalog.forScope(ServiceType.FLINK, family, MetricScope.VERTEX)) {
                names.add(spec.getRawMetric());
            }
        }
        names.addAll(explicit);

        RuntimeSource source = context.getSource();
        String base = source.getBaseUrl();
        String token = source.getToken();
        long timeout = context.restTimeoutMs();
        String jobId = context.getJobId();
        List<RuntimeComponentVO> result = new ArrayList<>();
        int limit = Math.min(maxComponents(context), maxVertices(context));

        for (JsonNode vertex : array(job, "vertices")) {
            if (result.size() >= limit) {
                break;
            }
            String vid = text(vertex, "id", "");
            String name = text(vertex, "name", vid);
            RuntimeComponentVO component = new RuntimeComponentVO();
            component.setType(VERTEX);
            component.setId(vid);
            component.setName(name);
            component.setStatus(text(vertex, "status", ""));

            Map<String, List<Double>> values = new LinkedHashMap<>();
            if (!names.isEmpty()) {
                String path = "/jobs/" + RuntimeRestClient.encode(jobId)
                        + "/vertices/" + RuntimeRestClient.encode(vid)
                        + "/subtasks/metrics?get=" + RuntimeRestClient.encode(String.join(",", names));
                values = fetchMetricValues(base, path, token, timeout);
                if (values.isEmpty()) {
                    warnings.add("vertex " + name + " 未返回 metrics（可能作业已结束或指标名不匹配）");
                }
            }
            for (MetricFamily family : vertexFamilies) {
                List<MetricSpec> specs = MetricCatalog.forScope(ServiceType.FLINK, family, MetricScope.VERTEX);
                Agg agg = family == MetricFamily.BACKPRESSURE ? Agg.MAX : Agg.SUM;
                component.getMetrics().addAll(mapSpecs(specs, values, agg, name, VERTEX, capturedAt,
                        "该 vertex 未返回此指标"));
            }
            for (String metricName : explicit) {
                component.getMetrics().add(explicitMetric(metricName, values, name, VERTEX, null, capturedAt));
            }
            if (context.familyEnabled(MetricFamily.FAILURES)) {
                int failed = vertex.path("tasks").path("failed").asInt(0);
                component.getMetrics().add(metric("vertex.tasks.failed", MetricFamily.FAILURES,
                        name, VERTEX, "tasks.failed", failed, "count",
                        RuntimeMetricVO.AVAILABLE, null, capturedAt));
            }
            result.add(component);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Checkpoint
    // ------------------------------------------------------------------

    private RuntimeCheckpointVO checkpoint(RuntimeQueryContext context, String jobId) {
        RuntimeCheckpointVO vo = new RuntimeCheckpointVO();
        RuntimeSource source = context.getSource();
        JsonNode root = rest.optionalJson(source.getBaseUrl(),
                "/jobs/" + RuntimeRestClient.encode(jobId) + "/checkpoints",
                source.getToken(), context.restTimeoutMs());
        if (root == null) {
            vo.setAvailable(false);
            vo.setReason("当前 Flink 版本未提供 /jobs/" + jobId + "/checkpoints 或作业已归档");
            return vo;
        }
        vo.setAvailable(true);
        JsonNode counts = root.path("counts");
        vo.setCompleted(counts.path("completed").asInt(0));
        vo.setFailed(counts.path("failed").asInt(0));
        vo.setInProgress(counts.path("in_progress").asInt(0));
        vo.setRestored(counts.path("restored").asInt(0));
        JsonNode latestFailed = root.path("latest").path("failed");
        if (latestFailed.isObject()) {
            vo.setLatestFailedReason(text(latestFailed, "failure_message",
                    text(latestFailed, "failureMessage", null)));
        }

        List<RuntimeCheckpointVO.Entry> entries = new ArrayList<>();
        for (JsonNode item : array(root, "history")) {
            entries.add(checkpointEntry(item));
        }
        if (entries.isEmpty()) {
            JsonNode latest = root.path("latest");
            for (String key : new String[]{"completed", "failed", "restored", "savepoint"}) {
                JsonNode item = latest.path(key);
                if (item.isObject()) {
                    entries.add(checkpointEntry(item));
                }
            }
        }
        entries.sort(Comparator.comparing(entry -> entry.getId() == null ? -1L : entry.getId(),
                Comparator.reverseOrder()));
        int limit = Math.max(context.getCheckpointHistory(), 1);
        vo.setHistory(entries.size() > limit ? new ArrayList<>(entries.subList(0, limit)) : entries);
        return vo;
    }

    private RuntimeCheckpointVO.Entry checkpointEntry(JsonNode item) {
        RuntimeCheckpointVO.Entry entry = new RuntimeCheckpointVO.Entry();
        entry.setId(item.path("id").isNumber() ? item.path("id").asLong() : null);
        entry.setStatus(text(item, "status", ""));
        entry.setTriggerTimestamp(epoch(item, "trigger_timestamp"));
        entry.setCompletedTimestamp(epoch(item, "latest_ack_timestamp"));
        entry.setDurationMs(epoch(item, "end_to_end_duration"));
        long size = item.path("state_size").asLong(0);
        entry.setSizeBytes(size > 0 ? size : null);
        entry.setFailureMessage(text(item, "failure_message", text(item, "failureMessage", null)));
        return entry;
    }

    // ------------------------------------------------------------------
    // 指标解析
    // ------------------------------------------------------------------

    private enum Agg { SUM, MAX }

    private Map<String, List<Double>> fetchMetricValues(String base, String path, String token, long timeout) {
        JsonNode node = rest.optionalJson(base, path, token, timeout);
        return parseMetricValues(node);
    }

    static Map<String, List<Double>> parseMetricValues(JsonNode node) {
        Map<String, List<Double>> result = new LinkedHashMap<>();
        if (node == null) {
            return result;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String id = item.path("id").asText("");
                Double value = numberOrNull(item.path("value"));
                addValue(result, normalizeId(id), value);
            }
        } else if (node.isObject()) {
            node.fields().forEachRemaining(field -> {
                JsonNode value = field.getValue();
                if (value.isObject()) {
                    value.fields().forEachRemaining(inner ->
                            addValue(result, normalizeId(inner.getKey()), numberOrNull(inner.getValue())));
                } else {
                    addValue(result, normalizeId(field.getKey()), numberOrNull(value));
                }
            });
        }
        return result;
    }

    /** 去掉 {@code "0.numRecordsIn"} 这类 subtask 前缀 */
    private static String normalizeId(String id) {
        if (id == null) {
            return "";
        }
        int dot = id.indexOf('.');
        if (dot > 0 && id.substring(0, dot).chars().allMatch(Character::isDigit)) {
            return id.substring(dot + 1);
        }
        return id;
    }

    private static void addValue(Map<String, List<Double>> result, String key, Double value) {
        if (key == null || key.isBlank() || value == null) {
            return;
        }
        result.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }

    private List<RuntimeMetricVO> mapSpecs(List<MetricSpec> specs, Map<String, List<Double>> values,
                                           Agg agg, String component, String componentType,
                                           long capturedAt, String missingReason) {
        List<RuntimeMetricVO> result = new ArrayList<>();
        for (MetricSpec spec : specs) {
            List<Double> matched = new ArrayList<>();
            for (Map.Entry<String, List<Double>> entry : values.entrySet()) {
                if (matches(spec.getRawMetric(), entry.getKey())) {
                    matched.addAll(entry.getValue());
                }
            }
            String reason = missingReason;
            if (matched.isEmpty() && spec.isWildcard()) {
                reason = "指标未返回：该版本可能不支持通配符，或 GC collector 名称不同"
                        + "（可用 metrics 显式指定，或配置 options.gc-collectors）";
            }
            result.add(toMetric(spec, matched, agg, component, componentType, capturedAt, reason));
        }
        return result;
    }

    private RuntimeMetricVO toMetric(MetricSpec spec, List<Double> values, Agg agg,
                                     String component, String componentType, long capturedAt,
                                     String missingReason) {
        RuntimeMetricVO vo = new RuntimeMetricVO();
        vo.setName(spec.getName());
        vo.setFamily(spec.getFamily().wireName());
        vo.setComponent(component);
        vo.setComponentType(componentType);
        vo.setSourceMetric(spec.getRawMetric());
        vo.setUnit(spec.getUnit());
        vo.setCapturedAt(capturedAt);
        if (values.isEmpty()) {
            vo.setAvailability(RuntimeMetricVO.UNAVAILABLE);
            vo.setReason(missingReason);
            return vo;
        }
        double value = agg == Agg.MAX
                ? values.stream().mapToDouble(Double::doubleValue).max().orElse(0)
                : values.stream().mapToDouble(Double::doubleValue).sum();
        vo.setValue(value);
        vo.setAvailability(value == 0 ? RuntimeMetricVO.ZERO : RuntimeMetricVO.AVAILABLE);
        return vo;
    }

    private RuntimeMetricVO explicitMetric(String rawName, Map<String, List<Double>> values,
                                           String component, String componentType,
                                           String reason, long capturedAt) {
        RuntimeMetricVO vo = new RuntimeMetricVO();
        vo.setName(rawName);
        vo.setFamily("explicit");
        vo.setComponent(component);
        vo.setComponentType(componentType);
        vo.setSourceMetric(rawName);
        vo.setUnit(guessUnit(rawName));
        vo.setCapturedAt(capturedAt);
        List<Double> matched = new ArrayList<>();
        for (Map.Entry<String, List<Double>> entry : values.entrySet()) {
            if (matches(rawName, entry.getKey())) {
                matched.addAll(entry.getValue());
            }
        }
        if (matched.isEmpty()) {
            vo.setAvailability(RuntimeMetricVO.UNAVAILABLE);
            vo.setReason(reason == null ? "该作用域未返回此指标" : reason);
        } else {
            double value = matched.stream().mapToDouble(Double::doubleValue).sum();
            vo.setValue(value);
            vo.setAvailability(value == 0 ? RuntimeMetricVO.ZERO : RuntimeMetricVO.AVAILABLE);
        }
        return vo;
    }

    private RuntimeMetricVO metric(String name, MetricFamily family, String component, String type,
                                   String raw, double value, String unit, String availability,
                                   String reason, long capturedAt) {
        RuntimeMetricVO vo = new RuntimeMetricVO();
        vo.setName(name);
        vo.setFamily(family.wireName());
        vo.setComponent(component);
        vo.setComponentType(type);
        vo.setSourceMetric(raw);
        vo.setValue(value);
        vo.setUnit(unit);
        vo.setAvailability(availability);
        vo.setReason(reason);
        vo.setCapturedAt(capturedAt);
        return vo;
    }

    static boolean matches(String pattern, String value) {
        if (pattern == null || value == null) {
            return false;
        }
        if (!pattern.contains("*")) {
            return pattern.equals(value);
        }
        String regex = java.util.Arrays.stream(pattern.split("\\*", -1))
                .map(Pattern::quote)
                .collect(Collectors.joining(".*"));
        return value.matches(regex);
    }

    static String guessUnit(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        if (lower.contains("persecond")) {
            return lower.contains("byte") ? MetricCatalog.UNIT_BYTES_PER_SECOND
                    : MetricCatalog.UNIT_RECORDS_PER_SECOND;
        }
        if (lower.contains("byte") || lower.contains("size")) {
            return MetricCatalog.UNIT_BYTES;
        }
        if (lower.contains("time") || lower.contains("duration")) {
            return MetricCatalog.UNIT_MS;
        }
        if (lower.contains("record")) {
            return MetricCatalog.UNIT_RECORDS;
        }
        return MetricCatalog.UNIT_COUNT;
    }

    private String resolveWildcard(String raw, RuntimeQueryContext context) {
        if (raw == null || !raw.contains("*")) {
            return raw;
        }
        RuntimeProperties.Options options = context.getSource().getConfig().getOptions();
        List<String> collectors = options == null ? List.of() : options.getGcCollectors();
        if (collectors == null || collectors.isEmpty()) {
            return raw;
        }
        return raw.replace("*", collectors.get(0));
    }

    private boolean hasExplicitTmMetric(RuntimeQueryContext context) {
        for (String metric : context.getExplicitMetrics()) {
            if (metric != null && metric.startsWith("Status.")) {
                return true;
            }
        }
        return false;
    }

    private List<String> explicitMetrics(RuntimeQueryContext context, boolean tm) {
        List<String> result = new ArrayList<>();
        for (String metric : context.getExplicitMetrics()) {
            if (metric == null || metric.isBlank()) {
                continue;
            }
            boolean statusMetric = metric.startsWith("Status.");
            if (tm == statusMetric) {
                result.add(metric);
            }
        }
        return result;
    }

    private Map<String, String> unavailableFamilies(RuntimeQueryContext context) {
        Map<String, String> result = new LinkedHashMap<>();
        for (MetricFamily family : context.getFamilies()) {
            if (!MetricCatalog.supports(ServiceType.FLINK, family)) {
                result.put(family.wireName(), "Flink 不提供该指标族");
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private String requireJobId(RuntimeQueryContext context) {
        String jobId = context.getJobId();
        if (jobId == null || jobId.isBlank()) {
            throw new RuntimeAdapterException(RuntimeAdapterException.Code.BAD_REQUEST,
                    "缺少 jobId（Flink 作业 id）");
        }
        return jobId.trim();
    }

    private int maxComponents(RuntimeQueryContext context) {
        RuntimeProperties.Options options = context.getSource().getConfig().getOptions();
        int override = options == null ? 0 : options.getMaxComponents();
        int value = override > 0 ? override : context.getMaxComponents();
        return Math.max(value, 1);
    }

    private int maxVertices(RuntimeQueryContext context) {
        RuntimeProperties.Options options = context.getSource().getConfig().getOptions();
        int override = options == null ? 0 : options.getMaxVertices();
        int value = override > 0 ? override : context.getMaxVertices();
        return Math.max(value, 1);
    }

    private Iterable<JsonNode> array(JsonNode node, String field) {
        if (node == null) {
            return List.of();
        }
        JsonNode array = node.path(field);
        return array.isArray() ? array : List.of();
    }

    private String text(JsonNode node, String field, String fallback) {
        if (node == null) {
            return fallback;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return fallback;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? fallback : text;
    }

    private Long epoch(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.canConvertToLong()) {
            return null;
        }
        long result = value.asLong();
        return result > 0 ? result : null;
    }

    private double number(JsonNode node, String field, double fallback) {
        if (node == null) {
            return fallback;
        }
        JsonNode value = node.get(field);
        Double parsed = numberOrNull(value);
        return parsed == null ? fallback : parsed;
    }

    private static Double numberOrNull(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        String text = value.asText("");
        if (text.isBlank() || "<unknown>".equalsIgnoreCase(text) || "NaN".equalsIgnoreCase(text)) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, String> flatten(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return result;
        }
        node.fields().forEachRemaining(field -> {
            JsonNode value = field.getValue();
            result.put(field.getKey(), value.isValueNode() ? value.asText() : value.toString());
        });
        return result;
    }

    private boolean isTerminal(String state) {
        return "FINISHED".equalsIgnoreCase(state) || "FAILED".equalsIgnoreCase(state)
                || "CANCELED".equalsIgnoreCase(state) || "CANCELLED".equalsIgnoreCase(state);
    }
}
