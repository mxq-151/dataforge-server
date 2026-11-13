package com.bigdata.modules.runtime.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * 组件（Flink TaskManager / Spark executor / vertex / stage）及其指标。
 *
 * <p>{@code type} 决定资源字段含义：taskmanager 用 slots，executor 用 cores/memory，
 * vertex/stage 只承载指标。</p>
 */
public class RuntimeComponentVO {

    private String id;
    private String name;
    /** taskmanager / executor / vertex / stage */
    private String type;
    private String host;
    /** ACTIVE / STALE / DEAD / REMOVED / UNKNOWN */
    private String status;
    private Integer slots;
    private Integer freeSlots;
    private Integer cores;
    private Long maxMemoryBytes;
    private Long usedMemoryBytes;
    private Long startTime;
    private Long endTime;
    private String note;
    private List<RuntimeMetricVO> metrics = new ArrayList<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getSlots() {
        return slots;
    }

    public void setSlots(Integer slots) {
        this.slots = slots;
    }

    public Integer getFreeSlots() {
        return freeSlots;
    }

    public void setFreeSlots(Integer freeSlots) {
        this.freeSlots = freeSlots;
    }

    public Integer getCores() {
        return cores;
    }

    public void setCores(Integer cores) {
        this.cores = cores;
    }

    public Long getMaxMemoryBytes() {
        return maxMemoryBytes;
    }

    public void setMaxMemoryBytes(Long maxMemoryBytes) {
        this.maxMemoryBytes = maxMemoryBytes;
    }

    public Long getUsedMemoryBytes() {
        return usedMemoryBytes;
    }

    public void setUsedMemoryBytes(Long usedMemoryBytes) {
        this.usedMemoryBytes = usedMemoryBytes;
    }

    public Long getStartTime() {
        return startTime;
    }

    public void setStartTime(Long startTime) {
        this.startTime = startTime;
    }

    public Long getEndTime() {
        return endTime;
    }

    public void setEndTime(Long endTime) {
        this.endTime = endTime;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public List<RuntimeMetricVO> getMetrics() {
        return metrics;
    }

    public void setMetrics(List<RuntimeMetricVO> metrics) {
        this.metrics = metrics == null ? new ArrayList<>() : metrics;
    }
}
