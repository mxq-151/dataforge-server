package com.bigdata.modules.telemetry.v1.metadata.provider.jdbc;

import com.bigdata.modules.telemetry.model.CapabilityCode;
import com.bigdata.modules.telemetry.model.Requirement;
import com.bigdata.modules.telemetry.model.RequirementKind;
import com.bigdata.modules.telemetry.shared.SecretResolver;
import com.bigdata.modules.telemetry.v1.metadata.catalog.MetadataCatalogContributor;
import com.bigdata.modules.telemetry.v1.metadata.catalog.MetadataCatalogEntry;
import com.bigdata.modules.telemetry.v1.metadata.model.MetadataSection;
import com.bigdata.modules.telemetry.v1.metadata.spi.MetadataCapability;
import com.bigdata.modules.telemetry.v1.metadata.spi.MetadataFetchResult;
import com.bigdata.modules.telemetry.v1.metadata.spi.MetadataQuery;
import com.bigdata.modules.telemetry.v1.metadata.spi.RawConfigEntry;
import com.bigdata.modules.telemetry.v1.metadata.spi.RawVersion;
import com.bigdata.modules.telemetry.v1.metadata.spi.ServiceMetadataProvider;
import com.bigdata.modules.telemetry.v1.metadata.support.JdbcMetadataClient;
import com.bigdata.modules.telemetry.v1.metadata.support.JdbcMetadataClient.ReadOnlyStatement;
import com.bigdata.modules.telemetry.v1.metadata.support.MetadataSqlClient;
import com.bigdata.modules.telemetry.v1.metadata.support.ProviderSettings;
import com.bigdata.modules.telemetry.v1.spi.BackendException;
import org.springframework.beans.factory.DisposableBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Doris / StarRocks 共用的 FE 管理面 provider（MySQL 协议）。
 *
 * <p>取数路径：{@code SHOW FRONTENDS} / {@code SHOW BACKENDS} 取版本（{@code Version} 列），
 * {@code ADMIN SHOW FRONTEND CONFIG} 取 FE 配置。{@code SELECT version()} 返回的是 MySQL 兼容
 * 版本（如 5.7.99），不代表产品版本，禁止使用。</p>
 *
 * <p>连接池、超时、只读白名单、密码解析全部在 {@link JdbcMetadataClient} 与
 * {@link SecretResolver} 内完成；本类不打印账号密码，也不把配置值写进异常。</p>
 */
public abstract class AbstractFeJdbcMetadataProvider
        implements ServiceMetadataProvider, MetadataCatalogContributor, DisposableBean {

    private static final String CONFIG_SECTION = "fe_config";

    private final ProviderSettings settings;
    private final SecretResolver secretResolver;
    private final String providerId;
    private final String providerType;
    private final String serviceType;
    private final String feComponentId;
    private final String beComponentId;
    private final String vendor;
    private final String distribution;
    private final int defaultOrder;

    private volatile MetadataSqlClient client;
    private final MetadataSqlClient fixedClient;

    protected AbstractFeJdbcMetadataProvider(ProviderSettings settings,
                                             SecretResolver secretResolver,
                                             String providerId,
                                             String providerType,
                                             String serviceType,
                                             String feComponentId,
                                             String beComponentId,
                                             String vendor,
                                             String distribution,
                                             int defaultOrder) {
        this(settings, secretResolver, providerId, providerType, serviceType,
                feComponentId, beComponentId, vendor, distribution, defaultOrder, null);
    }

    /** {@code fixedClient} 仅供单测注入录制行集；生产传 null。 */
    protected AbstractFeJdbcMetadataProvider(ProviderSettings settings,
                                             SecretResolver secretResolver,
                                             String providerId,
                                             String providerType,
                                             String serviceType,
                                             String feComponentId,
                                             String beComponentId,
                                             String vendor,
                                             String distribution,
                                             int defaultOrder,
                                             MetadataSqlClient fixedClient) {
        this.settings = settings;
        this.secretResolver = secretResolver;
        this.providerId = providerId;
        this.providerType = providerType;
        this.serviceType = serviceType;
        this.feComponentId = feComponentId;
        this.beComponentId = beComponentId;
        this.vendor = vendor;
        this.distribution = distribution;
        this.defaultOrder = defaultOrder;
        this.fixedClient = fixedClient;
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public MetadataCapability capability() {
        return new MetadataCapability(providerId, providerType,
                Set.of(serviceType), Set.of(feComponentId, beComponentId),
                true, true, true, settings.intValue(providerId, "order", defaultOrder),
                List.of(new MetadataSection(CONFIG_SECTION,
                        "ADMIN SHOW FRONTEND CONFIG")),
                List.of());
    }

    @Override
    public boolean enabled() {
        return settings.enabled(providerId);
    }

    @Override
    public List<MetadataCatalogEntry> contribute() {
        if (!enabled()) {
            return List.of();
        }
        return List.of(
                new MetadataCatalogEntry(serviceType, feComponentId, true,
                        List.of(CONFIG_SECTION), List.of(providerId)),
                new MetadataCatalogEntry(serviceType, beComponentId, true,
                        List.of(), List.of(providerId)));
    }

    @Override
    public MetadataFetchResult fetch(MetadataQuery query) {
        String componentId = query.target().target().componentId();
        boolean frontend = feComponentId.equals(componentId);
        boolean backend = beComponentId.equals(componentId);
        if (!frontend && !backend) {
            throw new BackendException(CapabilityCode.NOT_FOUND,
                    providerId + " 不支持组件 " + componentId);
        }

        List<RawVersion> versions = query.wantsVersion()
                ? fetchVersions(query, frontend) : List.of();
        List<RawConfigEntry> configs = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (query.wantsConfig()) {
            if (frontend) {
                configs.addAll(fetchConfig(query));
            } else {
                warnings.add(componentId + " 的配置不在本 provider 支持范围（仅 FE 配置）");
            }
        }
        return new MetadataFetchResult(versions, configs, warnings);
    }

    @Override
    public void destroy() {
        MetadataSqlClient current = client;
        if (current != null) {
            current.close();
        }
    }

    // ------------------------------------------------------------------ 取数

    private List<RawVersion> fetchVersions(MetadataQuery query, boolean frontend) {
        ReadOnlyStatement statement = frontend
                ? ReadOnlyStatement.SHOW_FRONTENDS : ReadOnlyStatement.SHOW_BACKENDS;
        String componentId = frontend ? feComponentId : beComponentId;
        String source = providerId + ":" + statement.sql();
        List<RawVersion> versions = new ArrayList<>();
        for (Map<String, String> row : client(query).query(statement)) {
            String raw = column(row, "Version");
            String host = frontend ? column(row, "IP") : column(row, "Host");
            if (host == null) {
                host = query.target().target().hostId();
            }
            versions.add(new RawVersion(componentId, host, raw, null, vendor, distribution,
                    source));
        }
        return versions;
    }

    private List<RawConfigEntry> fetchConfig(MetadataQuery query) {
        List<RawConfigEntry> entries = new ArrayList<>();
        String source = providerId + ":ADMIN SHOW FRONTEND CONFIG";
        for (Map<String, String> row : client(query).query(
                ReadOnlyStatement.ADMIN_SHOW_FRONTEND_CONFIG)) {
            String key = column(row, "Key");
            if (key == null) {
                continue;
            }
            // keys 过滤必须在截断之前：否则目标键可能排在 limit 之外被误判为不存在
            if (!query.keys().isEmpty() && !query.keys().contains(key)) {
                continue;
            }
            entries.add(new RawConfigEntry(CONFIG_SECTION, key, column(row, "Value"),
                    column(row, "Type"), false, source));
            if (entries.size() >= query.maxConfigItems()) {
                break;
            }
        }
        return entries;
    }

    private MetadataSqlClient client(MetadataQuery query) {
        if (fixedClient != null) {
            return fixedClient;
        }
        MetadataSqlClient current = client;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (client == null) {
                String jdbcUrl = settings.required(providerId, "jdbc-url");
                String username = settings.string(providerId, "username");
                String passwordRef = settings.string(providerId, "password-ref");
                String password;
                try {
                    password = secretResolver.resolve(null, passwordRef,
                            providerId + ".password-ref");
                } catch (IllegalStateException e) {
                    throw new BackendException(CapabilityCode.NOT_CONFIGURED, e.getMessage(),
                            List.of(new Requirement(RequirementKind.CREDENTIAL,
                                    "telemetry.metadata.providers." + providerId + ".password-ref",
                                    "配置可解析的密码引用")));
                }
                client = new JdbcMetadataClient(jdbcUrl,
                        username == null ? "root" : username, password,
                        (int) query.timeout().toMillis());
            }
            return client;
        }
    }

    /** 列名大小写不敏感：Doris 与 StarRocks 的列名大小写并不完全一致。 */
    private String column(Map<String, String> row, String name) {
        for (Map.Entry<String, String> entry : row.entrySet()) {
            if (entry.getKey() != null
                    && entry.getKey().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
                return entry.getValue();
            }
        }
        return null;
    }
}
