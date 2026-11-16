package com.bigdata.modules.telemetry.config.properties;

public class LogSourceConfig {
    private String serviceType = "";
    private String componentId = "";
    private String hostId = "cluster";
    private String stream = "";
    /** 单文件路径、通配符路径或目录。 */
    private String path = "";
    /** 行首时间戳格式（DateTimeFormatter 模式）；留空表示不支持时间范围过滤。 */
    private String timestampPattern = "";
    /** 时间戳时区，默认系统时区。 */
    private String zone = "";

    public String getServiceType() {
        return serviceType;
    }

    public void setServiceType(String serviceType) {
        this.serviceType = serviceType;
    }

    public String getComponentId() {
        return componentId;
    }

    public void setComponentId(String componentId) {
        this.componentId = componentId;
    }

    public String getHostId() {
        return hostId;
    }

    public void setHostId(String hostId) {
        this.hostId = hostId;
    }

    public String getStream() {
        return stream;
    }

    public void setStream(String stream) {
        this.stream = stream;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getTimestampPattern() {
        return timestampPattern;
    }

    public void setTimestampPattern(String timestampPattern) {
        this.timestampPattern = timestampPattern;
    }

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }
}
