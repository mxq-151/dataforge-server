package com.bigdata.modules.telemetry.v1.vo;

import com.bigdata.modules.telemetry.v1.metadata.catalog.MetadataCatalogEntry;
import com.bigdata.modules.telemetry.v1.model.ComponentCatalogEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁住 {@code mode=catalog} 的向下兼容：既有字段与层级不变，metadata[] 只增。
 */
class CatalogDataContractTest {

    @Test
    @DisplayName("CatalogData 字段顺序固定：catalogVersion / components / metadata")
    void legacyFieldsKeepOrderAndNames() {
        assertThat(Arrays.stream(TelemetryQueryV1Response.CatalogData.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactly("catalogVersion", "components", "metadata");
    }

    @Test
    @DisplayName("序列化后旧字段仍在顶层，metadata 与 components 平级")
    void serializedShapeKeepsLegacyKeys() throws Exception {
        TelemetryQueryV1Response.CatalogData data = new TelemetryQueryV1Response.CatalogData(
                "2026-11-15.2",
                List.of(new ComponentCatalogEntry("flink", "flink.jobmanager", List.of(),
                        List.of(), List.of(), com.bigdata.modules.telemetry.v1.model.CatalogStatus
                        .IMPLEMENTED, List.of())),
                List.of(new MetadataCatalogEntry("hdfs", "hdfs.namenode", true,
                        List.of("hdfs-site"), List.of("ambari"))));

        String json = new ObjectMapper().writeValueAsString(data);

        assertThat(json).startsWith("{\"catalogVersion\":\"2026-11-15.2\",\"components\":[")
                .contains("\"metadata\":[")
                .contains("\"providerIds\":[\"ambari\"]");
    }
}
