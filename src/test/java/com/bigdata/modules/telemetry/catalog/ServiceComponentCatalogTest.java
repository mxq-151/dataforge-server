package com.bigdata.modules.telemetry.catalog;

import com.bigdata.modules.telemetry.v1.catalog.SeedCatalogContributor;
import com.bigdata.modules.telemetry.v1.model.ComponentCatalogEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 服务目录的边界：九引擎约束只针对「可诊断引擎」，metadata-only 服务是纯增量。
 */
class ServiceComponentCatalogTest {

    @Test
    @DisplayName("可诊断引擎子集严格是九个，标识与 agentkit 一致")
    void diagnosableEnginesAreExactlyNine() {
        assertThat(ServiceComponentCatalog.DIAGNOSABLE_ENGINES).containsExactlyInAnyOrder(
                "spark", "flink", "doris", "starrocks", "clickhouse", "hbase", "hudi",
                "kafka", "pulsar");
        assertThat(ServiceComponentCatalog.DIAGNOSABLE_ENGINES).hasSize(9);
        for (String serviceType : ServiceComponentCatalog.DIAGNOSABLE_ENGINES) {
            assertThat(ServiceComponentCatalog.components())
                    .as("可诊断引擎必须在服务目录中: %s", serviceType)
                    .containsKey(serviceType);
        }
    }

    @Test
    @DisplayName("hdfs / hive / zookeeper 是 metadata-only，不进入可诊断引擎子集")
    void metadataOnlyServicesAreAdded() {
        assertThat(ServiceComponentCatalog.componentsOf("hdfs"))
                .containsExactly("hdfs.namenode", "hdfs.datanode", "hdfs.journalnode", "hdfs.zkfc");
        assertThat(ServiceComponentCatalog.componentsOf("hive"))
                .containsExactly("hive.hiveserver2", "hive.metastore");
        assertThat(ServiceComponentCatalog.componentsOf("zookeeper"))
                .containsExactly("zookeeper.server");

        for (String serviceType : List.of("hdfs", "hive", "zookeeper")) {
            assertThat(ServiceComponentCatalog.isMetadataOnly(serviceType)).isTrue();
            assertThat(ServiceComponentCatalog.isDiagnosableEngine(serviceType)).isFalse();
        }
        assertThat(ServiceComponentCatalog.isMetadataOnly("flink")).isFalse();
    }

    @Test
    @DisplayName("ds 只提供任务日志来源，不是可诊断引擎")
    void dsIsLogSourceOnly() {
        assertThat(ServiceComponentCatalog.componentsOf("ds")).containsExactly("ds.worker");
        assertThat(ServiceComponentCatalog.isDiagnosableEngine("ds")).isFalse();
        assertThat(ServiceComponentCatalog.isMetadataOnly("ds")).isFalse();
    }

    @Test
    @DisplayName("metadata-only 服务不声明任何指标 / 日志 / 引擎证据")
    void metadataOnlyServicesHaveNoDiagnosticEntries() {
        List<ComponentCatalogEntry> entries = new SeedCatalogContributor().contribute();
        assertThat(entries)
                .noneMatch(entry -> ServiceComponentCatalog.isMetadataOnly(entry.serviceType()))
                .noneMatch(entry -> entry.componentId().startsWith("hdfs.")
                        || entry.componentId().startsWith("hive.")
                        || entry.componentId().startsWith("zookeeper."));
    }
}
