package com.bigdata.modules.integration.service.submit;

/**
 * 集群侧作业状态。
 */
public class ClusterJobStatus {

    /** 集群原始状态，如 RUNNING / FINISHED / FAILED；未知时为 null */
    private final String clusterStatus;
    /** 集群是否可达 */
    private final boolean reachable;
    private final String message;

    public ClusterJobStatus(String clusterStatus, boolean reachable, String message) {
        this.clusterStatus = clusterStatus;
        this.reachable = reachable;
        this.message = message;
    }

    public static ClusterJobStatus reachable(String clusterStatus, String message) {
        return new ClusterJobStatus(clusterStatus, true, message);
    }

    public static ClusterJobStatus unreachable(String message) {
        return new ClusterJobStatus(null, false, message);
    }

    public String getClusterStatus() {
        return clusterStatus;
    }

    public boolean isReachable() {
        return reachable;
    }

    public String getMessage() {
        return message;
    }
}
