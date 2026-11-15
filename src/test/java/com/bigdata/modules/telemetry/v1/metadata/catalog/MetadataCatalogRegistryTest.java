package com.bigdata.modules.telemetry.v1.metadata.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    @DisplayName("entriesOf 按 serviceType / componentId 过滤，componentId 为空返回该服务全部")
    void filtersByServiceAndComponent() {
        MetadataCatalogRegistry registry = new MetadataCatalogRegistry(List.of(
                () -> List.of(
                        new MetadataCatalogEntry("hdfs", "hdfs.namenode", true, List.of(),
                                List.of("ambari")),
                        new MetadataCatalogEntry("hdfs", "hdfs.datanode", true, List.of(),
                                List.of("ambari")),
                        new MetadataCatalogEntry("hive", "hive.metastore", true, List.of(),
                                List.of("ambari")))));

        assertThat(registry.entriesOf("hdfs", null)).hasSize(2);
        assertThat(registry.entriesOf("hdfs", "hdfs.datanode")).singleElement()
                .satisfies(entry -> assertThat(entry.componentId()).isEqualTo("hdfs.datanode"));
        assertThat(registry.entriesOf("kafka", null)).isEmpty();
    }
}
