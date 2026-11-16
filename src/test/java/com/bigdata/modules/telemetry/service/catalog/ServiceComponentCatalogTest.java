package com.bigdata.modules.telemetry.service.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import com.bigdata.modules.telemetry.vo.ComponentCatalogEntry;

/**
 * 服务身份目录的边界：可诊断引擎、metadata-only 服务与 ds（仅任务日志来源）必须区分清楚。
 *
 * <p>可诊断与否不作为查询入口的准入条件（指标/日志的实际准入由 telemetry 目录决定），
 * 这里只锁定「哪些 serviceType + componentId 组合存在」。</p>
 */
class ServiceComponentCatalogTest {

    @Test
    @DisplayName("九个可诊断引擎都在服务目录中，标识与 agentkit 一致")
    void diagnosableEnginesArePresent() {
        List<String> engines = List.of("spark", "flink", "doris", "starrocks", "clickhouse",
                "hbase", "hudi", "kafka", "pulsar");
        assertThat(engines).hasSize(9);
        assertThat(ServiceIdentityCatalog.components()).containsKeys(engines.toArray(String[]::new));
    }

    @Test
    @DisplayName("hdfs / hive / zookeeper 是 metadata-only，不进入可诊断引擎子集")
    void metadataOnlyServicesAreAdded() {
        assertThat(ServiceIdentityCatalog.componentsOf("hdfs"))
                .containsExactly("hdfs.namenode", "hdfs.datanode", "hdfs.journalnode", "hdfs.zkfc");
        assertThat(ServiceIdentityCatalog.componentsOf("hive"))
                .containsExactly("hive.hiveserver2", "hive.metastore");
        assertThat(ServiceIdentityCatalog.componentsOf("zookeeper"))
                .containsExactly("zookeeper.server");

        // metadata-only 服务的标识只存在于身份目录，不出现在 telemetry 目录的引擎条目里
        for (String serviceType : List.of("hdfs", "hive", "zookeeper")) {
            assertThat(ServiceIdentityCatalog.components()).containsKey(serviceType);
        }
    }

    @Test
    @DisplayName("ds 只提供任务日志来源，不是可诊断引擎")
    void dsIsLogSourceOnly() {
        assertThat(ServiceIdentityCatalog.componentsOf("ds")).containsExactly("ds.worker");
    }

    @Test
    @DisplayName("metadata-only 服务不声明任何指标 / 日志 / 引擎证据")
    void metadataOnlyServicesHaveNoDiagnosticEntries() {
        List<ComponentCatalogEntry> entries = new SeedCatalogContributor().contribute();
        assertThat(entries)
                .noneMatch(entry -> entry.componentId().startsWith("hdfs.")
                        || entry.componentId().startsWith("hive.")
                        || entry.componentId().startsWith("zookeeper."));
    }
}
