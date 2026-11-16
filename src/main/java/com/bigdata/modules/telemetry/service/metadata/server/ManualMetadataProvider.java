package com.bigdata.modules.telemetry.service.metadata.server;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.Requirement;
import com.bigdata.modules.telemetry.common.model.RequirementKind;
import com.bigdata.modules.telemetry.service.metadata.spi.MetadataCatalogContributor;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataCatalogEntry;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataCapability;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataFetchResult;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataQuery;
import com.bigdata.modules.telemetry.service.metadata.model.RawConfigEntry;
import com.bigdata.modules.telemetry.service.metadata.model.RawVersion;
import com.bigdata.modules.telemetry.service.metadata.spi.ServiceMetadataProvider;
import com.bigdata.modules.telemetry.service.metadata.support.ProviderSettings;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * manual / static provider：从 {@code telemetry.metadata.providers.manual.entries[]} 读用户手填的
 * 版本与配置，覆盖没有管理平台的场景。
 *
 * <p>配置即数据：不做任何推断，未配置的 target 返回 NOT_CONFIGURED；版本标准化与脱敏仍走核心。</p>
 */
@Component
public class ManualMetadataProvider implements ServiceMetadataProvider, MetadataCatalogContributor {

    public static final String PROVIDER_ID = "manual";

    private final ProviderSettings settings;

    public ManualMetadataProvider(ProviderSettings settings) {
        this.settings = settings;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public MetadataCapability capability() {
        return new MetadataCapability(PROVIDER_ID, "manual", Set.of("*"), Set.of(),
                true, true, true, settings.intValue(PROVIDER_ID, "order", 90),
                List.of(), List.of());
    }

    @Override
    public boolean enabled() {
        return settings.enabled(PROVIDER_ID);
    }

    @Override
    public MetadataFetchResult fetch(MetadataQuery query) {
        Map<String, Object> matched = findEntry(query);
        if (matched == null) {
            throw new BackendException(CapabilityCode.NOT_CONFIGURED,
                    "manual provider 未配置该 target: " + query.target().target().serviceType()
                            + "/" + query.target().target().componentId(),
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "telemetry.metadata.providers.manual.entries",
                            "为该 target 增加一条手填条目")));
        }
        List<RawVersion> versions = query.wantsVersion()
                ? List.of(new RawVersion(text(matched, "component-id"),
                text(matched, "host-id"), text(matched, "raw-version"),
                text(matched, "build-hash"), text(matched, "vendor"),
                text(matched, "distribution"), "manual:entries"))
                : List.of();
        List<RawConfigEntry> configs = new ArrayList<>();
        if (query.wantsConfig()) {
            Object raw = matched.get("configs");
            if (raw instanceof List<?> items) {
                for (Object item : items) {
                    if (item instanceof Map<?, ?> config) {
                        configs.add(new RawConfigEntry(
                                stringValue(config.get("section")),
                                stringValue(config.get("key")),
                                stringValue(config.get("value")), "string", false,
                                "manual:entries"));
                    }
                }
            }
        }
        return new MetadataFetchResult(versions, configs, List.of());
    }

    @Override
    public List<MetadataCatalogEntry> contribute() {
        if (!enabled()) {
            return List.of();
        }
        List<MetadataCatalogEntry> entries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> entry : settings.objects(PROVIDER_ID, "entries")) {
            String serviceType = text(entry, "service-type");
            String componentId = text(entry, "component-id");
            if (serviceType == null || componentId == null) {
                continue;
            }
            if (!seen.add(serviceType + "|" + componentId)) {
                continue;
            }
            entries.add(new MetadataCatalogEntry(serviceType, componentId,
                    text(entry, "raw-version") != null, List.of(), List.of(PROVIDER_ID)));
        }
        return entries;
    }

    private Map<String, Object> findEntry(MetadataQuery query) {
        String serviceType = query.target().target().serviceType();
        String componentId = query.target().target().componentId();
        String hostId = query.target().target().hostId();
        for (Map<String, Object> entry : settings.objects(PROVIDER_ID, "entries")) {
            if (!serviceType.equals(text(entry, "service-type"))
                    || !componentId.equals(text(entry, "component-id"))) {
                continue;
            }
            String entryHost = text(entry, "host-id");
            if (entryHost == null || entryHost.equals(hostId)) {
                return entry;
            }
        }
        return null;
    }

    private String text(Map<String, Object> entry, String key) {
        Object value = entry.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
