package com.bigdata.modules.runtime.adapter;

import com.bigdata.modules.logs.model.ServiceType;
import com.bigdata.modules.runtime.config.RuntimeProperties;
import com.bigdata.modules.runtime.model.MetricCatalog;
import com.bigdata.modules.runtime.model.MetricFamily;
import com.bigdata.modules.runtime.model.MetricScope;
import com.bigdata.modules.runtime.model.MetricSpec;
import com.bigdata.modules.runtime.model.RuntimeMode;
import com.bigdata.modules.runtime.model.RuntimeSource;
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
import java.util.List;
import java.util.Map;

/**
 * Spark 运行时指标适配器。
 *
 * <h3>两种模式，不混为一个数据源</h3>
 * <ul>
 *   <li>{@link RuntimeMode#HISTORY}：Spark History Server。作业结束后可查；
 *       {@code /allexecutors} 含已退出 executor，{@code /executors} 在应用结束后通常为空。</li>
 *   <li>{@link RuntimeMode#LIVE}：live 应用 UI（tracking URL）。作业运行中可查；
 *       以 {@code /executors} 为主，{@code /allexecutors} 作为回退。</li>
 * </ul>
 *
 * <h3>端点</h3>
 * <ul>
 *   <li>{@code GET /api/v1/applications}（history 应用发现）</li>
 *   <li>{@code GET /api/v1/applications/{appId}}（attempts / 状态）</li>
 *   <li>{@code GET /api/v1/applications/{appId}/executors | /allexecutors}</li>
 *   <li>{@code GET /api/v1/applications/{appId}/stages}、{@code /jobs}、{@code /environment}</li>
 *   <li>YARN：{@code GET {rm}/ws/v1/cluster/apps?applicationTypes=SPARK&states=...} 发现 appId
 *       与 {@code trackingUrl}；{@code GET {rm}/ws/v1/cluster/apps/{appId}} 取单个应用</li>
 * </ul>
 *
 * <p>Spark REST 不提供 checkpoint 与背压指标，二者会在响应的 {@code unavailableFamilies}
 * 中明确标注原因，而不是返回 0。</p>
 */
@Component
public class SparkRuntimeAdapter implements RuntimeAdapter {

    private static final String EXECUTOR = "executor";
    private static final String STAGE = "stage";
    private static final String JOB = "job";

    private final RuntimeRestClient rest;

    public SparkRuntimeAdapter(RuntimeRestClient rest) {
        this.rest = rest;
    }

    @Override
    public boolean supports(ServiceType serviceType, RuntimeMode mode) {
        return serviceType == ServiceType.SPARK;
    }

    @Override
    public List<RuntimeJobVO> listJobs(RuntimeQueryContext context) {
        RuntimeSource source = context.getSource();
        if (source.getMode() == RuntimeMode.HISTORY) {
            return historyJobs(context);
        }
        return liveJobs(context);
    }

    private List<RuntimeJobVO> historyJobs(RuntimeQueryContext context) {
        RuntimeSource source = context.getSource();
        JsonNode root = rest.requireJson(source.getBaseUrl(), "/api/v1/applications",
                source.getToken(), context.restTimeoutMs(), "Spark History Server 未提供 /api/v1/applications");
        List<RuntimeJobVO> jobs = new ArrayList<>();
        for (JsonNode app : root.isArray() ? root : List.<JsonNode>of()) {
            jobs.add(toJob(source, app));
        }
        return jobs;
    }

    private List<RuntimeJobVO> liveJobs(RuntimeQueryContext context) {
        RuntimeSource source = context.getSource();
        RuntimeProperties.Options options = options(source);
        String rm = trim(options.getYarnRmUrl());
        if (!rm.isBlank()) {
            String path = "/ws/v1/cluster/apps?applicationTypes=SPARK"
                    + "&states=NEW,NEW_SAVING,SUBMITTED,ACCEPTED,RUNNING";
            JsonNode root = rest.requireJson(rm, path, source.getToken(), context.restTimeoutMs(),
                    "YARN ResourceManager 未提供应用列表");
            List<RuntimeJobVO> jobs = new ArrayList<>();
            JsonNode apps = root.path("apps");
            if (apps.isObject()) {
                for (JsonNode app : array(apps, "app")) {
                    RuntimeJobVO vo = new RuntimeJobVO();
                    vo.setSourceId(source.getId());
                    vo.setMode(source.getMode().wireName());
                    vo.setJobId(text(app, "id", ""));
                    vo.setName(text(app, "name", ""));
                    vo.setState(text(app, "state", ""));
                    vo.setStartTime(epoch(app, "startedTime"));
                    vo.setEndTime(epoch(app, "finishedTime"));
                    vo.setTrackingUrl(text(app, "trackingUrl", null));
                    vo.setNote("applicationType=" + text(app, "applicationType", "-"));
                    jobs.add(vo);
                }
            }
            return jobs;
        }
        String appId = firstNonBlank(context.getJobId(), options.getAppId());
        if (appId == null) {
            throw new RuntimeAdapterException(RuntimeAdapterException.Code.BAD_REQUEST,
                    "Spark live 作业发现需要 ref.jobId（appId），或配置 options.yarn-rm-url / options.app-id");
        }
        JsonNode app = rest.requireJson(context.getSource().getBaseUrl(),
                "/api/v1/applications/" + RuntimeRestClient.encode(appId),
                source.getToken(), context.restTimeoutMs(), "Spark 应用不存在: " + appId);
        return List.of(toJob(source, app));
    }

    private RuntimeJobVO toJob(RuntimeSource source, JsonNode app) {
        RuntimeJobVO vo = new RuntimeJobVO();
        vo.setSourceId(source.getId());
        vo.setMode(source.getMode().wireName());
        vo.setJobId(text(app, "id", ""));
        vo.setName(text(app, "name", ""));
        JsonNode attempts = app.path("attempts");
        JsonNode last = attempts.isArray() && !attempts.isEmpty()
                ? attempts.get(attempts.size() - 1) : null;
        boolean completed = last != null && last.path("completed").asBoolean(false);
        vo.setState(completed ? "FINISHED" : "RUNNING");
        if (last != null) {
            vo.setStartTime(epoch(last, "startTime"));
            vo.setEndTime(epoch(last, "endTime"));
            vo.setDurationMs(epoch(last, "duration"));
        }
        vo.setNote("attempts=" + (attempts.isArray() ? attempts.size() : 0));
        return vo;
    }

    @Override
    public List<RuntimeComponentVO> listComponents(RuntimeQueryContext context) {
        boolean withMetrics = wantsExecutorMetrics(context);
        return executors(context, withMetrics, null);
    }

    @Override
    public RuntimeSnapshotVO snapshot(RuntimeQueryContext context) {
        RuntimeSource source = context.getSource();
        String appId = requireAppId(context);
        long capturedAt = System.currentTimeMillis();
        String base = base(context);
        String token = source.getToken();
        long timeout = context.restTimeoutMs();

        JsonNode app = rest.requireJson(base, "/api/v1/applications/" + RuntimeRestClient.encode(appId),
                token, timeout, "Spark 应用不存在或 History Server 未保留: " + appId);

        RuntimeSnapshotVO snapshot = new RuntimeSnapshotVO();
        snapshot.setSourceId(source.getId());
        snapshot.setServiceType(source.getServiceType().wireName());
        snapshot.setCluster(source.getCluster());
        snapshot.setMode(source.getMode().wireName());
        snapshot.setJobId(appId);
        snapshot.setJobName(text(app, "name", ""));
        snapshot.setCapturedAt(capturedAt);
        fillState(snapshot, app);
        snapshot.setUnavailableFamilies(unavailableFamilies(context));

        List<String> warnings = new ArrayList<>();
        boolean needExecutors = context.isIncludeComponents() || wantsExecutorMetrics(context);
        if (needExecutors) {
            snapshot.getComponents().addAll(executors(context, wantsExecutorMetrics(context), warnings));
        }

        if (context.familyEnabled(MetricFamily.FAILURES)) {
            JsonNode attempts = app.path("attempts");
            int attemptCount = attempts.isArray() ? attempts.size() : 0;
            snapshot.getJobMetrics().add(metric("application.attempt.count", MetricFamily.FAILURES,
                    "job", JOB, "attempts", attemptCount, MetricCatalog.UNIT_COUNT,
                    attemptCount == 0 ? RuntimeMetricVO.ZERO : RuntimeMetricVO.AVAILABLE, null, capturedAt));
        }

        if (context.isIncludeVertices()) {
            snapshot.getComponents().addAll(stages(context, capturedAt, warnings));
        }

        if (context.familyEnabled(MetricFamily.FAILURES)) {
            snapshot.getJobMetrics().addAll(jobFailures(context, capturedAt, warnings));
        }

        if (context.familyEnabled(MetricFamily.CHECKPOINT)) {
            RuntimeCheckpointVO checkpoint = new RuntimeCheckpointVO();
            checkpoint.setAvailable(false);
            checkpoint.setReason(MetricCatalog.unavailableFamilies(ServiceType.SPARK)
                    .get(MetricFamily.CHECKPOINT.wireName()));
            snapshot.setCheckpoint(checkpoint);
        }

        if (context.isIncludeConfig()) {
            snapshot.setJobConfig(configSections(context, appId, app, warnings));
        }

        snapshot.setWarnings(warnings);
        return snapshot;
    }

    @Override
    public RuntimeConfigVO config(RuntimeQueryContext context) {
        RuntimeSource source = context.getSource();
        String appId = requireAppId(context);
        String base = base(context);
        String token = source.getToken();
        long timeout = context.restTimeoutMs();

        RuntimeConfigVO vo = new RuntimeConfigVO();
        vo.setSourceId(source.getId());
        vo.setServiceType(source.getServiceType().wireName());
        vo.setCluster(source.getCluster());
        vo.setMode(source.getMode().wireName());
        vo.setJobId(appId);
        vo.setCapturedAt(System.currentTimeMillis());

        JsonNode app = rest.requireJson(base, "/api/v1/applications/" + RuntimeRestClient.encode(appId),
                token, timeout, "Spark 应用不存在或 History Server 未保留: " + appId);
        Map<String, String> application = new LinkedHashMap<>();
        application.put("appId", appId);
        application.put("name", text(app, "name", ""));
        application.put("attempts", String.valueOf(app.path("attempts").size()));
        application.put("mode", source.getMode().wireName());
        vo.getSections().put("application", application);

        JsonNode environment = rest.optionalJson(base,
                "/api/v1/applications/" + RuntimeRestClient.encode(appId) + "/environment", token, timeout);
        if (environment == null) {
            vo.setAvailable(false);
            vo.setReason("未获取到 /applications/" + appId + "/environment（live UI 或该版本可能不提供）");
        } else {
            vo.setAvailable(true);
            vo.getSections().put("sparkProperties", flatten(environment.path("sparkProperties")));
            vo.getSections().put("hadoopProperties", flatten(environment.path("hadoopProperties")));
            vo.getSections().put("systemProperties", flatten(environment.path("systemProperties")));
            Map<String, String> classpath = new LinkedHashMap<>();
            JsonNode entries = environment.path("classpathEntries");
            if (entries.isArray()) {
                int index = 0;
                for (JsonNode entry : entries) {
                    classpath.put("classpath[" + index++ + "]", entry.isArray()
                            ? String.join(":", texts(entry)) : entry.asText(""));
                }
            }
            vo.getSections().put("classpath", classpath);
        }

        vo.setComponents(executors(context, false, vo.getWarnings()));
        return vo;
    }

    // ------------------------------------------------------------------
    // Executors
    // ------------------------------------------------------------------

    private List<RuntimeComponentVO> executors(RuntimeQueryContext context, boolean withMetrics,
                                               List<String> warnings) {
        RuntimeSource source = context.getSource();
        String appId = requireAppId(context);
        String base = base(context);
        String token = source.getToken();
        long timeout = context.restTimeoutMs();

        boolean history = source.getMode() == RuntimeMode.HISTORY;
        String primary = history ? "allexecutors" : "executors";
        String fallback = history ? "executors" : "allexecutors";
        String root = "/api/v1/applications/" + RuntimeRestClient.encode(appId) + "/";
        JsonNode node = rest.optionalJson(base, root + primary, token, timeout);
        String used = primary;
        if (node == null || (node.isArray() && node.isEmpty())) {
            JsonNode fallbackNode = rest.optionalJson(base, root + fallback, token, timeout);
            if (fallbackNode != null && fallbackNode.isArray() && !fallbackNode.isEmpty()) {
                node = fallbackNode;
                used = fallback;
                if (warnings != null) {
                    warnings.add("已回退到 /" + fallback + "（/" + primary + " 为空或不可用）");
                }
            }
        }
        if (node == null || !node.isArray()) {
            throw new RuntimeAdapterException(RuntimeAdapterException.Code.UNAVAILABLE,
                    "Spark executor 端点不可用（live: /executors；history: /allexecutors）");
        }

        List<MetricSpec> gcSpecs = withMetrics && context.familyEnabled(MetricFamily.JVM_GC)
                ? MetricCatalog.forScope(ServiceType.SPARK, MetricFamily.JVM_GC, MetricScope.EXECUTOR)
                : List.of();
        List<MetricSpec> throughputSpecs = withMetrics && context.familyEnabled(MetricFamily.THROUGHPUT)
                ? MetricCatalog.forScope(ServiceType.SPARK, MetricFamily.THROUGHPUT, MetricScope.EXECUTOR)
                : List.of();
        List<MetricSpec> failureSpecs = withMetrics && context.familyEnabled(MetricFamily.FAILURES)
                ? MetricCatalog.forScope(ServiceType.SPARK, MetricFamily.FAILURES, MetricScope.EXECUTOR)
                : List.of();

        List<RuntimeComponentVO> result = new ArrayList<>();
        int limit = maxComponents(context);
        long capturedAt = System.currentTimeMillis();
        for (JsonNode executor : node) {
            if (result.size() >= limit) {
                break;
            }
            RuntimeComponentVO component = new RuntimeComponentVO();
            component.setType(EXECUTOR);
            component.setId(text(executor, "id", ""));
            component.setName(component.getId());
            component.setHost(text(executor, "hostPort", null));
            component.setCores(executor.path("totalCores").asInt(0));
            component.setMaxMemoryBytes(longOrNull(executor.path("maxMemory")));
            component.setUsedMemoryBytes(longOrNull(executor.path("memoryUsed")));
            component.setStartTime(epoch(executor, "addTime"));
            component.setEndTime(epoch(executor, "removeTime"));
            component.setStatus(executorStatus(executor));
            String removeReason = text(executor, "removeReason", null);
            if (removeReason != null) {
                component.setNote(removeReason);
            }
            if (withMetrics) {
                applySpecs(gcSpecs, executor, component, capturedAt);
                applySpecs(throughputSpecs, executor, component, capturedAt);
                applySpecs(failureSpecs, executor, component, capturedAt);
                applyExplicit(context, executor, component, capturedAt);
                addGcRatio(component, capturedAt);
            }
            result.add(component);
        }
        if (result.isEmpty() && warnings != null) {
            warnings.add("应用当前没有 executor（可能刚启动或已结束且历史被回收）");
        }
        return result;
    }

    private String executorStatus(JsonNode executor) {
        if (executor.path("isActive").asBoolean(false)) {
            return "ACTIVE";
        }
        long removeTime = executor.path("removeTime").asLong(0);
        return removeTime > 0 ? "REMOVED" : "DEAD";
    }

    private void addGcRatio(RuntimeComponentVO component, long capturedAt) {
        Double gc = null;
        Double run = null;
        for (RuntimeMetricVO metric : component.getMetrics()) {
            if ("executor.gc.time".equals(metric.getName())) {
                gc = metric.getValue();
            } else if ("executor.run.time".equals(metric.getName())) {
                run = metric.getValue();
            }
        }
        if (gc == null || run == null || run <= 0) {
            return;
        }
        double ratio = gc / run;
        component.getMetrics().add(metric("executor.gc.ratio", MetricFamily.JVM_GC, component.getId(),
                EXECUTOR, "totalGCTime/totalDuration", ratio, MetricCatalog.UNIT_RATIO,
                RuntimeMetricVO.AVAILABLE, null, capturedAt));
    }

    // ------------------------------------------------------------------
    // Stages / Jobs
    // ------------------------------------------------------------------

    private List<RuntimeComponentVO> stages(RuntimeQueryContext context, long capturedAt,
                                            List<String> warnings) {
        boolean needed = context.familyEnabled(MetricFamily.THROUGHPUT)
                || context.familyEnabled(MetricFamily.SHUFFLE)
                || context.familyEnabled(MetricFamily.FAILURES)
                || !context.getExplicitMetrics().isEmpty();
        if (!needed) {
            return List.of();
        }
        RuntimeSource source = context.getSource();
        String base = base(context);
        String appId = context.getJobId();
        JsonNode root = rest.optionalJson(base,
                "/api/v1/applications/" + RuntimeRestClient.encode(appId) + "/stages",
                source.getToken(), context.restTimeoutMs());
        if (root == null || !root.isArray()) {
            warnings.add("未获取到 /applications/" + appId + "/stages，stage 级指标不可用");
            return List.of();
        }

        Map<Integer, JsonNode> latestByStage = new LinkedHashMap<>();
        for (JsonNode stage : root) {
            int stageId = stage.path("stageId").asInt(-1);
            if (stageId < 0) {
                continue;
            }
            JsonNode existing = latestByStage.get(stageId);
            if (existing == null || stage.path("attemptId").asInt(0) >= existing.path("attemptId").asInt(0)) {
                latestByStage.put(stageId, stage);
            }
        }

        List<MetricSpec> throughput = MetricCatalog.forScope(ServiceType.SPARK,
                MetricFamily.THROUGHPUT, MetricScope.STAGE);
        List<MetricSpec> shuffle = MetricCatalog.forScope(ServiceType.SPARK,
                MetricFamily.SHUFFLE, MetricScope.STAGE);
        List<MetricSpec> failures = MetricCatalog.forScope(ServiceType.SPARK,
                MetricFamily.FAILURES, MetricScope.STAGE);

        List<RuntimeComponentVO> result = new ArrayList<>();
        int limit = Math.min(maxComponents(context), maxStages(context));
        List<JsonNode> ordered = new ArrayList<>(latestByStage.values());
        ordered.sort(Comparator.comparingInt(stage -> stage.path("stageId").asInt(0)));
        for (JsonNode stage : ordered) {
            if (result.size() >= limit) {
                break;
            }
            RuntimeComponentVO component = new RuntimeComponentVO();
            component.setType(STAGE);
            int stageId = stage.path("stageId").asInt(0);
            int attemptId = stage.path("attemptId").asInt(0);
            component.setId("stage-" + stageId + "-" + attemptId);
            component.setName(text(stage, "name", "stage-" + stageId));
            component.setStatus(text(stage, "status", ""));
            component.setStartTime(epoch(stage, "submissionTime"));
            component.setEndTime(epoch(stage, "completionTime"));
            String failureReason = text(stage, "failureReason", null);
            if (failureReason != null) {
                component.setNote(failureReason);
            }
            if (context.familyEnabled(MetricFamily.THROUGHPUT)) {
                applySpecs(throughput, stage, component, capturedAt);
            }
            if (context.familyEnabled(MetricFamily.SHUFFLE)) {
                applySpecs(shuffle, stage, component, capturedAt);
            }
            if (context.familyEnabled(MetricFamily.FAILURES)) {
                applySpecs(failures, stage, component, capturedAt);
                // attemptId 从 0 开始，尝试次数 = attemptId + 1
                for (RuntimeMetricVO metric : component.getMetrics()) {
                    if ("stage.attempt.count".equals(metric.getName())) {
                        double attempts = attemptId + 1;
                        metric.setValue(attempts);
                        metric.setAvailability(RuntimeMetricVO.AVAILABLE);
                        metric.setReason(null);
                    }
                }
            }
            applyExplicit(context, stage, component, capturedAt);
            result.add(component);
        }
        return result;
    }

    private List<RuntimeMetricVO> jobFailures(RuntimeQueryContext context, long capturedAt,
                                              List<String> warnings) {
        RuntimeSource source = context.getSource();
        String base = base(context);
        String appId = context.getJobId();
        JsonNode root = rest.optionalJson(base,
                "/api/v1/applications/" + RuntimeRestClient.encode(appId) + "/jobs",
                source.getToken(), context.restTimeoutMs());
        if (root == null || !root.isArray()) {
            warnings.add("未获取到 /applications/" + appId + "/jobs，作业级失败指标不可用");
            return List.of();
        }
        int failedTasks = 0;
        int killedTasks = 0;
        int failedStages = 0;
        for (JsonNode job : root) {
            failedTasks += job.path("numFailedTasks").asInt(0);
            killedTasks += job.path("numKilledTasks").asInt(0);
            failedStages += job.path("numFailedStages").asInt(0);
        }
        List<RuntimeMetricVO> result = new ArrayList<>();
        result.add(metric("job.tasks.failed", MetricFamily.FAILURES, "job", JOB, "numFailedTasks",
                failedTasks, MetricCatalog.UNIT_COUNT,
                failedTasks == 0 ? RuntimeMetricVO.ZERO : RuntimeMetricVO.AVAILABLE, null, capturedAt));
        result.add(metric("job.tasks.killed", MetricFamily.FAILURES, "job", JOB, "numKilledTasks",
                killedTasks, MetricCatalog.UNIT_COUNT,
                killedTasks == 0 ? RuntimeMetricVO.ZERO : RuntimeMetricVO.AVAILABLE, null, capturedAt));
        result.add(metric("job.failedStages", MetricFamily.FAILURES, "job", JOB, "numFailedStages",
                failedStages, MetricCatalog.UNIT_COUNT,
                failedStages == 0 ? RuntimeMetricVO.ZERO : RuntimeMetricVO.AVAILABLE, null, capturedAt));
        return result;
    }

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    private Map<String, Object> configSections(RuntimeQueryContext context, String appId,
                                               JsonNode app, List<String> warnings) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("application", Map.of(
                "appId", appId,
                "name", text(app, "name", ""),
                "attempts", app.path("attempts").size(),
                "mode", context.getSource().getMode().wireName()));
        JsonNode environment = rest.optionalJson(base(context),
                "/api/v1/applications/" + RuntimeRestClient.encode(appId) + "/environment",
                context.getSource().getToken(), context.restTimeoutMs());
        if (environment == null) {
            warnings.add("environment 端点不可用，作业配置未包含在快照中");
            return result;
        }
        result.put("sparkProperties", flatten(environment.path("sparkProperties")));
        result.put("hadoopProperties", flatten(environment.path("hadoopProperties")));
        result.put("systemProperties", flatten(environment.path("systemProperties")));
        return result;
    }

    // ------------------------------------------------------------------
    // 指标映射
    // ------------------------------------------------------------------

    private void applySpecs(List<MetricSpec> specs, JsonNode node, RuntimeComponentVO component,
                            long capturedAt) {
        for (MetricSpec spec : specs) {
            if (spec.getRawMetric() != null && spec.getRawMetric().contains("/")) {
                continue; // 计算型指标（如 GC 占比）由 addGcRatio 单独生成
            }
            JsonNode valueNode = node.get(spec.getRawMetric());
            Double value = numberOrNull(valueNode);
            RuntimeMetricVO metric = new RuntimeMetricVO();
            metric.setName(spec.getName());
            metric.setFamily(spec.getFamily().wireName());
            metric.setComponent(component.getId());
            metric.setComponentType(component.getType());
            metric.setSourceMetric(spec.getRawMetric());
            metric.setUnit(spec.getUnit());
            metric.setCapturedAt(capturedAt);
            if (value == null) {
                metric.setAvailability(RuntimeMetricVO.UNAVAILABLE);
                metric.setReason("组件未返回该字段（历史应用可能已回收该信息）");
            } else {
                metric.setValue(value);
                metric.setAvailability(value == 0 ? RuntimeMetricVO.ZERO : RuntimeMetricVO.AVAILABLE);
            }
            component.getMetrics().add(metric);
        }
    }

    private void applyExplicit(RuntimeQueryContext context, JsonNode node,
                               RuntimeComponentVO component, long capturedAt) {
        for (String raw : context.getExplicitMetrics()) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            Double value = numberOrNull(node.get(raw));
            RuntimeMetricVO metric = new RuntimeMetricVO();
            metric.setName(raw);
            metric.setFamily("explicit");
            metric.setComponent(component.getId());
            metric.setComponentType(component.getType());
            metric.setSourceMetric(raw);
            metric.setUnit(FlinkRuntimeAdapter.guessUnit(raw));
            metric.setCapturedAt(capturedAt);
            if (value == null) {
                metric.setAvailability(RuntimeMetricVO.UNAVAILABLE);
                metric.setReason("该组件未返回此字段");
            } else {
                metric.setValue(value);
                metric.setAvailability(value == 0 ? RuntimeMetricVO.ZERO : RuntimeMetricVO.AVAILABLE);
            }
            component.getMetrics().add(metric);
        }
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

    // ------------------------------------------------------------------
    // 地址解析
    // ------------------------------------------------------------------

    /**
     * live 模式的访问地址：base-url 优先；否则按 tracking-url-template 或 YARN RM 反查 trackingUrl。
     */
    private String base(RuntimeQueryContext context) {
        RuntimeSource source = context.getSource();
        String configured = trim(source.getBaseUrl());
        if (!configured.isBlank()) {
            return configured;
        }
        RuntimeProperties.Options options = options(source);
        if (source.getMode() == RuntimeMode.HISTORY) {
            throw new RuntimeAdapterException(RuntimeAdapterException.Code.UNAVAILABLE,
                    "未配置 Spark History Server 的 base-url，无法查询已结束作业");
        }
        String appId = firstNonBlank(context.getJobId(), options.getAppId());
        if (appId != null) {
            String template = trim(options.getTrackingUrlTemplate());
            if (!template.isBlank()) {
                return template.replace("{appId}", appId);
            }
            String rm = trim(options.getYarnRmUrl());
            if (!rm.isBlank()) {
                JsonNode app = rest.requireJson(rm,
                        "/ws/v1/cluster/apps/" + RuntimeRestClient.encode(appId),
                        source.getToken(), context.restTimeoutMs(),
                        "YARN 中不存在应用: " + appId);
                String tracking = text(app.path("app"), "trackingUrl", "");
                if (tracking.isBlank()) {
                    throw new RuntimeAdapterException(RuntimeAdapterException.Code.UNAVAILABLE,
                            "YARN 未返回 trackingUrl（应用可能尚未启动 Spark UI）");
                }
                return tracking;
            }
        }
        throw new RuntimeAdapterException(RuntimeAdapterException.Code.UNAVAILABLE,
                "未配置 Spark live UI 的 base-url、options.tracking-url-template 或 options.yarn-rm-url");
    }

    private void fillState(RuntimeSnapshotVO snapshot, JsonNode app) {
        JsonNode attempts = app.path("attempts");
        if (!attempts.isArray() || attempts.isEmpty()) {
            snapshot.setState("UNKNOWN");
            return;
        }
        JsonNode last = attempts.get(attempts.size() - 1);
        snapshot.setState(last.path("completed").asBoolean(false) ? "FINISHED" : "RUNNING");
        snapshot.setStartTime(epoch(last, "startTime"));
        snapshot.setEndTime(epoch(last, "endTime"));
        snapshot.setDurationMs(epoch(last, "duration"));
    }

    private Map<String, String> unavailableFamilies(RuntimeQueryContext context) {
        Map<String, String> result = new LinkedHashMap<>();
        Map<String, String> all = MetricCatalog.unavailableFamilies(ServiceType.SPARK);
        for (MetricFamily family : context.getFamilies()) {
            String reason = all.get(family.wireName());
            if (reason != null) {
                result.put(family.wireName(), reason);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private RuntimeProperties.Options options(RuntimeSource source) {
        RuntimeProperties.Options options = source.getConfig().getOptions();
        return options == null ? new RuntimeProperties.Options() : options;
    }

    private String requireAppId(RuntimeQueryContext context) {
        String appId = firstNonBlank(context.getJobId(), options(context.getSource()).getAppId());
        if (appId == null) {
            throw new RuntimeAdapterException(RuntimeAdapterException.Code.BAD_REQUEST,
                    "缺少 appId（Spark application id）");
        }
        return appId;
    }

    private boolean wantsExecutorMetrics(RuntimeQueryContext context) {
        return context.familyEnabled(MetricFamily.JVM_GC)
                || context.familyEnabled(MetricFamily.THROUGHPUT)
                || context.familyEnabled(MetricFamily.FAILURES)
                || !context.getExplicitMetrics().isEmpty();
    }

    private int maxComponents(RuntimeQueryContext context) {
        RuntimeProperties.Options options = options(context.getSource());
        int override = options.getMaxComponents();
        int value = override > 0 ? override : context.getMaxComponents();
        return Math.max(value, 1);
    }

    private int maxStages(RuntimeQueryContext context) {
        int configured = options(context.getSource()).getMaxStages();
        return Math.max(configured, 1);
    }

    private Iterable<JsonNode> array(JsonNode node, String field) {
        if (node == null) {
            return List.of();
        }
        JsonNode array = node.path(field);
        if (array.isArray()) {
            return array;
        }
        return List.of();
    }

    private List<String> texts(JsonNode array) {
        List<String> result = new ArrayList<>();
        for (JsonNode item : array) {
            result.add(item.asText(""));
        }
        return result;
    }

    private Map<String, String> flatten(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        if (node == null) {
            return result;
        }
        // Spark UI / History Server 的 /environment 返回的是 “键值对数组”：
        //   "sparkProperties": [ ["spark.app.name","x"], ... ]
        // 而部分自定义实现返回对象形式 { "spark.app.name": "x" }，两种都要支持。
        if (node.isArray()) {
            for (JsonNode entry : node) {
                if (!entry.isArray() || entry.size() < 2) {
                    continue;
                }
                String key = entry.get(0).asText("");
                if (key.isBlank()) {
                    continue;
                }
                JsonNode value = entry.get(1);
                result.put(key, value.isValueNode() ? value.asText() : value.toString());
            }
            return result;
        }
        if (!node.isObject()) {
            return result;
        }
        node.fields().forEachRemaining(field -> result.put(field.getKey(),
                field.getValue().isValueNode() ? field.getValue().asText() : field.getValue().toString()));
        return result;
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

    private Long longOrNull(JsonNode value) {
        if (value == null || value.isNull() || !value.canConvertToLong()) {
            return null;
        }
        long result = value.asLong();
        return result >= 0 ? result : null;
    }

    private Double numberOrNull(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        if (value.isBoolean()) {
            return value.asBoolean() ? 1D : 0D;
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

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
