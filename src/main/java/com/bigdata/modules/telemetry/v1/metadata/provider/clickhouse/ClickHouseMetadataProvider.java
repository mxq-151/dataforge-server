package com.bigdata.modules.telemetry.v1.metadata.provider.clickhouse;

import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.Requirement;
import com.bigdata.modules.telemetry.model.RequirementKind;
import com.bigdata.modules.telemetry.shared.SecretResolver;
import com.bigdata.modules.telemetry.v1.backend.engine.EngineRestClient;
import com.bigdata.modules.telemetry.v1.metadata.catalog.MetadataCatalogContributor;
import com.bigdata.modules.telemetry.v1.metadata.catalog.MetadataCatalogEntry;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ClickHouse HTTP 接口 provider：{@code version()} + system.* 设置表。
 *
 * <p>取数路径（均已对真实实例核实）：</p>
 * <ul>
 *   <li>版本：{@code GET /?query=SELECT version() FORMAT JSON}；</li>
 *   <li>配置：{@code SELECT name, value, type, changed FROM system.<section> FORMAT JSON}，
 *       section 只允许 {@link #ALLOWED_SECTIONS} 白名单，避免把请求参数拼进 SQL；</li>
 *   <li>认证：Basic Auth，密码走 {@code SecretResolver}，不写进日志或 attempts。</li>
 * </ul>
 */
@Component
public class ClickHouseMetadataProvider implements ServiceMetadataProvider, MetadataCatalogContributor {

    public static final String PROVIDER_ID = "clickhouse-http";

    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:8123";
    private static final Set<String> ALLOWED_SECTIONS = new LinkedHashSet<>(List.of(
            "server_settings", "settings", "merge_tree_settings"));
    private static final long MAX_RESPONSE_BYTES = 8L * 1024 * 1024;

    private final ProviderSettings settings;
    private final SecretResolver secretResolver;
    private final ObjectMapper objectMapper;

    public ClickHouseMetadataProvider(ProviderSettings settings, SecretResolver secretResolver,
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
        List<MetadataSection> sections = new ArrayList<>();
        sections.add(new MetadataSection("server_settings", "system.server_settings"));
        sections.add(new MetadataSection("settings", "system.settings"));
        sections.add(new MetadataSection("merge_tree_settings", "system.merge_tree_settings"));
        return new MetadataCapability(PROVIDER_ID, "clickhouse-http",
                Set.of("clickhouse"), Set.of("clickhouse.server"),
                true, true, true, settings.intValue(PROVIDER_ID, "order", 20),
                sections, List.of());
    }

    @Override
    public boolean enabled() {
        return settings.enabled(PROVIDER_ID);
    }

    @Override
    public MetadataFetchResult fetch(MetadataQuery query) {
        String baseUrl = settings.string(PROVIDER_ID, "base-url");
        if (baseUrl == null) {
            baseUrl = DEFAULT_BASE_URL;
        }
        EngineRestClient client = new EngineRestClient(baseUrl, authorizationHeader(),
                query.timeout(), MAX_RESPONSE_BYTES, objectMapper);

        List<RawVersion> versions = query.wantsVersion()
                ? List.of(fetchVersion(client, query))
                : List.of();
        List<RawConfigEntry> configs = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (query.wantsConfig()) {
            for (String section : requestedSections(query)) {
                if (!ALLOWED_SECTIONS.contains(section)) {
                    warnings.add("忽略未知 section: " + section);
                    continue;
                }
                configs.addAll(fetchSection(client, section, query.maxConfigItems(), query.keys()));
            }
        }
        return new MetadataFetchResult(versions, configs, warnings);
    }

    @Override
    public List<MetadataCatalogEntry> contribute() {
        if (!enabled()) {
            return List.of();
        }
        return List.of(new MetadataCatalogEntry("clickhouse", "clickhouse.server", true,
                new ArrayList<>(ALLOWED_SECTIONS), List.of(PROVIDER_ID)));
    }

    // ------------------------------------------------------------------ 取数

    private RawVersion fetchVersion(EngineRestClient client, MetadataQuery query) {
        String sql = "SELECT version() FORMAT JSON";
        JsonNode root = client.getJson("/?query=" + URLEncoder.encode(sql, StandardCharsets.UTF_8));
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new BackendException(CapabilityCode.PARSE_ERROR,
                    "ClickHouse version() 响应缺少 data");
        }
        JsonNode first = data.get(0);
        String raw = first.isObject() && first.fields().hasNext()
                ? first.fields().next().getValue().asText(null) : null;
        return new RawVersion(query.target().target().componentId(),
                query.target().target().hostId(), raw, null, "ClickHouse",
                "clickhouse-server", "clickhouse:version()");
    }

    private List<RawConfigEntry> fetchSection(EngineRestClient client, String section, int limit,
                                              List<String> keys) {
        // keys 过滤在行上做，不能在 SQL 里拼键名；指定 keys 时不加 LIMIT，避免目标键被截断
        String sql = "SELECT name, value, type, changed FROM system." + section
                + (keys.isEmpty() ? " LIMIT " + Math.max(limit, 1) : "")
                + " FORMAT JSON";
        JsonNode root = client.getJson("/?query=" + URLEncoder.encode(sql, StandardCharsets.UTF_8));
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            throw new BackendException(CapabilityCode.PARSE_ERROR,
                    "ClickHouse system." + section + " 响应缺少 data");
        }
        List<RawConfigEntry> entries = new ArrayList<>();
        String source = "clickhouse:system." + section;
        for (JsonNode row : data) {
            String name = row.path("name").asText(null);
            if (name == null) {
                continue;
            }
            if (!keys.isEmpty() && !keys.contains(name)) {
                continue;
            }
            entries.add(new RawConfigEntry(section, name, row.path("value").asText(null),
                    row.path("type").asText(null), false, source));
            if (entries.size() >= limit) {
                break;
            }
        }
        return entries;
    }

    // ------------------------------------------------------------------ 配置

    private List<String> requestedSections(MetadataQuery query) {
        if (!query.sections().isEmpty()) {
            return query.sections();
        }
        List<String> configured = settings.list(PROVIDER_ID, "sections");
        return configured.isEmpty() ? new ArrayList<>(ALLOWED_SECTIONS) : configured;
    }

    private String authorizationHeader() {
        Map<String, Object> auth = settings.map(PROVIDER_ID, "auth");
        if (auth.isEmpty() || "none".equalsIgnoreCase(String.valueOf(auth.get("type")))) {
            return null;
        }
        Object user = auth.get("username");
        Object passwordRef = auth.get("password-ref");
        String password;
        try {
            password = secretResolver.resolve(null,
                    passwordRef == null ? null : String.valueOf(passwordRef),
                    PROVIDER_ID + ".password");
        } catch (IllegalStateException e) {
            throw new BackendException(CapabilityCode.NOT_CONFIGURED, e.getMessage(),
                    List.of(new Requirement(RequirementKind.CREDENTIAL,
                            "telemetry.metadata.providers." + PROVIDER_ID + ".auth.password-ref",
                            "配置可解析的密码引用")));
        }
        String credentials = (user == null ? "default" : String.valueOf(user)) + ":" + password;
        return "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
}
