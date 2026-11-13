package com.bigdata.modules.logs.model;

import java.util.Locale;

/**
 * 组件角色 —— 统一日志访问层的第二维。
 *
 * <p>同一服务的不同角色日志位置不同（如 DS worker 日志在 worker 本地、YARN AM 日志在 NM 容器目录），
 * 由适配器按角色选择访问方式；角色为空时按 {@link #GENERIC} 处理。</p>
 */
public enum ComponentRole {

    MASTER("master"),
    WORKER("worker"),
    API("api"),
    RESOURCE_MANAGER("resource_manager"),
    NODE_MANAGER("node_manager"),
    APPLICATION_MASTER("application_master"),
    HISTORY_SERVER("history_server"),
    JOB_MANAGER("job_manager"),
    TASK_MANAGER("task_manager"),
    DRIVER("driver"),
    EXECUTOR("executor"),
    GENERIC("generic");

    private final String wireName;

    ComponentRole(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static ComponentRole from(String value) {
        if (value == null || value.isBlank()) {
            return GENERIC;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "master" -> MASTER;
            case "worker" -> WORKER;
            case "api", "server" -> API;
            case "rm", "resource_manager", "resourcemanager" -> RESOURCE_MANAGER;
            case "nm", "node_manager", "nodemanager" -> NODE_MANAGER;
            case "am", "application_master", "applicationmaster" -> APPLICATION_MASTER;
            case "shs", "history_server", "historyserver" -> HISTORY_SERVER;
            case "jm", "job_manager", "jobmanager" -> JOB_MANAGER;
            case "tm", "task_manager", "taskmanager" -> TASK_MANAGER;
            case "driver" -> DRIVER;
            case "executor" -> EXECUTOR;
            case "generic", "any", "" -> GENERIC;
            default -> throw new IllegalArgumentException("不支持的组件角色: " + value);
        };
    }
}
