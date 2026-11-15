package com.bigdata.modules.telemetry.dto;

import com.bigdata.modules.logs.dto.LogQueryDTO;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一取数请求：四元组身份 + 业务引用 + 可选窗口。
 */
public class TelemetryQueryRequest {

    private String serviceType;
    private String componentId;
    private String hostId;
    private String dataTypeId;
    private Map<String, String> ref = new LinkedHashMap<>();
    private LogQueryDTO.TimeRange timeRange;
    private String keyword;
    private String cursor;
    private Integer limit;
    private Integer maxBytes;
    private Map<String, Object> params = new LinkedHashMap<>();

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

    public String getDataTypeId() {
        return dataTypeId;
    }

    public void setDataTypeId(String dataTypeId) {
        this.dataTypeId = dataTypeId;
    }

    public Map<String, String> getRef() {
        return ref;
    }

    public void setRef(Map<String, String> ref) {
        this.ref = ref == null ? new LinkedHashMap<>() : ref;
    }

    public LogQueryDTO.TimeRange getTimeRange() {
        return timeRange;
    }

    public void setTimeRange(LogQueryDTO.TimeRange timeRange) {
        this.timeRange = timeRange;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getCursor() {
        return cursor;
    }

    public void setCursor(String cursor) {
        this.cursor = cursor;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    public Integer getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(Integer maxBytes) {
        this.maxBytes = maxBytes;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params == null ? new LinkedHashMap<>() : params;
    }
}
