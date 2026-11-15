package com.bigdata.modules.telemetry.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JMX 类数据的服务端通道配置（{@code telemetry.jmx.*}）。
 *
 * <p>模板中的 {@code {host}} 在 host inventory 落地后替换为具体主机；
 * 未配置时能力判定返回 NOT_CONFIGURED，不伪造数据。</p>
 */
@Component
@ConfigurationProperties(prefix = "telemetry.jmx")
public class TelemetryJmxProperties {

    /** 例：http://{host}:8778/jolokia */
    private String jolokiaUrlTemplate = "";

    /** 服务端 JMX 桥地址模板；桥尚未部署时留空 */
    private String serverUrlTemplate = "";

    public String getJolokiaUrlTemplate() {
        return jolokiaUrlTemplate;
    }

    public void setJolokiaUrlTemplate(String jolokiaUrlTemplate) {
        this.jolokiaUrlTemplate = jolokiaUrlTemplate == null ? "" : jolokiaUrlTemplate;
    }

    public String getServerUrlTemplate() {
        return serverUrlTemplate;
    }

    public void setServerUrlTemplate(String serverUrlTemplate) {
        this.serverUrlTemplate = serverUrlTemplate == null ? "" : serverUrlTemplate;
    }
}
