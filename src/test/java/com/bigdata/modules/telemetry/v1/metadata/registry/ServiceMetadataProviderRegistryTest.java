package com.bigdata.modules.telemetry.v1.metadata.registry;

import com.bigdata.modules.telemetry.v1.metadata.model.MaskingMode;
import com.bigdata.modules.telemetry.v1.metadata.model.MaskingRule;
import com.bigdata.modules.telemetry.v1.metadata.model.MetadataScope;
import com.bigdata.modules.telemetry.v1.metadata.spi.MetadataCapability;
import com.bigdata.modules.telemetry.v1.metadata.spi.MetadataFetchResult;
import com.bigdata.modules.telemetry.v1.metadata.spi.MetadataQuery;
import com.bigdata.modules.telemetry.v1.metadata.spi.ServiceMetadataProvider;
import com.bigdata.modules.telemetry.v1.model.Target;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceMetadataProviderRegistryTest {

    private static final Target CLICKHOUSE =
            new Target("clickhouse", "clickhouse.server", "ch-1");

    @Test
    @DisplayName("providerId 重复启动即失败")
    void rejectsDuplicateProviderId() {
        assertThatThrownBy(() -> new ServiceMetadataProviderRegistry(List.of(
                provider("dup", "clickhouse", 20), provider("dup", "doris", 30))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复的 metadata providerId");
    }

    @Test
    @DisplayName("providerId 为空启动即失败")
    void rejectsBlankProviderId() {
        assertThatThrownBy(() -> new ServiceMetadataProviderRegistry(List.of(
                provider(" ", "clickhouse", 20))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少 providerId");
    }

    @Test
    @DisplayName("按 order 升序，同 order 按 providerId 字典序")
    void sortsByOrderThenId() {
        ServiceMetadataProviderRegistry registry = new ServiceMetadataProviderRegistry(List.of(
                provider("z", "clickhouse", 20),
                provider("a", "clickhouse", 20),
                provider("first", "clickhouse", 10)));

        assertThat(registry.providers()).extracting(ServiceMetadataProvider::providerId)
                .containsExactly("first", "a", "z");
    }

    @Test
    @DisplayName("候选过滤：serviceType / componentId / scope 都要匹配")
    void candidatesFilterByTargetAndScope() {
        ServiceMetadataProvider versionOnly = provider("version-only", "clickhouse", 20,
                true, false);
        ServiceMetadataProvider both = provider("both", "clickhouse", 30, true, true);
        ServiceMetadataProviderRegistry registry = new ServiceMetadataProviderRegistry(
                List.of(versionOnly, both));

        assertThat(registry.candidates(CLICKHOUSE, EnumSet.of(MetadataScope.VERSION)))
                .extracting(ServiceMetadataProvider::providerId)
                .containsExactly("version-only", "both");
        assertThat(registry.candidates(CLICKHOUSE, EnumSet.of(MetadataScope.CONFIG)))
                .extracting(ServiceMetadataProvider::providerId)
                .containsExactly("both");
        assertThat(registry.candidates(new Target("doris", "doris.fe", "x"),
                EnumSet.allOf(MetadataScope.class))).isEmpty();
    }

    @Test
    @DisplayName("非法脱敏正则启动即失败")
    void rejectsInvalidMaskingRule() {
        ServiceMetadataProvider provider = new FakeProvider("bad", true,
                capability("bad", Set.of("clickhouse"), Set.of(), true, true, true, 20,
                        List.of(new MaskingRule("([", "^.*$", MaskingMode.WHOLE_VALUE, "***"))));

        assertThatThrownBy(() -> new ServiceMetadataProviderRegistry(List.of(provider)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ 辅助

    private ServiceMetadataProvider provider(String id, String serviceType, int order) {
        return provider(id, serviceType, order, true, true);
    }

    private ServiceMetadataProvider provider(String id, String serviceType, int order,
                                             boolean version, boolean config) {
        return new FakeProvider(id, true,
                capability(id, Set.of(serviceType), Set.of(), version, config, true, order,
                        List.of()));
    }

    private MetadataCapability capability(String id, Set<String> serviceTypes,
                                          Set<String> componentIds, boolean version,
                                          boolean config, boolean fallback, int order,
                                          List<MaskingRule> rules) {
        return new MetadataCapability(id, id, serviceTypes, componentIds, version, config,
                fallback, order, List.of(), rules);
    }

    private static final class FakeProvider implements ServiceMetadataProvider {

        private final String id;
        private final boolean enabled;
        private final MetadataCapability capability;

        private FakeProvider(String id, boolean enabled, MetadataCapability capability) {
            this.id = id;
            this.enabled = enabled;
            this.capability = capability;
        }

        @Override
        public String providerId() {
            return id;
        }

        @Override
        public MetadataCapability capability() {
            return capability;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public MetadataFetchResult fetch(MetadataQuery query) {
            return MetadataFetchResult.empty();
        }
    }
}
