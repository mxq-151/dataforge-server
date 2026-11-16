package com.bigdata.modules.telemetry.service.metadata.task;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.Requirement;
import com.bigdata.modules.telemetry.common.model.RequirementKind;
import com.bigdata.modules.telemetry.common.http.SecretResolver;
import com.bigdata.modules.telemetry.common.http.EngineRestClient;
import com.bigdata.modules.telemetry.service.metadata.spi.MetadataCatalogContributor;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataCatalogEntry;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataSection;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataCapability;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataFetchResult;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataQuery;
import com.bigdata.modules.telemetry.service.metadata.model.RawConfigEntry;
import com.bigdata.modules.telemetry.service.metadata.model.RawVersion;
import com.bigdata.modules.telemetry.service.metadata.spi.ServiceMetadataProvider;
import com.bigdata.modules.telemetry.service.metadata.support.ProviderSettings;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.bigdata.modules.telemetry.common.http.SparkShsAttempts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.bigdata.modules.telemetry.util.Urls;

/**
 * Spark 作业的版本与配置 provider：读 SHS 的应用详情与 {@code /environment}。
 *
 * <ul>
 *   <li>version：{@code /api/v1/applications/{applicationId}} 的 {@code appSparkVersion}
 *       （SHS 原值，如 {@code 3.5.9}），不猜、不补；</li>
 *   <li>config：{@code /environment} 的 sparkProperties / hadoopProperties / systemProperties /
 *       classpathEntries，按 section 展平；CPU / 内存分配就是 {@code spark.master}、
 *       {@code spark.driver.memory}、{@code spark.executor.*} 这些键。</li>
 * </ul>
 *
 * <p>{@code ref.applicationId} 必填；配置值原样返回，脱敏由核心 {@code MetadataMasker} 统一完成。</p>
 */
@Component
public class SparkShsMetadataProvider implements ServiceMetadataProvider, MetadataCatalogContributor {

    public static final String PROVIDER_ID = "spark-shs";

    private static final Set<String> SECTIONS = new LinkedHashSet<>(List.of(
            "spark", "hadoop", "system", "classpath"));
    private static final long MAX_RESPONSE_BYTES = 8L * 1024 * 1024;

    private final ProviderSettings settings;
    private final SecretResolver secretResolver;
    private final ObjectMapper objectMapper;

    public SparkShsMetadataProvider(ProviderSettings settings, SecretResolver secretResolver,
                                    ObjectMapper objectMapper) {
        this.settings = settings;
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
                new MetadataSection("spark", "sparkProperties"),
                new MetadataSection("hadoop", "hadoopProperties"),
                new MetadataSection("system", "systemProperties"),
                new MetadataSection("classpath", "classpathEntries"));
        return new MetadataCapability(PROVIDER_ID, "spark-shs", Set.of("spark"), Set.of(),
                true, true, false, settings.intValue(PROVIDER_ID, "order", 30),
                sections, List.of());
    }

    @Override
    public boolean enabled() {
        return settings.enabled(PROVIDER_ID);
    }

    @Override
    public MetadataFetchResult fetch(MetadataQuery query) {
        String applicationId = query.target().ref().get("applicationId");
        if (applicationId == null || applicationId.isBlank()) {
            throw new BackendException(CapabilityCode.INVALID_ARGUMENT,
                    "Spark SHS 配置查询缺少 ref.applicationId",
                    List.of(new Requirement(RequirementKind.TARGET, "ref.applicationId",
                            "补充 Spark applicationId")));
        }
        String baseUrl = settings.required(PROVIDER_ID, "base-url");
        EngineRestClient client = new EngineRestClient(baseUrl, authorizationHeader(),
                query.timeout(), MAX_RESPONSE_BYTES, objectMapper);
        String appId = applicationId.trim();
        // 应用详情同时给出 attempt 路径段与 appSparkVersion，只读一次
        SparkShsAttempts.AttemptRef attempt = SparkShsAttempts.load(client, appId);
        List<RawVersion> versions = new ArrayList<>();
        if (query.wantsVersion() && !attempt.sparkVersion().isBlank()) {
            versions.add(new RawVersion(query.target().target().componentId(),
                    query.target().target().hostId(), attempt.sparkVersion(),
                    null, "Apache", "Apache Spark", "spark-shs:appSparkVersion"));
        }
        List<RawConfigEntry> configs = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (query.wantsConfig()) {
            JsonNode root = client.getJson("/api/v1/applications/" + Urls.encode(appId)
                    + attempt.segment() + "/environment");
            for (String section : requestedSections(query)) {
                if (!SECTIONS.contains(section)) {
                    continue;
                }
                if (configs.size() >= query.maxConfigItems()) {
                    break;
                }
                configs.addAll(flatten(root, section, query.keys(),
                        query.maxConfigItems() - configs.size()));
            }
            if (configs.size() >= query.maxConfigItems()) {
                warnings.add("配置条数达到上限 " + query.maxConfigItems() + "，结果已截断");
            }
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
                new MetadataCatalogEntry("spark", "spark.driver", true, sections,
                        List.of(PROVIDER_ID)),
                new MetadataCatalogEntry("spark", "spark.executor", true, sections,
                        List.of(PROVIDER_ID)));
    }

    // ------------------------------------------------------------------ 取数

    private List<RawConfigEntry> flatten(JsonNode root, String section, List<String> keys,
                                         int limit) {
        String field = switch (section) {
            case "spark" -> "sparkProperties";
            case "hadoop" -> "hadoopProperties";
            case "system" -> "systemProperties";
            default -> "classpathEntries";
        };
        JsonNode node = root.path(field);
        Map<String, String> values = flattenNode(node, section);
        List<RawConfigEntry> entries = new ArrayList<>();
        String source = "spark-shs:" + field;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entries.size() >= limit) {
                break;
            }
            if (!keys.isEmpty() && !keys.contains(entry.getKey())) {
                continue;
            }
            entries.add(new RawConfigEntry(section, entry.getKey(), entry.getValue(),
                    "string", false, source));
        }
        return entries;
    }

    private static Map<String, String> flattenNode(JsonNode node, String section) {
        Map<String, String> result = new LinkedHashMap<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return result;
        }
        if (node.isArray()) {
            int index = 0;
            for (JsonNode item : node) {
                if ("classpath".equals(section)) {
                    result.put("classpath[" + index++ + "]", item.isArray()
                            ? join(item) : item.asText(""));
                    continue;
                }
                if (!item.isArray() || item.size() < 2) {
                    continue;
                }
                String key = item.get(0).asText("");
                if (key.isBlank()) {
                    continue;
                }
                JsonNode value = item.get(1);
                result.put(key, value.isValueNode() ? value.asText() : value.toString());
            }
            return result;
        }
        if (!node.isObject()) {
            return result;
        }
        node.fields().forEachRemaining(field -> result.put(field.getKey(),
                field.getValue().isValueNode() ? field.getValue().asText()
                        : field.getValue().toString()));
        return result;
    }

    private static String join(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : array) {
            values.add(item.asText(""));
        }
        return String.join(":", values);
    }

    private List<String> requestedSections(MetadataQuery query) {
        if (query.sections().isEmpty()) {
            return new ArrayList<>(SECTIONS);
        }
        return query.sections();
    }

    private String authorizationHeader() {
        Map<String, Object> auth = settings.map(PROVIDER_ID, "auth");
        if (auth.isEmpty() || "none".equalsIgnoreCase(String.valueOf(auth.get("type")))) {
            return null;
        }
        String type = String.valueOf(auth.get("type"));
        if ("basic".equalsIgnoreCase(type)) {
            String username = auth.get("username") == null ? "" : String.valueOf(auth.get("username"));
            String password = secretResolver.resolve(null,
                    auth.get("password-ref") == null ? null : String.valueOf(auth.get("password-ref")),
                    PROVIDER_ID + ".password");
            return "Basic " + Base64.getEncoder().encodeToString(
                    (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        }
        if ("bearer".equalsIgnoreCase(type)) {
            String token = secretResolver.resolve(null,
                    auth.get("token-ref") == null ? null : String.valueOf(auth.get("token-ref")),
                    PROVIDER_ID + ".token");
            return "Bearer " + token;
        }
        throw new BackendException(CapabilityCode.NOT_CONFIGURED,
                "未知的 Spark SHS 认证类型: " + type,
                List.of(new Requirement(RequirementKind.CONFIG,
                        "telemetry.metadata.providers." + PROVIDER_ID + ".auth.type",
                        "只支持 none / basic / bearer")));
    }

}
