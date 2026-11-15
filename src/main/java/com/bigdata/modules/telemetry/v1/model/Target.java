package com.bigdata.modules.telemetry.v1.model;

/**
 * 取数目标：服务类型 → 服务组件 → host。
 *
 * <p>{@code hostId=cluster} 表示不绑定单台主机，允许跨 host 聚合；日志查询必须能唯一解析到
 * 单个源，否则返回 {@code HOST_REQUIRED}。</p>
 */
public record Target(String serviceType, String componentId, String hostId) {

    public static final String CLUSTER_HOST = "cluster";

    public boolean clusterScoped() {
        return CLUSTER_HOST.equals(hostId);
    }
}
