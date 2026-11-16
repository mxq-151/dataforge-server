package com.bigdata.modules.telemetry.service.metadata.task;

import com.bigdata.modules.telemetry.common.http.AuthorizationHeader;
import com.bigdata.modules.telemetry.common.http.EngineRestClient;
import com.bigdata.modules.telemetry.common.http.SecretResolver;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.Requirement;
import com.bigdata.modules.telemetry.common.model.RequirementKind;
import com.bigdata.modules.telemetry.config.TelemetryBackendProperties;
import com.bigdata.modules.telemetry.config.properties.Auth;
import com.bigdata.modules.telemetry.config.properties.FlinkRestConfig;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataCapability;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataCatalogEntry;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataFetchResult;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataQuery;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataSection;
import com.bigdata.modules.telemetry.service.metadata.model.RawConfigEntry;
import com.bigdata.modules.telemetry.service.metadata.model.RawVersion;
import com.bigdata.modules.telemetry.service.metadata.spi.MetadataCatalogContributor;
import com.bigdata.modules.telemetry.service.metadata.spi.ServiceMetadataProvider;
import com.bigdata.modules.telemetry.util.Urls;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flink 的版本与配置 provider：读 JobManager REST 的 {@code /overview}、{@code /jobmanager/config}、
 * {@code /taskmanagers} 与（可选）{@code /jobs/{jobId}/config}。
 *
 * <ul>
 *   <li>version：{@code /overview.flink-version} 与 {@code flink-commit}，原值返回，不猜；</li>
 *   <li>config section {@code flink}：{@code /jobmanager/config} 的有效配置（含
 *       {@code jobmanager.memory.*}、{@code taskmanager.memory.*}、{@code parallelism.default}）；</li>
 *   <li>config section {@code resources}：{@code /overview} 与 {@code /taskmanagers} 的实时 CPU /
 *       内存分配（slots、cpuCores、hardware、memoryConfiguration），按 TM 实例展开；</li>
 *   <li>config section {@code job}：{@code ref.jobId} 存在时读 {@code /jobs/{jobId}} 与
 *       {@code /jobs/{jobId}/config}，给出作业并行度与执行配置。</li>
 * </ul>
 *
 * <p>版本与集群级配置不依赖 {@code ref.jobId}；只有 {@code job} section 需要。配置值原样返回，
 * 脱敏由核心 {@code MetadataMasker} 统一完成。</p>
 */
@Component
public class FlinkRestMetadataProvider implements ServiceMetadataProvider, MetadataCatalogContributor {

    public static final String PROVIDER_ID = "flink-rest";

    private static final Set<String> SECTIONS = new LinkedHashSet<>(List.of(
            "flink", "resources", "job"));
    private static final long MAX_RESPONSE_BYTES = 8L * 1024 * 1024;

    private final TelemetryBackendProperties properties;
    private final SecretResolver secretResolver;
    private final ObjectMapper objectMapper;

    public FlinkRestMetadataProvider(TelemetryBackendProperties properties,
                                     SecretResolver secretResolver, ObjectMapper objectMapper) {
        this.properties = properties;
        this.secretResolver = secretResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public MetadataCapability capability() {
        List<MetadataSection> sections = List.of(
                new MetadataSection("flink", "jobmanager.config 中的有效配置（含 JM/TM 内存与并行度）"),
                new MetadataSection("resources", "overview + taskmanagers 的 CPU / 内存实际分配"),
                new MetadataSection("job", "jobs/{jobId} 与 execution-config（需 ref.jobId）"));
        return new MetadataCapability(PROVIDER_ID, "flink-rest", Set.of("flink"), Set.of(),
                true, true, false, 20, sections, List.of());
    }

    @Override
    public boolean enabled() {
        return config() != null;
    }

    @Override
    public MetadataFetchResult fetch(MetadataQuery query) {
        FlinkRestConfig config = config();
        if (config == null) {
            throw new BackendException(CapabilityCode.NOT_CONFIGURED,
                    "未配置 Flink REST 地址",
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "telemetry.backends.flink-rest[].base-url",
                            "配置 Flink JobManager REST 地址")));
        }
        EngineRestClient client = new EngineRestClient(config.getBaseUrl(),
                authorization(config.getAuth(), config.getId()),
                query.timeout() == null ? Duration.ofSeconds(10) : query.timeout(),
                MAX_RESPONSE_BYTES, objectMapper);

        List<RawVersion> versions = new ArrayList<>();
        JsonNode overview = client.getJson("/overview");
        if (query.wantsVersion()) {
            String version = overview.path("flink-version").asText("");
            if (!version.isBlank()) {
                versions.add(new RawVersion(query.target().target().componentId(),
                        query.target().target().hostId(), version,
                        textOrNull(overview, "flink-commit"),
                        "Apache", "Apache Flink", "flink-rest:overview.flink-version"));
            }
        }

        List<RawConfigEntry> candidates = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (query.wantsConfig()) {
            String jobId = jobId(query);
            List<String> requested = requestedSections(query);
            for (String section : requested) {
                if (!SECTIONS.contains(section)) {
                    continue;
                }
                switch (section) {
                    case "flink" -> candidates.addAll(flinkConfig(client));
                    case "resources" -> candidates.addAll(resources(overview, client));
                    default -> {
                        if (jobId != null) {
                            candidates.addAll(job(client, jobId, warnings));
                        } else if (query.sections().contains("job")) {
                            warnings.add("未提供 ref.jobId，跳过 job section");
                        }
                    }
                }
            }
        }
        List<RawConfigEntry> configs = filter(candidates, query);
        if (configs.size() > query.maxConfigItems()) {
            configs = new ArrayList<>(configs.subList(0, query.maxConfigItems()));
            warnings.add("配置条数达到上限 " + query.maxConfigItems() + "，结果已截断");
        }
        return new MetadataFetchResult(versions, configs, warnings);
    }

    @Override
    public List<MetadataCatalogEntry> contribute() {
        if (!enabled()) {
            return List.of();
        }
        List<String> sections = new ArrayList<>(SECTIONS);
        return List.of(
                new MetadataCatalogEntry("flink", "flink.jobmanager", true, sections,
                        List.of(PROVIDER_ID)),
                new MetadataCatalogEntry("flink", "flink.taskmanager", true, sections,
                        List.of(PROVIDER_ID)));
    }

    // ------------------------------------------------------------------ 取数

    private List<RawConfigEntry> flinkConfig(EngineRestClient client) {
        JsonNode root = client.getJson("/jobmanager/config");
        Map<String, String> values = new LinkedHashMap<>();
        if (root.isArray()) {
            for (JsonNode item : root) {
                String key = item.path("key").asText("");
                if (key.isBlank()) {
                    continue;
                }
                // 同一键可能来自 config.yaml 与 FLINK_PROPERTIES，后者是生效的覆盖值
                values.put(key, item.path("value").asText(""));
            }
        }
        List<RawConfigEntry> entries = new ArrayList<>();
        values.forEach((key, value) -> entries.add(new RawConfigEntry("flink", key, value,
                "string", false, "flink-rest:jobmanager.config")));
        return entries;
    }

    private List<RawConfigEntry> resources(JsonNode overview, EngineRestClient client) {
        List<RawConfigEntry> entries = new ArrayList<>();
        addNumber(entries, "resources", "cluster.taskmanagers",
                overview.path("taskmanagers"), "number", "flink-rest:overview");
        addNumber(entries, "resources", "cluster.slots.total",
                overview.path("slots-total"), "number", "flink-rest:overview");
        addNumber(entries, "resources", "cluster.slots.available",
                overview.path("slots-available"), "number", "flink-rest:overview");

        JsonNode root = client.getJson("/taskmanagers");
        JsonNode taskmanagers = root.path("taskmanagers");
        if (!taskmanagers.isArray()) {
            return entries;
        }
        for (JsonNode tm : taskmanagers) {
            String id = tm.path("id").asText("");
            if (id.isBlank()) {
                continue;
            }
            String source = "flink-rest:taskmanagers/" + id;
            JsonNode resource = tm.path("totalResource");
            addNumber(entries, "resources", id + ".cpu-cores",
                    resource.path("cpuCores"), "number", source);
            addNumber(entries, "resources", id + ".slots.total",
                    tm.path("slotsNumber"), "number", source);
            addNumber(entries, "resources", id + ".slots.free",
                    tm.path("freeSlots"), "number", source);
            addNumber(entries, "resources", id + ".resource.task-heap-mb",
                    resource.path("taskHeapMemory"), "mb", source);
            addNumber(entries, "resources", id + ".resource.managed-memory-mb",
                    resource.path("managedMemory"), "mb", source);
            addNumber(entries, "resources", id + ".resource.network-memory-mb",
                    resource.path("networkMemory"), "mb", source);
            addNumber(entries, "resources", id + ".hardware.cpu-cores",
                    tm.path("hardware").path("cpuCores"), "number", source);
            addNumber(entries, "resources", id + ".hardware.physical-memory-bytes",
                    tm.path("hardware").path("physicalMemory"), "bytes", source);
            JsonNode memory = tm.path("memoryConfiguration");
            addNumber(entries, "resources", id + ".memory.total-process-bytes",
                    memory.path("totalProcessMemory"), "bytes", source);
            addNumber(entries, "resources", id + ".memory.total-flink-bytes",
                    memory.path("totalFlinkMemory"), "bytes", source);
            addNumber(entries, "resources", id + ".memory.task-heap-bytes",
                    memory.path("taskHeap"), "bytes", source);
            addNumber(entries, "resources", id + ".memory.managed-memory-bytes",
                    memory.path("managedMemory"), "bytes", source);
            addNumber(entries, "resources", id + ".memory.network-memory-bytes",
                    memory.path("networkMemory"), "bytes", source);
            addNumber(entries, "resources", id + ".memory.jvm-metaspace-bytes",
                    memory.path("jvmMetaspace"), "bytes", source);
            addNumber(entries, "resources", id + ".memory.jvm-overhead-bytes",
                    memory.path("jvmOverhead"), "bytes", source);
        }
        return entries;
    }

    private List<RawConfigEntry> job(EngineRestClient client, String jobId, List<String> warnings) {
        List<RawConfigEntry> entries = new ArrayList<>();
        String source = "flink-rest:jobs/" + jobId;
        JsonNode job = client.getJson("/jobs/" + Urls.encode(jobId));
        addText(entries, "job", "job.name", job.path("name"), source);
        addText(entries, "job", "job.state", job.path("state"), source);
        addText(entries, "job", "job.type", job.path("job-type"), source);
        addNumber(entries, "job", "job.max-parallelism", job.path("maxParallelism"),
                "number", source);
        try {
            JsonNode root = client.getJson("/jobs/" + Urls.encode(jobId) + "/config");
            JsonNode execution = root.path("execution-config");
            String configSource = source + "/config";
            addNumber(entries, "job", "execution.job-parallelism",
                    execution.path("job-parallelism"), "number", configSource);
            addText(entries, "job", "execution.restart-strategy",
                    execution.path("restart-strategy"), configSource);
            addText(entries, "job", "execution.object-reuse-mode",
                    execution.path("object-reuse-mode"), configSource);
            JsonNode userConfig = execution.path("user-config");
            if (userConfig.isObject()) {
                userConfig.fields().forEachRemaining(field -> entries.add(new RawConfigEntry(
                        "job", "user." + field.getKey(),
                        field.getValue().isValueNode() ? field.getValue().asText()
                                : field.getValue().toString(),
                        "string", false, configSource)));
            }
        } catch (BackendException e) {
            if (e.getCode() != CapabilityCode.NOT_FOUND) {
                throw e;
            }
            warnings.add("Flink " + jobId + " 无 /jobs/{jobId}/config（老版本或作业已退出），"
                    + "仅返回作业详情");
        }
        return entries;
    }

    // ------------------------------------------------------------------ 辅助

    private static List<RawConfigEntry> filter(List<RawConfigEntry> entries, MetadataQuery query) {
        List<String> sections = query.sections();
        List<String> keys = query.keys();
        List<RawConfigEntry> result = new ArrayList<>();
        for (RawConfigEntry entry : entries) {
            if (!sections.isEmpty() && !sections.contains(entry.section())) {
                continue;
            }
            if (!keys.isEmpty() && !keys.contains(entry.key())) {
                continue;
            }
            result.add(entry);
        }
        return result;
    }

    /** 缺失或非数值字段不造 0，直接跳过。 */
    private static void addNumber(List<RawConfigEntry> entries, String section, String key,
                                  JsonNode value, String valueType, String source) {
        if (value == null || value.isMissingNode() || value.isNull() || !value.isNumber()) {
            return;
        }
        entries.add(new RawConfigEntry(section, key, value.asText(), valueType, false, source));
    }

    private static void addText(List<RawConfigEntry> entries, String section, String key,
                                JsonNode value, String source) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return;
        }
        entries.add(new RawConfigEntry(section, key, value.asText(), "string", false, source));
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
    }

    private static String jobId(MetadataQuery query) {
        String jobId = query.target().ref().get("jobId");
        return jobId == null || jobId.isBlank() ? null : jobId.trim();
    }

    private List<String> requestedSections(MetadataQuery query) {
        if (query.sections().isEmpty()) {
            return new ArrayList<>(SECTIONS);
        }
        return query.sections();
    }

    private FlinkRestConfig config() {
        for (FlinkRestConfig config : properties.getFlinkRest()) {
            if (config != null && config.getBaseUrl() != null && !config.getBaseUrl().isBlank()) {
                return config;
            }
        }
        return null;
    }

    private String authorization(Auth auth, String id) {
        try {
            return AuthorizationHeader.of(auth, secretResolver, "flink-rest." + id);
        } catch (IllegalArgumentException e) {
            throw new BackendException(CapabilityCode.NOT_CONFIGURED,
                    "未知的 Flink REST 认证类型: " + auth.getType(),
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "telemetry.backends.flink-rest[].auth.type",
                            "只支持 none / basic / bearer")));
        }
    }
}
