package com.bigdata.modules.telemetry.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.bigdata.modules.telemetry.config.properties.PromqlConfig;
import com.bigdata.modules.telemetry.config.properties.LocalDiskConfig;
import com.bigdata.modules.telemetry.config.properties.EsConfig;
import com.bigdata.modules.telemetry.config.properties.FlinkRestConfig;
import com.bigdata.modules.telemetry.config.properties.SparkShsRestConfig;
import com.bigdata.modules.telemetry.config.properties.YarnConfig;
import com.bigdata.modules.telemetry.config.properties.DsConfig;
import com.bigdata.modules.telemetry.config.properties.DorisAuditConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 存储后端实例配置（{@code telemetry.backends.*}）。
 *
 * <p>实例 id 由配置给出；目录里的逻辑后端名会展开为同类型的全部实例。未配置实例不会伪造数据，
 * 查询返回 NOT_CONFIGURED + 具体配置键。</p>
 */
@Component
@ConfigurationProperties(prefix = "telemetry.backends")
public class TelemetryBackendProperties {

    private List<PromqlConfig> prometheus = new ArrayList<>();
    private List<PromqlConfig> victoriametrics = new ArrayList<>();
    private List<LocalDiskConfig> localDisk = new ArrayList<>();
    private List<EsConfig> elasticsearch = new ArrayList<>();
    private List<FlinkRestConfig> flinkRest = new ArrayList<>();
    private List<SparkShsRestConfig> sparkShsRest = new ArrayList<>();
    private List<YarnConfig> yarn = new ArrayList<>();
    private List<DsConfig> dsApi = new ArrayList<>();
    private List<DorisAuditConfig> dorisAudit = new ArrayList<>();

    public List<PromqlConfig> getPrometheus() {
        return prometheus;
    }

    public void setPrometheus(List<PromqlConfig> prometheus) {
        this.prometheus = prometheus;
    }

    public List<PromqlConfig> getVictoriametrics() {
        return victoriametrics;
    }

    public void setVictoriametrics(List<PromqlConfig> victoriametrics) {
        this.victoriametrics = victoriametrics;
    }

    public List<LocalDiskConfig> getLocalDisk() {
        return localDisk;
    }

    public void setLocalDisk(List<LocalDiskConfig> localDisk) {
        this.localDisk = localDisk;
    }

    public List<EsConfig> getElasticsearch() {
        return elasticsearch;
    }

    public void setElasticsearch(List<EsConfig> elasticsearch) {
        this.elasticsearch = elasticsearch;
    }

    public List<FlinkRestConfig> getFlinkRest() {
        return flinkRest;
    }

    public void setFlinkRest(List<FlinkRestConfig> flinkRest) {
        this.flinkRest = flinkRest;
    }

    public List<SparkShsRestConfig> getSparkShsRest() {
        return sparkShsRest;
    }

    public void setSparkShsRest(List<SparkShsRestConfig> sparkShsRest) {
        this.sparkShsRest = sparkShsRest;
    }

    public List<YarnConfig> getYarn() {
        return yarn;
    }

    public void setYarn(List<YarnConfig> yarn) {
        this.yarn = yarn;
    }

    public List<DsConfig> getDsApi() {
        return dsApi;
    }

    public void setDsApi(List<DsConfig> dsApi) {
        this.dsApi = dsApi;
    }

    public List<DorisAuditConfig> getDorisAudit() {
        return dorisAudit;
    }

    public void setDorisAudit(List<DorisAuditConfig> dorisAudit) {
        this.dorisAudit = dorisAudit;
    }

    /** Flink JobManager / REST 网关配置。 */
    /** Spark History Server（mode=history）或 live UI（mode=live）配置。 */
    /** Prometheus / VictoriaMetrics 共用配置，差异只在 base-url、path-prefix 与 query 选项。 */
    /** 一条「服务 + 组件 + host + stream → 文件」的定位配置。 */
}
