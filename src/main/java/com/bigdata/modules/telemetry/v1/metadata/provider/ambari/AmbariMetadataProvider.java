package com.bigdata.modules.telemetry.v1.metadata.provider.ambari;

import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.Requirement;
import com.bigdata.modules.telemetry.model.RequirementKind;
import com.bigdata.modules.telemetry.shared.SecretResolver;
import com.bigdata.modules.telemetry.v1.backend.engine.EngineRestClient;
import com.bigdata.modules.telemetry.v1.metadata.catalog.MetadataCatalogContributor;
import com.bigdata.modules.telemetry.v1.metadata.catalog.MetadataCatalogEntry;
import com.bigdata.modules.telemetry.v1.metadata.model.MaskingMode;
import com.bigdata.modules.telemetry.v1.metadata.model.MaskingRule;
import com.bigdata.modules.telemetry.v1.metadata.model.MetadataSection;
import com.bigdata.modules.telemetry.v1.metadata.spi.MetadataCapability;
import com.bigdata.modules.telemetry.v1.metadata.spi.MetadataFetchResult;
import com.bigdata.modules.telemetry.v1.metadata.spi.MetadataQuery;
import com.bigdata.modules.telemetry.v1.metadata.spi.RawConfigEntry;
import com.bigdata.modules.telemetry.v1.metadata.spi.RawVersion;
import com.bigdata.modules.telemetry.v1.metadata.spi.ServiceMetadataProvider;
import com.bigdata.modules.telemetry.v1.metadata.support.ProviderSettings;
import com.bigdata.modules.telemetry.v1.spi.BackendException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ambari provider（Hadoop 生态服务的版本与 desired configs）。
 *
 * <p><b>未对真实 Ambari 验证</b>：当前环境没有 Ambari 实例，路径与字段按 Ambari REST 契约实现，
 * 只用录制响应做 fixture 单测。首次接入真实集群时必须核对 field 名称与
 * {@code desired_configs} 的结构，不要据印象调参。</p>
 *
 * <p>版本优先级：host_component 的 {@code HostRoles/version} → 集群
 * {@code /stack_versions}（并在 warnings 里说明"是栈版本，不是组件精确版本"）→
 * {@code rawVersion=null}（由核心标记 UNAVAILABLE）。绝不猜版本。</p>
 */
@Component
public class AmbariMetadataProvider implements ServiceMetadataProvider, MetadataCatalogContributor {

    public static final String PROVIDER_ID = "ambari";

    private static final long MAX_RESPONSE_BYTES = 8L * 1024 * 1024;
    private static final int MAX_CONFIG_TYPES = 20;

    /** serviceType/componentId → Ambari 的 service / component 名。 */
    private static final Map<String, List<String>> MAPPING = mapping();

    private final ProviderSettings settings;
    private final SecretResolver secretResolver;
    private final ObjectMapper objectMapper;

    public AmbariMetadataProvider(ProviderSettings settings, SecretResolver secretResolver,
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
        Set<String> serviceTypes = new LinkedHashSet<>();
        Set<String> componentIds = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : MAPPING.entrySet()) {
            String[] key = entry.getKey().split("\\|", 2);
            serviceTypes.add(key[0]);
            componentIds.add(key[1]);
        }
        return new MetadataCapability(PROVIDER_ID, "ambari", serviceTypes, componentIds,
                true, true, true, settings.intValue(PROVIDER_ID, "order", 10),
                List.of(new MetadataSection("desired_configs",
                        "Ambari desired_configs: type + tag")),
                List.of(new MaskingRule(null, "(?i)^ssl[._-].*",
                        MaskingMode.WHOLE_VALUE, "***")));
    }

    @Override
    public boolean enabled() {
        return settings.enabled(PROVIDER_ID);
    }

    @Override
    public MetadataFetchResult fetch(MetadataQuery query) {
        String cluster = cluster(query);
        List<String> mapped = MAPPING.get(query.target().target().serviceType() + "|"
                + query.target().target().componentId());
        if (mapped == null) {
            throw new BackendException(CapabilityCode.NOT_FOUND,
                    "Ambari 未映射该组件: " + query.target().target().componentId());
        }
        String service = mapped.get(0);
        String component = mapped.get(1);
        EngineRestClient client = new EngineRestClient(baseUrl(), authorizationHeader(),
                query.timeout(), MAX_RESPONSE_BYTES, objectMapper);

        List<RawVersion> versions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (query.wantsVersion()) {
            versions.add(fetchVersion(client, query, cluster, component, warnings));
        }
        List<RawConfigEntry> configs = query.wantsConfig()
                ? fetchConfigs(client, query, cluster, service, component) : List.of();
        return new MetadataFetchResult(versions, configs, warnings);
    }

    @Override
    public List<MetadataCatalogEntry> contribute() {
        if (!enabled()) {
            return List.of();
        }
        List<MetadataCatalogEntry> entries = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : MAPPING.entrySet()) {
            String[] key = entry.getKey().split("\\|", 2);
            entries.add(new MetadataCatalogEntry(key[0], key[1], true, List.of(),
                    List.of(PROVIDER_ID)));
        }
        return entries;
    }

    // ------------------------------------------------------------------ 取数

    private RawVersion fetchVersion(EngineRestClient client, MetadataQuery query, String cluster,
                                    String component, List<String> warnings) {
        String componentId = query.target().target().componentId();
        String hostId = query.target().target().hostId();
        if (!"cluster".equals(hostId)) {
            JsonNode items = client.getJson(path("/clusters/" + encode(cluster) + "/hosts/"
                    + encode(hostId) + "/host_components")
                    + "?fields=HostRoles/component_name,HostRoles/version,HostRoles/state")
                    .path("items");
            for (JsonNode item : items) {
                JsonNode roles = item.path("HostRoles");
                if (component.equalsIgnoreCase(roles.path("component_name").asText(""))) {
                    String raw = roles.path("version").asText(null);
                    if (raw != null && !raw.isBlank()) {
                        return new RawVersion(componentId, hostId, raw, null, "Apache",
                                "ambari", "ambari:host_components");
                    }
                }
            }
        }
        JsonNode stackItems = client.getJson(path("/clusters/" + encode(cluster)
                + "/stack_versions")).path("items");
        for (JsonNode item : stackItems) {
            String raw = item.path("ClusterStackVersions").path("version").asText(null);
            if (raw != null && !raw.isBlank()) {
                warnings.add("AMBARI_STACK_VERSION: 使用集群栈版本 " + raw
                        + "，不是组件精确版本");
                return new RawVersion(componentId, hostId, raw, null, "Apache", "ambari",
                        "ambari:stack_versions");
            }
        }
        return new RawVersion(componentId, hostId, null, null, "Apache", "ambari",
                "ambari:stack_versions");
    }

    private List<RawConfigEntry> fetchConfigs(EngineRestClient client, MetadataQuery query,
                                              String cluster, String service, String component) {
        JsonNode info = client.getJson(path("/clusters/" + encode(cluster) + "/services/"
                + encode(service) + "/components/" + encode(component))
                + "?fields=ServiceComponentInfo/desired_configs");
        JsonNode desired = info.path("ServiceComponentInfo").path("desired_configs");
        if (!desired.isObject()) {
            desired = info.path("desired_configs");
        }
        if (!desired.isObject() || desired.isEmpty()) {
            return List.of();
        }
        List<RawConfigEntry> entries = new ArrayList<>();
        int types = 0;
        var fields = desired.fields();
        while (fields.hasNext() && entries.size() < query.maxConfigItems()
                && types < MAX_CONFIG_TYPES) {
            Map.Entry<String, JsonNode> item = fields.next();
            String type = item.getKey();
            String tag = item.getValue().path("tag").asText(null);
            if (tag == null || tag.isBlank()) {
                continue;
            }
            types++;
            JsonNode configurations = client.getJson(path("/clusters/" + encode(cluster)
                    + "/configurations") + "?type=" + encode(type) + "&tag=" + encode(tag))
                    .path("items");
            for (JsonNode configuration : configurations) {
                JsonNode properties = configuration.path("Configuration").path("properties");
                if (!properties.isObject()) {
                    continue;
                }
                var propertyFields = properties.fields();
                while (propertyFields.hasNext() && entries.size() < query.maxConfigItems()) {
                    Map.Entry<String, JsonNode> property = propertyFields.next();
                    entries.add(new RawConfigEntry(type, property.getKey(),
                            property.getValue().asText(null), "string", false,
                            "ambari:configurations?type=" + type));
                }
            }
        }
        return entries;
    }

    // ------------------------------------------------------------------ 配置

    private String cluster(MetadataQuery query) {
        String fromRef = query.target().ref().get("cluster");
        if (fromRef != null && !fromRef.isBlank()) {
            return fromRef;
        }
        String configured = settings.string(PROVIDER_ID, "cluster");
        if (configured != null) {
            return configured;
        }
        throw new BackendException(CapabilityCode.NOT_CONFIGURED,
                "Ambari 需要 ref.cluster 或 telemetry.metadata.providers.ambari.cluster",
                List.of(new Requirement(RequirementKind.CONFIG,
                        "telemetry.metadata.providers.ambari.cluster", "默认集群名")));
    }

    private String baseUrl() {
        String baseUrl = settings.string(PROVIDER_ID, "base-url");
        if (baseUrl == null) {
            throw new BackendException(CapabilityCode.NOT_CONFIGURED,
                    "缺少 telemetry.metadata.providers.ambari.base-url",
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "telemetry.metadata.providers.ambari.base-url",
                            "Ambari Server 地址")));
        }
        return baseUrl;
    }

    private String authorizationHeader() {
        Map<String, Object> auth = settings.map(PROVIDER_ID, "auth");
        if (auth.isEmpty() || "none".equalsIgnoreCase(String.valueOf(auth.get("type")))) {
            return null;
        }
        String username = auth.get("username") == null
                ? "admin" : String.valueOf(auth.get("username"));
        String passwordRef = auth.get("password-ref") == null
                ? null : String.valueOf(auth.get("password-ref"));
        String password;
        try {
            password = secretResolver.resolve(null, passwordRef, PROVIDER_ID + ".password");
        } catch (IllegalStateException e) {
            throw new BackendException(CapabilityCode.NOT_CONFIGURED, e.getMessage(),
                    List.of(new Requirement(RequirementKind.CREDENTIAL,
                            "telemetry.metadata.providers.ambari.auth.password-ref",
                            "配置可解析的密码引用")));
        }
        return "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private String path(String suffix) {
        return "/api/v1" + suffix;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Map<String, List<String>> mapping() {
        Map<String, List<String>> mapping = new LinkedHashMap<>();
        mapping.put("hdfs|hdfs.namenode", List.of("HDFS", "NAMENODE"));
        mapping.put("hdfs|hdfs.datanode", List.of("HDFS", "DATANODE"));
        mapping.put("hdfs|hdfs.journalnode", List.of("HDFS", "JOURNALNODE"));
        mapping.put("hdfs|hdfs.zkfc", List.of("HDFS", "ZKFC"));
        mapping.put("hive|hive.hiveserver2", List.of("HIVE", "HIVESERVER2"));
        mapping.put("hive|hive.metastore", List.of("HIVE", "HIVE_METASTORE"));
        mapping.put("zookeeper|zookeeper.server", List.of("ZOOKEEPER", "ZOOKEEPER_SERVER"));
        mapping.put("hbase|hbase.master", List.of("HBASE", "HBASE_MASTER"));
        mapping.put("hbase|hbase.regionserver", List.of("HBASE", "HBASE_REGIONSERVER"));
        mapping.put("yarn|yarn.resourcemanager", List.of("YARN", "RESOURCEMANAGER"));
        mapping.put("yarn|yarn.nodemanager", List.of("YARN", "NODEMANAGER"));
        mapping.put("kafka|kafka.broker", List.of("KAFKA", "KAFKA_BROKER"));
        return java.util.Collections.unmodifiableMap(mapping);
    }
}
