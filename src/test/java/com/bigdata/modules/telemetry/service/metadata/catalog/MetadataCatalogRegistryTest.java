package com.bigdata.modules.telemetry.service.metadata.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import com.bigdata.modules.telemetry.service.metadata.model.MetadataCatalogEntry;
import com.bigdata.modules.telemetry.service.metadata.registry.MetadataCatalogRegistry;

class MetadataCatalogRegistryTest {

    @Test
    @DisplayName("同一组件被多个 provider 声明时按 providerId / section 合并，不视为冲突")
    void mergesEntriesOfSameComponent() {
        MetadataCatalogRegistry registry = new MetadataCatalogRegistry(List.of(
                () -> List.of(new MetadataCatalogEntry("doris", "doris.fe", true,
                        List.of("fe_config"), List.of("doris-fe"))),
                () -> List.of(new MetadataCatalogEntry("doris", "doris.fe", false,
                        List.of("fe_config", "extra"), List.of("manual")))));

        assertThat(registry.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.versionSupported()).isTrue();
            assertThat(entry.sections()).containsExactly("fe_config", "extra");
            assertThat(entry.providerIds()).containsExactly("doris-fe", "manual");
        });
    }
}
